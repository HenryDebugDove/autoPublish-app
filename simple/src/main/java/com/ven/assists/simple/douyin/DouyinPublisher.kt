package com.ven.assists.simple.douyin

import android.view.accessibility.AccessibilityNodeInfo
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.findByTags
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.findFirstParentClickable
import com.ven.assists.AssistsCore.getBoundsInScreen
import com.ven.assists.AssistsCore.gestureClick
import com.ven.assists.AssistsCore.nodeGestureClick
import com.ven.assists.AssistsCore.setNodeText
import com.ven.assists.AssistsCore.paste
import com.ven.assists.simple.AutomationLog
import com.ven.assists.simple.control.ControlPanelBridge
import com.ven.assists.simple.weibo.WeiboPublisher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 复用微博发布的上下文，后续如果需要独立能力再拆分。
 */
typealias DouyinContext = WeiboPublisher.Context

/**
 * 抖音自动化发布流程（预留架子，后续逐步补全）
 */
object DouyinPublisher {
    private const val SERVER_BASE_URL = "http://118.25.152.48:4001"

    /** 切换模板后等待「加载中」消失的超时时间 */
    private const val TEMPLATE_LOAD_TIMEOUT_MS = 30_000L
    /** 轮询「加载中」/下一步就绪的间隔 */
    private const val TEMPLATE_LOAD_POLL_MS = 300L
    /** 加载消失后再稳定一小段，避免遮罩闪烁误判 */
    private const val TEMPLATE_LOAD_SETTLE_MS = 400L
    /** 等待「下一步」可点的超时时间 */
    private const val NEXT_BUTTON_READY_TIMEOUT_MS = 30_000L
    /** 点击模板后，短暂等待「加载中」出现（避免加载尚未弹出就误判就绪） */
    private const val TEMPLATE_LOADING_APPEAR_WAIT_MS = 1_200L
    
    /**
     * 抖音文案模板列表，可在外部进行配置
     */
    var contentTemplates: List<String> = listOf("零基础学前端怕坚持不下来、被拖延症耳误？9年前端带徒，我来监督！从 HTML、CSS入门，逐步攻克 JS、Vue、React，最后练小程序/Uniapp项目。正向反馈看得见进步。日常督促打卡，想有人盯着学，跟我一起搞定前端！#前端 #程序员")
        @Synchronized get
        @Synchronized set
    
    /**
     * 抖音话题标签，可在外部进行配置
     */
    var tailTag: String = "#前端 #程序员"
        @Synchronized get
        @Synchronized set

    /** 本次发布任务 id（由控制面板下发，用于进度关联） */
    @Volatile
    var pendingJobId: String? = null

    /** 本次要发布的文案列表（优先于 /api/config，来自立即发布指令） */
    @Volatile
    var pendingContentTemplates: List<String>? = null

    private val httpClient = okhttp3.OkHttpClient()

    data class RemoteConfig(
        val weiboTailTag: String,
        val weiboContentTemplate: String,
        val douyinTailTag: String,
        val douyinContentTemplates: List<String>
    )

    private suspend fun fetchRemoteConfig(log: (String) -> Unit): RemoteConfig? {
        return try {
            withContext(Dispatchers.IO) {
                log("开始请求控制面板配置: ${SERVER_BASE_URL}/api/config")
                val request = okhttp3.Request.Builder()
                    .url("${SERVER_BASE_URL}/api/config")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log("❌ 拉取控制面板配置失败: HTTP ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string().orEmpty()
                    log("接口响应: ${body.take(200)}...")
                    if (body.isBlank()) {
                        log("❌ 控制面板配置响应为空")
                        return@withContext null
                    }
                    val json = org.json.JSONObject(body)
                    val weiboTail = json.optString("tailTag", "")
                    val weiboContent = json.optString("contentTemplate", "")
                    val douyinTail = json.optString("douyinTailTag", "")
                    
                    // 解析 douyinContentTemplates 数组
                    val douyinContents = mutableListOf<String>()
                    json.optJSONArray("douyinContentTemplates")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            douyinContents.add(arr.getString(i))
                        }
                    }
                    
                    if (douyinTail.isBlank() || douyinContents.isEmpty()) {
                        log("⚠️ 控制面板返回的抖音配置不完整")
                    } else {
                        log("✅ 已从控制面板获取配置，抖音文案数: ${douyinContents.size}")
                    }
                    RemoteConfig(weiboTail, weiboContent, douyinTail, douyinContents)
                }
            }
        } catch (e: Exception) {
            log("❌ 拉取控制面板配置异常: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            null
        }
    }

    suspend fun publish(context: DouyinContext) = with(context) {
        AutomationLog.startLongRunningAutomation()
        val jobId = pendingJobId?.also { pendingJobId = null }
            ?: "douyin-${System.currentTimeMillis()}"
        val templatesFromCmd = pendingContentTemplates.also { pendingContentTemplates = null }

        // 启动时优先从控制面板后端获取 tailTag；文案优先用立即发布指令里的列表
        val remoteConfig = fetchRemoteConfig(log)
        if (remoteConfig == null && templatesFromCmd.isNullOrEmpty()) {
            log("⚠️ 未能从控制面板获取抖音配置，终止流程")
            reportProgress(jobId, "job_fail", 0, 0, "", "未能获取抖音配置")
            return@with
        }

        if (remoteConfig != null && remoteConfig.douyinTailTag.isNotBlank()) {
            tailTag = remoteConfig.douyinTailTag
            log("已从控制面板更新抖音 tailTag：$tailTag")
        }

        val templates = templatesFromCmd?.takeIf { it.isNotEmpty() }
            ?: remoteConfig?.douyinContentTemplates.orEmpty()

        if (templates.isEmpty()) {
            log("❌ 抖音文案列表为空，终止流程")
            reportProgress(jobId, "job_fail", 0, 0, "", "文案列表为空")
            return@with
        }

        val totalCount = templates.size
        log("📢 抖音发布任务启动，共 $totalCount 条文案需要发布（jobId=$jobId）")
        reportProgress(jobId, "job_start", 0, totalCount, "", "任务启动，共 $totalCount 条")

        var successCount = 0
        var failCount = 0
        templates.forEachIndexed { index, contentTemplate ->
            if (AutomationLog.shouldStop()) {
                log("⚠️ 已停止，结束抖音发布任务")
                reportProgress(
                    jobId, "job_stopped", index, totalCount,
                    contentTemplate, "已停止（完成 $successCount 成功 / $failCount 失败）"
                )
                return@with
            }
            val current = index + 1
            log("\n========== 开始发布第 $current/$totalCount 条文案 ==========")
            log("文案内容: ${contentTemplate.take(50)}...")
            reportProgress(
                jobId, "item_start", current, totalCount,
                contentTemplate, "开始发布第 $current/$totalCount 条"
            )

            currentContentTemplate = contentTemplate
            val success = publishSingle(context)

            if (success) {
                successCount++
                log("✅ 第 $current/$totalCount 条文案发布成功")
                reportProgress(
                    jobId, "item_success", current, totalCount,
                    contentTemplate, "第 $current/$totalCount 条发布成功"
                )
            } else {
                failCount++
                log("❌ 第 $current/$totalCount 条文案发布失败")
                reportProgress(
                    jobId, "item_fail", current, totalCount,
                    contentTemplate, "第 $current/$totalCount 条发布失败"
                )
            }

            if (index < totalCount - 1) {
                log("⏳ 等待 30 秒后发布下一条文案…（日志浮窗可停止或音量加中断）")
                AutomationLog.waitUnlessStopped(30_000L)
            }
        }

        log("\n🎉 抖音发布任务完成，共 $totalCount 条（成功 $successCount / 失败 $failCount）")
        reportProgress(
            jobId, "job_done", totalCount, totalCount, "",
            "任务完成（成功 $successCount / 失败 $failCount）"
        )
    }

    private fun reportProgress(
        jobId: String,
        phase: String,
        current: Int,
        total: Int,
        content: String,
        message: String
    ) {
        runCatching {
            ControlPanelBridge.sendPublishProgress(
                jobId = jobId,
                platform = "douyin",
                phase = phase,
                current = current,
                total = total,
                contentPreview = content,
                message = message
            )
        }
    }
    
    // 当前正在发布的文案
    private var currentContentTemplate: String = ""
    
    /**
     * 执行单次抖音发布流程
     */
    private suspend fun publishSingle(context: DouyinContext): Boolean = with(context) {
        if (AutomationLog.shouldStop()) {
            log("⚠️ 已停止，跳过本条")
            return@with false
        }
        log("🚧 抖音自动化发布流程（阶段一：点击底部+号）")
        if (clickBottomAddButton()) {
            log("✅ 已点击底部加号按钮")
        } else {
            log("❌ 未能点击底部加号按钮，请确认抖音是否停留在首页")
            return@with false
        }

        delay(3000)
        log("🚧 抖音自动化发布流程（阶段二：点击文字按钮）")
        if (clickTextButton()) {
            log("✅ 已点击文字按钮")
        } else {
            log("❌ 未能点击文字按钮")
            return@with false
        }

        // 文案输入页面
        if (!fillDouyinTextContent()) {
            log("❌ 填充抖音文案失败")
            return@with false
        }

        delay(1000)
        log("🚧 抖音自动化发布流程（阶段三：点击第一个下一步）")
        if (clickNextButton()) {
            log("✅ 已点击第一个下一步按钮")
        } else {
            log("❌ 未能点击第一个下一步按钮")
            return@with false
        }

        log("⏳ 等待页面稳定，延时5秒...")
        AutomationLog.waitUnlessStopped(5_000L)
        if (AutomationLog.shouldStop()) {
            log("⚠️ 已停止，跳过本条")
            return@with false
        }

        log("🚧 抖音自动化发布流程（阶段3.5：随机点击话题分类按钮）")
        if (clickRandomTopicButton()) {
            log("✅ 已随机点击话题分类按钮")
            if (!waitTemplateLoadDone()) {
                log("❌ 模板加载未完成（超时或已停止），终止本条发布")
                return@with false
            }
        } else {
            log("⚠️ 未能点击话题分类按钮，继续流程")
        }

        log("⏳ 等待「下一步」可点击...")
        if (!waitNextButtonReady()) {
            log("❌ 「下一步」未就绪（可能仍在加载），终止本条发布")
            return@with false
        }

        log("🚧 抖音自动化发布流程（阶段4：点击第二个下一步）")
        if (clickNextButtonInFrame()) {
            log("✅ 已点击第二个下一步按钮")
        } else {
            log("❌ 未能点击第二个下一步按钮")
            return@with false
        }

        delay(1000)
        log("🚧 抖音自动化发布流程（阶段5：添加话题标签）")
        if (fillDouyinTopicTags()) {
            log("✅ 已添加话题标签")
        } else {
            log("❌ 未能添加话题标签")
            return@with false
        }

        delay(1000)
        log("🚧 抖音自动化发布流程（阶段6：点击发布按钮）")
        if (clickPublishButton()) {
            log("✅ 已点击发布按钮")
        } else {
            log("❌ 未能点击发布按钮")
            return@with false
        }

        delay(500)
        log("✅ 单条抖音发布流程完成！")
        return@with true
    }

    private suspend fun DouyinContext.clickBottomAddButton(): Boolean {
        val nodes = AssistsCore.findByTags("android.widget.FrameLayout")
        nodes.forEach { frame ->
            frame.findByTags("android.widget.ImageView").forEach { imageView ->
                val resId = imageView.viewIdResourceName.orEmpty()
                val desc = imageView.contentDescription?.toString().orEmpty()
                if (resId == "com.ss.android.ugc.aweme:id/0sq" &&
                    desc.contains("拍摄") &&
                    desc.contains("按钮")
                ) {
                    showNodeEffect(imageView, "抖音 + 号")
                    delay(100)
                    if (imageView.clickSelfOrParent()) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun findDouyinTextEdit(): AccessibilityNodeInfo? {
        // 优先根据 EditText + 文本 “分享你的想法” + id 兼容
        AssistsCore.findByTags("android.widget.EditText").forEach { edit ->
            val text = edit.text?.toString().orEmpty()
            val resId = edit.viewIdResourceName.orEmpty()
            if (text.contains("分享你的想法") || resId.endsWith(":id/m0d")) {
                return edit
            }
        }
        return null
    }

    private suspend fun DouyinContext.fillDouyinTextContent(): Boolean {
        val edit = findDouyinTextEdit() ?: run {
            log("❌ 未找到抖音文字输入框")
            return false
        }

        val bounds = edit.getBoundsInScreen()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()

        showClickEffect(cx, cy, "点击输入框获取焦点")
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(150)
        AssistsCore.gestureClick(cx, cy)
        delay(200)

        val target = currentContentTemplate

        // 方案1：setNodeText
        if (edit.refresh() && edit.setNodeText(target)) {
            log("✅ 文案填充成功（setNodeText）")
            return true
        }

        // 方案2：paste API
        if (edit.refresh()) {
            edit.paste(target)
            log("✅ 文案填充尝试（paste）")
            delay(200)
            return true
        }

        // 方案3：再次点击并长按粘贴（简单兜底）
        AssistsCore.gestureClick(cx, cy, duration = 120)
        delay(200)
        if (edit.refresh() && edit.setNodeText(target)) {
            log("✅ 文案填充成功（兜底 setNodeText）")
            return true
        }

        log("❌ 文案填充失败")
        return false
    }

    private suspend fun DouyinContext.clickTextButton(): Boolean {
        val button = findTextButton() ?: run {
            log("❌ 未找到文字按钮")
            return false
        }

        showClickEffect(button, "抖音 文字按钮")
        delay(300) // 稍等渲染稳定

        repeat(10) { round ->
            log("🔁 文字按钮点击重试: 第 ${round + 1} 轮")
            button.refresh()
            if (tryClickTextButtonOnce(button)) {
                delay(250)
                return true
            }

            // 额外偏移手势补点
            val b = button.getBoundsInScreen()
            val x = b.centerX().toFloat() + round * 2
            val y = b.centerY().toFloat() + round * 2
            showClickEffect(x, y, "额外偏移手势 (round ${round + 1})")
            delay(80)
            AssistsCore.gestureClick(x, y, duration = 90)
            delay(200)
        }

        log("❌ 所有点击方式多轮重试后仍未触发")
        return false
    }

    private fun findTextButton(): AccessibilityNodeInfo? {
        // 先按类型 Button 精确匹配
        AssistsCore.findByTags("android.widget.Button").forEach { button ->
            val resId = button.viewIdResourceName.orEmpty()
            val text = button.text?.toString().orEmpty()
            if (resId == "com.ss.android.ugc.aweme:id/1_7" && text == "文字") {
                return button
            }
        }
        // 回退：按文本找任意节点，容忍 class 变化
        AssistsCore.findByText("文字").forEach { node ->
            val resId = node.viewIdResourceName.orEmpty()
            if (resId == "com.ss.android.ugc.aweme:id/1_7") {
                return node
            }
        }
        return null
    }

    private suspend fun DouyinContext.tryClickTextButtonOnce(button: AccessibilityNodeInfo): Boolean {
        // 方式1: 按钮自身（参考 WeiboPublisher 的 clickSelfOrParent + 手势兜底）
        if (button.isClickable && button.click()) {
            log("✅ 方式1: 直接点击按钮成功")
            delay(200)
            return true
        }
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("✅ 方式1b: performAction CLICK 成功")
            delay(200)
            return true
        }
        if (button.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            log("ℹ️ 已请求焦点，再尝试点击")
            delay(120)
            if (button.isClickable && button.click()) {
                log("✅ 方式1c: 焦点后点击成功")
                delay(200)
                return true
            }
        }
        // 方式1d: clickSelfOrParent + 手势
        if (button.clickSelfOrParent()) {
            log("✅ 方式1d: clickSelfOrParent 成功")
            delay(200)
            return true
        }
        run {
            val b = button.getBoundsInScreen()
            val cx = b.centerX().toFloat()
            val cy = b.centerY().toFloat()
            showClickEffect(cx, cy, "手势兜底(Weibo策略)")
            delay(80)
            if (button.nodeGestureClick()) {
                log("✅ 方式1e: nodeGestureClick 按钮成功")
                delay(200)
                return true
            }
        }

        // 方式2: 可点击父节点
        button.findFirstParentClickable()?.let { parent ->
            log("尝试点击可点击的父节点: ${parent.className}")
            delay(80)
            if (parent.click()) {
                log("✅ 方式2: 通过点击父节点成功")
                delay(200)
                return true
            }
        }

        // 方式3: FrameLayout 容器及手势
        var parentFrame: AccessibilityNodeInfo? = button.parent
        while (parentFrame != null) {
            if (parentFrame.className?.contains("FrameLayout") == true) {
                log("找到父 FrameLayout: ${parentFrame.className}")
                if (parentFrame.isClickable && parentFrame.click()) {
                    log("✅ 方式3a: 点击 FrameLayout 成功")
                    delay(200)
                    return true
                }
                val frameBounds = parentFrame.getBoundsInScreen()
                val fx = frameBounds.centerX().toFloat()
                val fy = frameBounds.centerY().toFloat()
                showClickEffect(fx, fy, "手势点击 FrameLayout")
                delay(80)
                if (AssistsCore.gestureClick(fx, fy)) {
                    log("✅ 方式3b: 手势点击 FrameLayout 成功")
                    delay(200)
                    return true
                }
                if (parentFrame.nodeGestureClick()) {
                    log("✅ 方式3c: nodeGestureClick FrameLayout 成功")
                    delay(200)
                    return true
                }
                break
            }
            parentFrame = parentFrame.parent
        }

        // 方式4: 按钮中心多种手势（标准/加长/偏移/双击）
        val bounds = button.getBoundsInScreen()
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val points: List<Triple<Float, Float, Long>> = listOf(
            Triple(cx, cy, 60L),
            Triple(cx, cy, 120L),
            Triple(cx + 6f, cy + 6f, 60L),
            Triple(cx - 6f, cy - 4f, 60L),
            Triple(cx, cy, 200L) // 更长按压
        )
        points.forEachIndexed { idx, (x, y, duration) ->
            showClickEffect(x, y, "手势点击文字按钮#$idx")
            delay(80)
            if (AssistsCore.gestureClick(x, y, duration = duration)) {
                log("✅ 方式4.${idx + 1}: 手势点击成功 duration=$duration")
                delay(200)
                return true
            }
        }
        // 额外双击尝试（参考 WeiboPublisher performPointerDoubleTap 思路）
        run {
            showClickEffect(cx, cy, "双击尝试-1")
            delay(60)
            val first = AssistsCore.gestureClick(cx, cy, duration = 45)
            delay(120)
            val second = AssistsCore.gestureClick(cx, cy, duration = 45)
            if (first && second) {
                log("✅ 方式4.extra: 双击成功")
                delay(200)
                return true
            }
        }

        // 方式5: nodeGestureClick 按钮本身
        if (button.nodeGestureClick()) {
            log("✅ 方式5: nodeGestureClick 成功")
            delay(200)
            return true
        }

        // 方式6: 再尝试 performAction SELECT
        if (button.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
            log("✅ 方式6: performAction SELECT 成功")
            delay(200)
            return true
        }

        return false
    }
    
    private suspend fun DouyinContext.showClickEffect(node: AccessibilityNodeInfo, label: String = "") {
        showNodeEffect(node, label)
    }
    
    private suspend fun DouyinContext.showClickEffect(x: Float, y: Float, label: String = "") {
        showPointEffect(x, y, label)
    }

    private fun AccessibilityNodeInfo.clickSelfOrParent(): Boolean {
        if (isClickable && click()) return true
        return findParentClickable()?.click() == true
    }

    private fun AccessibilityNodeInfo.findParentClickable(): AccessibilityNodeInfo? {
        var parent = this.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * 点击"下一步"按钮（在 ViewGroup 下的 TextView）
     */
    private suspend fun DouyinContext.clickNextButton(): Boolean {
        val nextButton = findNextButton() ?: run {
            log("❌ 未找到下一步按钮")
            return false
        }

        showClickEffect(nextButton, "抖音 下一步按钮")
        delay(200)

        repeat(5) { round ->
            log("🔁 下一步按钮点击重试: 第 ${round + 1} 轮")
            nextButton.refresh()
            
            // 方式1: 直接点击 TextView
            if (nextButton.isClickable && nextButton.click()) {
                log("✅ 方式1: 直接点击TextView成功")
                delay(200)
                return true
            }
            
            // 方式2: 点击可点击的父节点
            nextButton.findFirstParentClickable()?.let { parent ->
                log("尝试点击可点击的父节点: ${parent.className}")
                delay(80)
                if (parent.click()) {
                    log("✅ 方式2: 通过点击父节点成功")
                    delay(200)
                    return true
                }
            }
            
            // 方式3: clickSelfOrParent
            if (nextButton.clickSelfOrParent()) {
                log("✅ 方式3: clickSelfOrParent 成功")
                delay(200)
                return true
            }
            
            // 方式4: 手势点击
            val bounds = nextButton.getBoundsInScreen()
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            showClickEffect(cx, cy, "手势点击下一步 (round ${round + 1})")
            delay(80)
            
            if (AssistsCore.gestureClick(cx, cy, duration = 80)) {
                log("✅ 方式4: 手势点击成功")
                delay(200)
                return true
            }
            
            // 方式5: nodeGestureClick
            if (nextButton.nodeGestureClick()) {
                log("✅ 方式5: nodeGestureClick 成功")
                delay(200)
                return true
            }
            
            delay(300)
        }

        log("❌ 所有点击方式多轮重试后仍未触发")
        return false
    }

    /**
     * 查找"下一步"按钮（ViewGroup 下的 TextView，text="下一步"）
     */
    private fun findNextButton(): AccessibilityNodeInfo? {
        // 遍历 ViewGroup，查找其子节点中 text="下一步" 的 TextView
        AssistsCore.findByTags("android.view.ViewGroup").forEach { viewGroup ->
            viewGroup.findByTags("android.widget.TextView").forEach { textView ->
                val text = textView.text?.toString().orEmpty()
                if (text == "下一步") {
                    return textView
                }
            }
        }
        
        // 回退方案：直接按文本查找 TextView
        AssistsCore.findByText("下一步").forEach { node ->
            if (node.className?.contains("TextView") == true) {
                return node
            }
        }
        
        return null
    }

    /** 是否存在可见的模板「加载中」遮罩 */
    private fun isTemplateLoadingVisible(): Boolean {
        return AssistsCore.findByText("加载中").any { node ->
            runCatching { node.isVisibleToUser }.getOrDefault(true)
        }
    }

    /**
     * 显式等待模板加载完成：
     * 1) 短暂等「加载中」出现（避免尚未弹出就误判就绪）
     * 2) 再轮询直至「加载中」消失，并二次确认防闪烁
     * 网络差时最长等 [TEMPLATE_LOAD_TIMEOUT_MS]。
     */
    private suspend fun DouyinContext.waitTemplateLoadDone(
        timeoutMs: Long = TEMPLATE_LOAD_TIMEOUT_MS
    ): Boolean {
        // 阶段1：给加载遮罩一点出现时间
        val appearDeadline = System.currentTimeMillis() + TEMPLATE_LOADING_APPEAR_WAIT_MS
        var sawLoading = false
        while (System.currentTimeMillis() < appearDeadline) {
            if (AutomationLog.shouldStop()) {
                log("⚠️ 等待模板加载时已停止")
                return false
            }
            if (isTemplateLoadingVisible()) {
                sawLoading = true
                break
            }
            AutomationLog.waitUnlessStopped(TEMPLATE_LOAD_POLL_MS)
        }

        // 阶段2：若已出现（或之后又出现），等到消失
        val deadline = System.currentTimeMillis() + timeoutMs
        var loggedWaiting = sawLoading
        if (sawLoading) {
            log("⏳ 检测到「加载中」，等待模板加载完成...")
        }
        while (System.currentTimeMillis() < deadline) {
            if (AutomationLog.shouldStop()) {
                log("⚠️ 等待模板加载时已停止")
                return false
            }
            if (isTemplateLoadingVisible()) {
                if (!loggedWaiting) {
                    log("⏳ 检测到「加载中」，等待模板加载完成...")
                    loggedWaiting = true
                }
                AutomationLog.waitUnlessStopped(TEMPLATE_LOAD_POLL_MS)
                continue
            }
            // 消失后稳定一小段，避免遮罩闪一下又出现
            AutomationLog.waitUnlessStopped(TEMPLATE_LOAD_SETTLE_MS)
            if (AutomationLog.shouldStop()) {
                log("⚠️ 等待模板加载时已停止")
                return false
            }
            if (!isTemplateLoadingVisible()) {
                if (loggedWaiting) {
                    log("✅ 模板加载完成（「加载中」已消失）")
                } else {
                    log("ℹ️ 未检测到「加载中」，判定模板已就绪")
                }
                return true
            }
        }
        log("❌ 等待模板加载超时（${timeoutMs}ms），「加载中」仍在")
        return false
    }

    /**
     * 等待第二个「下一步」可点：无加载遮罩且能找到可见的下一步按钮。
     */
    private suspend fun DouyinContext.waitNextButtonReady(
        timeoutMs: Long = NEXT_BUTTON_READY_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AutomationLog.shouldStop()) {
                log("⚠️ 等待「下一步」就绪时已停止")
                return false
            }
            if (isTemplateLoadingVisible()) {
                AutomationLog.waitUnlessStopped(TEMPLATE_LOAD_POLL_MS)
                continue
            }
            val nextButton = findNextButtonInFrame()
            val visible = nextButton != null &&
                runCatching { nextButton.isVisibleToUser }.getOrDefault(false)
            if (visible) {
                AutomationLog.waitUnlessStopped(TEMPLATE_LOAD_SETTLE_MS)
                if (AutomationLog.shouldStop()) return false
                if (!isTemplateLoadingVisible() && findNextButtonInFrame() != null) {
                    log("✅ 「下一步」已就绪")
                    return true
                }
            }
            AutomationLog.waitUnlessStopped(TEMPLATE_LOAD_POLL_MS)
        }
        log("❌ 等待「下一步」就绪超时（${timeoutMs}ms）")
        return false
    }

    /**
     * 随机点击 RecyclerView 下的话题分类按钮
     */
    private suspend fun DouyinContext.clickRandomTopicButton(): Boolean {
        val buttons = findTopicCategoryButtons()
        if (buttons.isEmpty()) {
            log("❌ 未找到话题分类按钮")
            return false
        }
        
        log("ℹ️ 找到 ${buttons.size} 个话题分类按钮")
        
        // 随机选择一个按钮
        val randomButton = buttons.random()
        val desc = randomButton.contentDescription?.toString() ?: "未知"
        log("🎲 随机选中话题：$desc")
        
        showClickEffect(randomButton, "话题分类: $desc")
        delay(200)
        
        repeat(3) { round ->
            log("🔁 话题分类按钮点击重试: 第 ${round + 1} 轮")
            randomButton.refresh()
            
            // 方式1: 直接点击 Button
            if (randomButton.isClickable && randomButton.click()) {
                log("✅ 方式1: 直接点击Button成功")
                delay(200)
                return true
            }
            
            // 方式2: 点击可点击的父节点
            randomButton.findFirstParentClickable()?.let { parent ->
                log("尝试点击可点击的父节点: ${parent.className}")
                delay(80)
                if (parent.click()) {
                    log("✅ 方式2: 通过点击父节点成功")
                    delay(200)
                    return true
                }
            }
            
            // 方式3: clickSelfOrParent
            if (randomButton.clickSelfOrParent()) {
                log("✅ 方式3: clickSelfOrParent 成功")
                delay(200)
                return true
            }
            
            // 方式4: 手势点击
            val bounds = randomButton.getBoundsInScreen()
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            showClickEffect(cx, cy, "手势点击话题按钮 (round ${round + 1})")
            delay(80)
            
            if (AssistsCore.gestureClick(cx, cy, duration = 80)) {
                log("✅ 方式4: 手势点击成功")
                delay(200)
                return true
            }
            
            // 方式5: nodeGestureClick
            if (randomButton.nodeGestureClick()) {
                log("✅ 方式5: nodeGestureClick 成功")
                delay(200)
                return true
            }
            
            delay(300)
        }
        
        log("⚠️ 所有点击方式多轮重试后仍未触发")
        return false
    }
    
    /**
     * 查找 RecyclerView 下的所有话题分类按钮(仅包含指定的话题类型)
     */
    private fun findTopicCategoryButtons(): List<AccessibilityNodeInfo> {
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        
        // 允许的话题类型列表
        val allowedTopics = setOf(
            "提问小卡",
            "简约纸条",
            "每日心情",
            "彩色心情",
            "暗调笔记",
            "互动对话",
            "灵感速记"
        )
        
        // 查找 RecyclerView
        AssistsCore.findByTags("androidx.recyclerview.widget.RecyclerView").forEach { recyclerView ->
            // 查找 RecyclerView 下的所有 Button
            recyclerView.findByTags("android.widget.Button").forEach { button ->
                // 确保 Button 有 content-desc
                val desc = button.contentDescription?.toString()
                if (!desc.isNullOrEmpty()) {
                    // 仅添加允许列表中的按钮
                    if (desc in allowedTopics) {
                        buttons.add(button)
                    }
                }
            }
        }
        
        return buttons
    }

    /**
     * 点击第二个"下一步"按钮（FrameLayout 下的 TextView）
     */
    private suspend fun DouyinContext.clickNextButtonInFrame(): Boolean {
        val nextButton = findNextButtonInFrame() ?: run {
            log("❌ 未找到 FrameLayout 下的下一步按钮")
            return false
        }

        showClickEffect(nextButton, "抖音 第二个下一步按钮")
        delay(200)

        repeat(5) { round ->
            log("🔁 第二个下一步按钮点击重试: 第 ${round + 1} 轮")
            if (AutomationLog.shouldStop()) return false

            // 重试过程中若再次出现加载遮罩，先等其消失再点
            if (isTemplateLoadingVisible()) {
                log("⏳ 点击前又出现「加载中」，先等待...")
                if (!waitTemplateLoadDone()) return false
            }

            nextButton.refresh()
            
            // 方式1: 直接点击 TextView
            if (nextButton.isClickable && nextButton.click()) {
                log("✅ 方式1: 直接点击TextView成功")
                delay(200)
                return true
            }
            
            // 方式2: 点击可点击的父节点
            nextButton.findFirstParentClickable()?.let { parent ->
                log("尝试点击可点击的父节点: ${parent.className}")
                delay(80)
                if (parent.click()) {
                    log("✅ 方式2: 通过点击父节点成功")
                    delay(200)
                    return true
                }
            }
            
            // 方式3: clickSelfOrParent
            if (nextButton.clickSelfOrParent()) {
                log("✅ 方式3: clickSelfOrParent 成功")
                delay(200)
                return true
            }
            
            // 方式4: 手势点击
            val bounds = nextButton.getBoundsInScreen()
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            showClickEffect(cx, cy, "手势点击第二个下一步 (round ${round + 1})")
            delay(80)
            
            if (AssistsCore.gestureClick(cx, cy, duration = 80)) {
                log("✅ 方式4: 手势点击成功")
                delay(200)
                return true
            }
            
            // 方式5: nodeGestureClick
            if (nextButton.nodeGestureClick()) {
                log("✅ 方式5: nodeGestureClick 成功")
                delay(200)
                return true
            }
            
            delay(300)
        }

        log("❌ 所有点击方式多轮重试后仍未触发")
        return false
    }

    /**
     * 查找 FrameLayout 下的"下一步"按钮
     */
    private fun findNextButtonInFrame(): AccessibilityNodeInfo? {
        // 遍历 FrameLayout，查找其子节点中 text="下一步" 的 TextView
        AssistsCore.findByTags("android.widget.FrameLayout").forEach { frameLayout ->
            frameLayout.findByTags("android.widget.TextView").forEach { textView ->
                val text = textView.text?.toString().orEmpty()
                if (text == "下一步") {
                    return textView
                }
            }
        }
        
        // 回退方案：直接按文本查找 TextView，验证其父节点是 FrameLayout
        AssistsCore.findByText("下一步").forEach { node ->
            if (node.className?.contains("TextView") == true) {
                var parent = node.parent
                while (parent != null) {
                    if (parent.className?.contains("FrameLayout") == true) {
                        return node
                    }
                    parent = parent.parent
                }
            }
        }
        
        return null
    }

    /**
     * 查找话题标签输入框（LinearLayout 下的 EditText，resourceId=com.ss.android.ugc.aweme:id/hud）
     */
    private fun findTopicTagEditText(): AccessibilityNodeInfo? {
        // 优先根据 resource-id 精确匹配
        AssistsCore.findByTags("android.widget.LinearLayout").forEach { linearLayout ->
            linearLayout.findByTags("android.widget.EditText").forEach { editText ->
                val resId = editText.viewIdResourceName.orEmpty()
                if (resId == "com.ss.android.ugc.aweme:id/hud") {
                    return editText
                }
            }
        }
        
        // 回退方案：直接查找所有 EditText，匹配 resource-id
        AssistsCore.findByTags("android.widget.EditText").forEach { editText ->
            val resId = editText.viewIdResourceName.orEmpty()
            if (resId == "com.ss.android.ugc.aweme:id/hud") {
                return editText
            }
        }
        
        return null
    }

    /**
     * 添加抖音话题标签（参考 WeiboPublisher 的粘贴方案）
     */
    private suspend fun DouyinContext.fillDouyinTopicTags(): Boolean {
        val editText = findTopicTagEditText() ?: run {
            log("❌ 未找到话题标签输入框")
            return false
        }

        log("步骤1: 点击输入框获得焦点")
        val bounds = editText.getBoundsInScreen()
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        
        showClickEffect(centerX, centerY, "点击话题标签输入框")
        if (editText.isClickable) {
            editText.click()
        } else {
            editText.findFirstParentClickable()?.click()
        }
        editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        AssistsCore.gestureClick(centerX, centerY)

        log("等待输入框获得焦点并稳定...")
        delay(800)

        log("步骤2: 记录粘贴前的输入框状态")
        val beforeText = if (editText.refresh()) editText.text?.toString() ?: "" else ""
        val beforeLength = beforeText.length
        log("粘贴前输入框文本长度: $beforeLength")

        // 话题标签输入框需要填充文案内容 + 标签
        val fullContent = "$currentContentTemplate $tailTag"
        log("准备填充话题标签: ${fullContent.take(50)}...")

        log("步骤3: 执行粘贴操作")
        var pasteAttempted = false
        
        // 方式1: 尝试 setNodeText
        log("方式1: 尝试 setNodeText")
        if (editText.refresh() && editText.setNodeText(fullContent)) {
            log("✅ setNodeText 执行成功")
            pasteAttempted = true
        } else {
            // 方式2: 尝试 paste API
            log("方式2: setNodeText 失败，尝试 paste API")
            if (editText.refresh()) {
                editText.paste(fullContent)
                log("✅ paste API 已执行")
                pasteAttempted = true
            } else {
                // 方式3: 尝试 ACTION_PASTE（先复制到剪贴板）
                log("方式3: paste API 失败，尝试 ACTION_PASTE")
                // 先将完整内容复制到剪贴板
                copyToClipboard(fullContent)
                delay(200)
                editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(300)
                val pasteSuccess = editText.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                log("ACTION_PASTE 执行结果: $pasteSuccess")
                if (pasteSuccess) {
                    pasteAttempted = true
                } else {
                    // 方式4: 点击粘贴按钮
                    log("方式4: ACTION_PASTE 失败，尝试点击粘贴按钮")
                    delay(300)
                    var pasteButtonClicked = false
                    AssistsCore.findByText("粘贴").forEach { pasteButton ->
                        if (pasteButton.isClickable) {
                            showClickEffect(pasteButton, "点击粘贴按钮")
                            delay(100)
                            if (pasteButton.click() || pasteButton.findFirstParentClickable()?.click() == true) {
                                pasteButtonClicked = true
                                pasteAttempted = true
                                return@forEach
                            }
                        }
                    }
                    if (!pasteButtonClicked) {
                        // 方式5: 坐标点击粘贴按钮
                        val bounds2 = editText.getBoundsInScreen()
                        val pasteX = bounds2.centerX().toFloat()
                        val pasteY = bounds2.bottom + 80f
                        showClickEffect(pasteX, pasteY, "坐标点击粘贴按钮")
                        delay(100)
                        if (AssistsCore.gestureClick(pasteX, pasteY)) {
                            pasteAttempted = true
                        }
                    }
                }
            }
        }

        if (pasteAttempted) {
            log("粘贴操作已执行，等待粘贴完成...")
        } else {
            log("⚠️ 所有粘贴方式都失败")
            return false
        }
        delay(800)

        // 验证是否成功
        if (editText.refresh()) {
            val afterText = editText.text?.toString() ?: ""
            val afterLength = afterText.length
            log("粘贴后输入框文本长度: $afterLength")
            log("粘贴后输入框内容: ${afterText.take(100)}...")
            if (afterLength > beforeLength || afterText.contains(tailTag)) {
                log("✅ 话题标签添加成功")
                return true
            }
        }
        
        log("⚠️ 话题标签添加可能失败，但将继续流程")
        return true
    }

    /**
     * 复制内容到剪贴板
     */
    private fun copyToClipboard(text: String) {
        val service = com.ven.assists.service.AssistsService.instance ?: return
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("douyin_content", text))
    }

    /**
     * 查找"发布"按钮（TextView，text="发布"）
     */
    private fun findPublishButton(): AccessibilityNodeInfo? {
        // 优先根据 resource-id 精确匹配
        AssistsCore.findByTags("android.widget.TextView").forEach { textView ->
            val text = textView.text?.toString().orEmpty()
            val resId = textView.viewIdResourceName.orEmpty()
            if (text == "发布" && resId == "com.ss.android.ugc.aweme:id/34o") {
                return textView
            }
        }
        
        // 回退方案：仅按 text 匹配
        AssistsCore.findByText("发布").forEach { textView ->
            if (textView.className?.contains("TextView") == true) {
                return textView
            }
        }
        
        return null
    }

    /**
     * 点击"发布"按钮
     */
    private suspend fun DouyinContext.clickPublishButton(): Boolean {
        val publishButton = findPublishButton() ?: run {
            log("❌ 未找到发布按钮")
            return false
        }

        showClickEffect(publishButton, "抖音 发布按钮")
        delay(200)

        repeat(5) { round ->
            log("🔁 发布按钮点击重试: 第 ${round + 1} 轮")
            publishButton.refresh()
            
            // 方式1: 直接点击 TextView
            if (publishButton.isClickable && publishButton.click()) {
                log("✅ 方式1: 直接点击TextView成功")
                delay(200)
                return true
            }
            
            // 方式2: 点击可点击的父节点
            publishButton.findFirstParentClickable()?.let { parent ->
                log("尝试点击可点击的父节点: ${parent.className}")
                delay(80)
                if (parent.click()) {
                    log("✅ 方式2: 通过点击父节点成功")
                    delay(200)
                    return true
                }
            }
            
            // 方式3: clickSelfOrParent
            if (publishButton.clickSelfOrParent()) {
                log("✅ 方式3: clickSelfOrParent 成功")
                delay(200)
                return true
            }
            
            // 方式4: 手势点击
            val bounds = publishButton.getBoundsInScreen()
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            showClickEffect(cx, cy, "手势点击发布按钮 (round ${round + 1})")
            delay(80)
            
            if (AssistsCore.gestureClick(cx, cy, duration = 80)) {
                log("✅ 方式4: 手势点击成功")
                delay(200)
                return true
            }
            
            // 方式5: nodeGestureClick
            if (publishButton.nodeGestureClick()) {
                log("✅ 方式5: nodeGestureClick 成功")
                delay(200)
                return true
            }
            
            delay(300)
        }

        log("❌ 所有点击方式多轮重试后仍未触发")
        return false
    }
}

