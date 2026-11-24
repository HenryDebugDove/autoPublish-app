package com.ven.assists.simple.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.blankj.utilcode.util.Utils
import com.blankj.utilcode.util.ToastUtils
import com.ven.assists.service.AssistsService
import com.ven.assists.simple.overlays.OverlayBasic
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.simple.douyin.DouyinPublisher
import com.ven.assists.simple.kuaishou.KuaishouPublisher
import com.ven.assists.simple.xianyu.XianyuBatchRunner
import com.ven.assists.simple.xianyu.XianyuListingRunner
import com.ven.assists.simple.xianyu.XianyuPolishRunner

import com.ven.assists.utils.CoroutineWrapper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 连接 PC 端控制面板的 WebSocket 客户端，负责：
 * 1. 保持心跳，汇报设备状态
 * 2. 接收后台的配置 / 发布指令并执行微博自动化
 *
 * 连接策略：应用层心跳 + OkHttp ping；断开后指数退避重连，避免固定间隔打爆服务端。
 */
object ControlPanelBridge {
    private const val TAG = "ControlPanelBridge"
    /** 应用层心跳间隔；需明显小于服务端 HEARTBEAT_TIMEOUT */
    private const val HEARTBEAT_INTERVAL = 8_000L
    private const val RECONNECT_BASE_MS = 1_000L
    private const val RECONNECT_MAX_MS = 30_000L
    private const val TOAST_COOLDOWN_MS = 60_000L
    private const val SERVER_BASE_URL = "http://118.25.152.48:4001"
    private const val WS_URL = "ws://118.25.152.48:4001/ws"

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 长连接不设读超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    /** 正在握手中的 socket，用于忽略已被替换连接的迟到回调 */
    @Volatile
    private var pendingSocket: WebSocket? = null

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var started = false
    private val connecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    @Volatile
    private var lastDisconnectToastAt = 0L

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    @Volatile
    var status: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set

    private val statusListeners = mutableSetOf<(ConnectionStatus) -> Unit>()

    fun start() {
        if (started) return
        started = true
        updateStatus(ConnectionStatus.CONNECTING)
        connect()
        fetchLatestConfig()
    }

    fun reconnectNow() {
        reconnectAttempt.set(0)
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocketQuietly("manual reconnect")
        connecting.set(false)
        updateStatus(ConnectionStatus.CONNECTING)
        connect()
    }

    fun addStatusListener(listener: (ConnectionStatus) -> Unit) {
        statusListeners.add(listener)
        listener(status)
    }

    fun removeStatusListener(listener: (ConnectionStatus) -> Unit) {
        statusListeners.remove(listener)
    }

    private fun updateStatus(newStatus: ConnectionStatus) {
        status = newStatus
        statusListeners.forEach { callback ->
            CoroutineWrapper.launch(isMain = true) {
                callback(newStatus)
            }
        }
    }

    private fun closeSocketQuietly(reason: String) {
        stopHeartbeat()
        val old = webSocket
        val pending = pendingSocket
        webSocket = null
        pendingSocket = null
        runCatching { old?.close(1000, reason) }
        if (pending != null && pending !== old) {
            runCatching { pending.cancel() }
        }
    }

    private fun isActiveSocket(ws: WebSocket): Boolean {
        return ws === webSocket || ws === pendingSocket
    }

    private fun connect() {
        if (!started) return
        if (!connecting.compareAndSet(false, true)) {
            Log.d(TAG, "已有连接尝试进行中，跳过")
            return
        }
        if (webSocket != null && status == ConnectionStatus.CONNECTED) {
            connecting.set(false)
            return
        }
        updateStatus(ConnectionStatus.CONNECTING)
        Log.i(TAG, "正在连接控制面板: $WS_URL (attempt=${reconnectAttempt.get()})")
        val request = Request.Builder().url(WS_URL).build()
        pendingSocket = okHttpClient.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            if (!isActiveSocket(ws)) {
                runCatching { ws.close(1000, "stale open") }
                return
            }
            reconnectJob?.cancel()
            reconnectJob = null
            pendingSocket = null
            webSocket = ws
            connecting.set(false)
            reconnectAttempt.set(0)
            Log.i(TAG, "WebSocket 已连接")
            updateStatus(ConnectionStatus.CONNECTED)
            sendRegister()
            startHeartbeatLoop()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (webSocket !== ws) return
            handleMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (!isActiveSocket(ws)) {
                Log.d(TAG, "忽略非当前连接关闭: $code $reason")
                return
            }
            Log.w(TAG, "WebSocket 已关闭: $code $reason")
            onSocketLost(ws, showToast = code != 1000)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (!isActiveSocket(ws)) {
                Log.d(TAG, "忽略非当前连接失败: ${t.message}")
                return
            }
            Log.e(TAG, "WebSocket 连接失败: ${t.message}")
            onSocketLost(ws, showToast = true, detail = t.message)
        }
    }

    private fun onSocketLost(ws: WebSocket?, showToast: Boolean, detail: String? = null) {
        if (ws != null && !isActiveSocket(ws)) return
        stopHeartbeat()
        if (webSocket === ws) webSocket = null
        if (pendingSocket === ws) pendingSocket = null
        connecting.set(false)
        updateStatus(ConnectionStatus.DISCONNECTED)
        if (showToast) {
            maybeToastDisconnect(detail)
        }
        scheduleReconnect()
    }

    private fun maybeToastDisconnect(detail: String?) {
        val now = System.currentTimeMillis()
        if (now - lastDisconnectToastAt < TOAST_COOLDOWN_MS) return
        lastDisconnectToastAt = now
        val msg = if (detail.isNullOrBlank()) {
            "控制面板连接已断开，正在重连…"
        } else {
            "控制面板连接失败: $detail"
        }
        ToastUtils.showShort(msg)
    }

    private fun scheduleReconnect() {
        if (!started) return
        reconnectJob?.cancel()
        val attempt = reconnectAttempt.getAndIncrement()
        val delayMs = (RECONNECT_BASE_MS * (1L shl attempt.coerceAtMost(5)))
            .coerceAtMost(RECONNECT_MAX_MS)
        Log.i(TAG, "将在 ${delayMs}ms 后重连 (attempt=$attempt)")
        reconnectJob = CoroutineWrapper.launch {
            delay(delayMs)
            connect()
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineWrapper.launch {
            // 连上后立刻再打一枪心跳，缩短「注册后到被认作在线」窗口
            while (true) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendRegister() {
        val payload = JSONObject().apply {
            put("type", "register")
            put("deviceInfo", buildDeviceInfo())
            put("timestamp", System.currentTimeMillis())
        }
        sendPayload(payload)
    }

    private fun sendHeartbeat() {
        val payload = JSONObject().apply {
            put("type", "heartbeat")
            put("deviceInfo", buildDeviceInfo())
            put("timestamp", System.currentTimeMillis())
        }
        sendPayload(payload)
    }

    private fun sendAck(message: String) {
        val payload = JSONObject().apply {
            put("type", "ack")
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        sendPayload(payload)
    }

    /**
     * 向控制面板上报发布进度（结构化事件，由 server 转发给管理端）。
     */
    fun sendPublishProgress(
        jobId: String,
        platform: String,
        phase: String,
        current: Int,
        total: Int,
        contentPreview: String = "",
        message: String = ""
    ) {
        val payload = JSONObject().apply {
            put("type", "publish_progress")
            put("jobId", jobId)
            put("platform", platform)
            put("deviceId", currentDeviceId())
            put("phase", phase)
            put("current", current)
            put("total", total)
            put("contentPreview", contentPreview.take(80))
            put("message", message)
            put("timestamp", System.currentTimeMillis())
        }
        sendPayload(payload)
    }

    private fun currentDeviceId(): String {
        return Settings.Secure.getString(
            Utils.getApp().contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }

    private fun sendPayload(payload: JSONObject) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "发送失败：WebSocket 未连接 (${payload.optString("type")})")
            return
        }
        val ok = runCatching { ws.send(payload.toString()) }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "WebSocket send 返回 false，触发重连")
            onSocketLost(ws, showToast = false)
        }
    }

    private fun handleMessage(text: String) {
        runCatching {
            val data = JSONObject(text)
            when (data.optString("type")) {
                "publish" -> handlePublishCommand(data)
                "publish_douyin" -> handleDouyinPublishCommand(data)
                "publish_kuaishou" -> handleKuaishouPublishCommand(data)
                "xianyu_task" -> handleXianyuTaskCommand(data)
                "config" -> applyConfigFromServer(data)
                "ping" -> sendPayload(JSONObject().apply {
                    put("type", "pong")
                    put("timestamp", System.currentTimeMillis())
                })
                else -> Log.d(TAG, "未知消息: $text")
            }
        }.onFailure {
            Log.e(TAG, "处理消息失败: ${it.message}")
        }
    }

    private fun handlePublishCommand(json: JSONObject) {
        val remoteTailTag = json.optString("tailTag")
        val content = json.optString("content")
        if (remoteTailTag.isNotBlank()) {
            WeiboPublisher.tailTag = remoteTailTag
        }
        if (content.isNotBlank()) {
            copyTextToClipboard(content)
        }
        sendAck("publish_received")
        CoroutineWrapper.launch(isMain = true) {
            runCatching {
                WeiboPublisher.publish(OverlayBasic.createWeiboAutomationContext())
            }.onFailure { Log.e(TAG, "执行微博发布失败: ${it.message}") }
        }
    }

    private fun handleDouyinPublishCommand(json: JSONObject) {
        val douyinTailTag = json.optString("douyinTailTag")
        if (douyinTailTag.isNotBlank()) {
            DouyinPublisher.tailTag = douyinTailTag
            Log.d(TAG, "已更新抖音 tailTag: $douyinTailTag")
        }
        val jobId = json.optString("jobId").ifBlank {
            "douyin-${System.currentTimeMillis()}"
        }
        DouyinPublisher.pendingJobId = jobId

        val templatesFromCmd = mutableListOf<String>()
        json.optJSONArray("douyinContentTemplates")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty()) templatesFromCmd.add(item)
            }
        }
        DouyinPublisher.pendingContentTemplates =
            templatesFromCmd.takeIf { it.isNotEmpty() }

        sendAck("publish_douyin_received")
        CoroutineWrapper.launch(isMain = true) {
            runCatching {
                DouyinPublisher.publish(OverlayBasic.createWeiboAutomationContext())
            }.onFailure { Log.e(TAG, "执行抖音发布失败: ${it.message}") }
        }
    }

    private fun handleKuaishouPublishCommand(json: JSONObject) {
        sendAck("publish_kuaishou_received")
        CoroutineWrapper.launch(isMain = true) {
            runCatching {
                KuaishouPublisher.publish(OverlayBasic.createWeiboAutomationContext())
            }.onFailure { Log.e(TAG, "执行快手发布失败: ${it.message}") }
        }
    }

    private fun handleXianyuTaskCommand(json: JSONObject) {
        when (json.optString("action")) {
            "refresh" -> {
                sendAck("xianyu_refresh_received")
                CoroutineWrapper.launch(isMain = true) {
                    runCatching {
                        XianyuBatchRunner.run(XianyuBatchRunner.createLogOnlyContext())
                    }.onFailure { Log.e(TAG, "执行闲鱼刷新消息失败: ${it.message}") }
                }
            }
            "polish" -> {
                sendAck("xianyu_polish_received")
                CoroutineWrapper.launch(isMain = true) {
                    runCatching {
                        XianyuPolishRunner.run(XianyuPolishRunner.createLogOnlyContext())
                    }.onFailure { Log.e(TAG, "执行闲鱼擦亮失败: ${it.message}") }
                }
            }
            "listing" -> {
                sendAck("xianyu_listing_received")
                CoroutineWrapper.launch(isMain = true) {
                    runCatching {
                        XianyuListingRunner.run(XianyuListingRunner.createLogOnlyContext())
                    }.onFailure { Log.e(TAG, "执行闲鱼上架失败: ${it.message}") }
                }
            }
            else -> Log.w(TAG, "未知闲鱼任务 action: ${json.optString("action")}")
        }
    }

    private fun applyConfigFromServer(json: JSONObject) {
        val tailTag = json.optString("tailTag")
        if (tailTag.isNotBlank()) {
            WeiboPublisher.tailTag = tailTag
        }
    }

    private fun fetchLatestConfig() {
        CoroutineWrapper.launch {
            try {
                val request = Request.Builder()
                    .url("${SERVER_BASE_URL}/api/config")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            applyConfigFromServer(JSONObject(body))
                        }
                    } else {
                        Log.w(TAG, "获取服务器配置失败: ${response.code}")
                        ToastUtils.showShort("获取控制面板配置失败: ${response.code}")
                    }
                    // 显式返回 Unit，避免 Kotlin 将 if 视为表达式
                    Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取配置异常: ${e.message}")
                ToastUtils.showShort("拉取控制面板配置异常: ${e.message}")
            }
        }
    }

    private fun copyTextToClipboard(text: String) {
        val service = AssistsService.instance ?: return
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("control_panel_content", text))
    }

    private fun buildDeviceInfo(): JSONObject {
        val androidId = Settings.Secure.getString(
            Utils.getApp().contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return JSONObject().apply {
            put("deviceId", androidId)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("device", Build.DEVICE)
        }
    }
}
