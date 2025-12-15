package com.ven.assists.simple.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.ven.assists.service.AssistsService
import com.ven.assists.simple.overlays.OverlayBasic
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.simple.douyin.DouyinPublisher
import com.ven.assists.simple.kuaishou.KuaishouPublisher
import com.ven.assists.simple.config.ServerConfig
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

/**
 * 连接 PC 端控制面板的 WebSocket 客户端，负责：
 * 1. 保持心跳，汇报设备状态
 * 2. 接收后台的配置 / 发布指令并执行微博自动化
 */
object ControlPanelBridge {
    private const val TAG = "ControlPanelBridge"
    private const val HEARTBEAT_INTERVAL = 10_000L

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    private var heartbeatJob: Job? = null
    private var started = false

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
        webSocket?.close(1000, "manual reconnect")
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

    private fun connect() {
        val request = Request.Builder().url(ServerConfig.WS_URL).build()
        okHttpClient.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            webSocket = ws
            Log.i(TAG, "WebSocket 已连接")
            updateStatus(ConnectionStatus.CONNECTED)
            sendRegister()
            startHeartbeatLoop()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket 已关闭: $code $reason")
            stopHeartbeat()
            updateStatus(ConnectionStatus.DISCONNECTED)
            ToastUtils.showShort("控制面板连接已断开")
            reconnect()
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket 连接失败: ${t.message}")
            stopHeartbeat()
            updateStatus(ConnectionStatus.DISCONNECTED)
            ToastUtils.showShort("控制面板连接失败: ${t.message ?: "未知错误"}")
            reconnect()
        }
    }

    private fun reconnect() {
        if (!started) return
        CoroutineWrapper.launch {
            delay(5000)
            connect()
            updateStatus(ConnectionStatus.CONNECTING)
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineWrapper.launch {
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

    private fun sendPayload(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    private fun handleMessage(text: String) {
        runCatching {
            val data = JSONObject(text)
            when (data.optString("type")) {
                "publish" -> handlePublishCommand(data)
                "publish_douyin" -> handleDouyinPublishCommand(data)
                "publish_kuaishou" -> handleKuaishouPublishCommand(data)
                "config" -> applyConfigFromServer(data)
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
        val douyinContentTemplate = json.optString("douyinContentTemplate")
        if (douyinTailTag.isNotBlank()) {
            DouyinPublisher.tailTag = douyinTailTag
            Log.d(TAG, "已更新抖音 tailTag: $douyinTailTag")
        }
        if (douyinContentTemplate.isNotBlank()) {
            DouyinPublisher.contentTemplate = douyinContentTemplate
            Log.d(TAG, "已更新抖音 contentTemplate")
        }
        sendAck("publish_douyin_received")
        CoroutineWrapper.launch(isMain = true) {
            runCatching {
                DouyinPublisher.publish(OverlayBasic.createWeiboAutomationContext())
            }.onFailure { Log.e(TAG, "执行抖音发布失败: ${it.message}") }
        }
    }

    private fun handleKuaishouPublishCommand(json: JSONObject) {
        val kuaishouContentTemplate = json.optString("kuaishouContentTemplate")
        if (kuaishouContentTemplate.isNotBlank()) {
            KuaishouPublisher.contentTemplate = kuaishouContentTemplate
            Log.d(TAG, "已更新快手 contentTemplate")
        }
        sendAck("publish_kuaishou_received")
        CoroutineWrapper.launch(isMain = true) {
            runCatching {
                KuaishouPublisher.publish(OverlayBasic.createWeiboAutomationContext())
            }.onFailure { Log.e(TAG, "执行快手发布失败: ${it.message}") }
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
                    .url("${ServerConfig.SERVER_BASE_URL}/api/config")
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
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("device", Build.DEVICE)
        }
    }
}

