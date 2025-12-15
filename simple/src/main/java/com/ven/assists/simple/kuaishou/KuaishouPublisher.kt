package com.ven.assists.simple.kuaishou

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
import com.ven.assists.simple.weibo.WeiboPublisher
import kotlinx.coroutines.delay

/**
 * 复用微博发布的上下文，后续如果需要独立能力再拆分。
 */
typealias KuaishouContext = WeiboPublisher.Context

/**
 * 快手自动化发布流程（预留架子，后续逐步补全）
 */
object KuaishouPublisher {
    /**
     * 快手文案模板，可在外部进行配置
     */
    var contentTemplate: String = "零基础学前端怕坚持不下来、被拖延症耽误？9年前端带徒，我来监督！从 HTML、CSS入门，逐步攻克 JS、Vue、React，最后练小程序/Uniapp项目。正向反馈看得见进步。日常督促打卡，想有人盯着学，跟我一起搞定前端！#前端 #前端教学"
        @Synchronized get
        @Synchronized set

    // 控制面板配置接口
    private const val CONTROL_PANEL_BASE_URL = "http://192.168.50.192:4001"
    private val httpClient = okhttp3.OkHttpClient()

    data class RemoteConfig(
        val weiboTailTag: String,
        val weiboContentTemplate: String,
        val douyinTailTag: String,
        val douyinContentTemplate: String,
        val kuaishouContentTemplate: String
    )

    private fun fetchRemoteConfig(log: (String) -> Unit): RemoteConfig? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$CONTROL_PANEL_BASE_URL/api/config")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log("❌ 拉取控制面板配置失败: HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    log("❌ 控制面板配置响应为空")
                    return null
                }
                val json = org.json.JSONObject(body)
                val weiboTail = json.optString("tailTag", "")
                val weiboContent = json.optString("contentTemplate", "")
                val douyinTail = json.optString("douyinTailTag", "")
                val douyinContent = json.optString("douyinContentTemplate", "")
                val kuaishouContent = json.optString("kuaishouContentTemplate", "")
                log("✅ 已从控制面板获取配置")
                RemoteConfig(weiboTail, weiboContent, douyinTail, douyinContent, kuaishouContent)
            }
        } catch (e: Exception) {
            log("❌ 拉取控制面板配置异常: ${e.message}")
            null
        }
    }

    suspend fun publish(context: KuaishouContext) = with(context) {
        // 启动时优先从控制面板后端获取 contentTemplate
        fetchRemoteConfig(log)?.let { cfg ->
            if (cfg.kuaishouContentTemplate.isNotBlank()) {
                contentTemplate = cfg.kuaishouContentTemplate
                log("已从控制面板更新快手 contentTemplate")
            }
        } ?: log("⚠️ 未能从控制面板获取快手配置，使用当前本地配置")
        
        log("🚧 快手自动化发布流程（阶段一：点击底部加号）")
        if (clickBottomAddButton()) {
            log("✅ 已点击底部加号按钮")
        } else {
            log("❌ 未能点击底部加号按钮，请确认快手是否停留在首页")
            return@with
        }
        
        log("⏳ 等待页面响应，延时3秒...")
        delay(3000)
        
        log("🚧 快手自动化发布流程（阶段二：点击文字按钮）")
        if (clickTextButton()) {
            log("✅ 已点击文字按钮")
        } else {
            log("❌ 未能点击文字按钮")
            return@with
        }
        
        // 阶段三 - 填写文案内容
        log("⏳ 等待页面加载，延时3秒...")
        delay(3000)
        
        log("🚧 快手自动化发布流程（阶段三：填写文案内容）")
        if (fillKuaishouTextContent()) {
            log("✅ 已填充文案内容")
        } else {
            log("❌ 填充文案内容失败")
            return@with
        }
        
        log("⏳ 等待内容保存，延时3秒...")
        delay(3000)
        
        log("🚧 快手自动化发布流程（阶段四：点击下一步）")
        if (clickNextStepButton()) {
            log("✅ 已点击下一步按钮")
        } else {
            log("❌ 未能点击下一步按钮")
            return@with
        }
        
        log("⏳ 等待页面稳定，延时3秒...")
        delay(5000)
        
        log("🚧 快手自动化发布流程（阶段五：点击发布按钮）")
        if (clickPublishButton()) {
            log("✅ 已点击发布按钮")
        } else {
            log("❌ 未能点击发布按钮")
            return@with
        }
        
        delay(500)
        log("✅ 快手发布流程完成！")
        
        log("✅ 快手发布流程架子搭建完成，等待后续调试补充")
    }

    /**
     * 点击快手底部中间加号按钮（ViewGroup，content-desc="拍摄"）
     */
    private suspend fun KuaishouContext.clickBottomAddButton(): Boolean {
        val nodes = AssistsCore.findByTags("android.view.ViewGroup")
        nodes.forEach { viewGroup ->
            val desc = viewGroup.contentDescription?.toString().orEmpty()
            if (desc.contains("拍摄")) {
                showNodeEffect(viewGroup, "快手 + 号")
                delay(100)
                
                // 多种点击策略
                repeat(3) { round ->
                    log("🔁 底部加号按钮点击重试: 第 ${round + 1} 轮")
                    viewGroup.refresh()
                    
                    // 方式1: 直接点击 ViewGroup
                    if (viewGroup.isClickable && viewGroup.click()) {
                        log("✅ 方式1: 直接点击ViewGroup成功")
                        delay(200)
                        return true
                    }
                    
                    // 方式2: clickSelfOrParent
                    if (viewGroup.clickSelfOrParent()) {
                        log("✅ 方式2: clickSelfOrParent 成功")
                        delay(200)
                        return true
                    }
                    
                    // 方式3: 手势点击中心
                    val bounds = viewGroup.getBoundsInScreen()
                    val cx = bounds.centerX().toFloat()
                    val cy = bounds.centerY().toFloat()
                    showClickEffect(cx, cy, "手势点击加号 (round ${round + 1})")
                    delay(80)
                    
                    if (AssistsCore.gestureClick(cx, cy, duration = 80)) {
                        log("✅ 方式3: 手势点击成功")
                        delay(200)
                        return true
                    }
                    
                    // 方式4: nodeGestureClick
                    if (viewGroup.nodeGestureClick()) {
                        log("✅ 方式4: nodeGestureClick 成功")
                        delay(200)
                        return true
                    }
                    
                    delay(300)
                }
            }
        }
        return false
    }

    /**
     * 查找“文字”按钮（FrameLayout 下的 TextView，text="文字"）
     */
    private fun findTextButton(): AccessibilityNodeInfo? {
        // 遍历 FrameLayout，查找其子节点中 text="文字" 的 TextView
        AssistsCore.findByTags("android.widget.FrameLayout").forEach { frameLayout ->
            frameLayout.findByTags("android.widget.TextView").forEach { textView ->
                val text = textView.text?.toString().orEmpty()
                if (text == "文字") {
                    return textView
                }
            }
        }
        
        // 回退方案：直接按文本查找 TextView
        AssistsCore.findByText("文字").forEach { node ->
            if (node.className?.contains("TextView") == true) {
                return node
            }
        }
        
        return null
    }

    /**
     * 点击"文字"按钮（参考 DouyinPublisher 的完整点击策略）
     */
    private suspend fun KuaishouContext.clickTextButton(): Boolean {
        val button = findTextButton() ?: run {
            log("❌ 未找到文字按钮")
            return false
        }

        showClickEffect(button, "快手 文字按钮")
        delay(300)

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

    /**
     * 尝试一次点击文字按钮（参考抖音的完整策略）
     */
    private suspend fun KuaishouContext.tryClickTextButtonOnce(button: AccessibilityNodeInfo): Boolean {
        // 方式1: 按钮自身多种尝试
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
            showClickEffect(cx, cy, "手势兜底")
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
            Triple(cx, cy, 200L)
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
        // 额外双击尝试
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

    /**
     * 查找快手文案输入框（RelativeLayout 下的 EditText，text="写下你的心情"，resourceId="com.smile.gifmakerid/story_text"）
     */
    private fun findKuaishouTextEdit(): AccessibilityNodeInfo? {
        // 优先根据 resource-id 精确匹配
        AssistsCore.findByTags("android.widget.RelativeLayout").forEach { relativeLayout ->
            relativeLayout.findByTags("android.widget.EditText").forEach { editText ->
                val resId = editText.viewIdResourceName.orEmpty()
                val text = editText.text?.toString().orEmpty()
                if (resId == "com.smile.gifmakerid/story_text" || text.contains("写下你的心情")) {
                    return editText
                }
            }
        }
        
        // 回退方案：直接查找所有 EditText，匹配 resource-id 或 text
        AssistsCore.findByTags("android.widget.EditText").forEach { editText ->
            val resId = editText.viewIdResourceName.orEmpty()
            val text = editText.text?.toString().orEmpty()
            if (resId == "com.smile.gifmakerid/story_text" || text.contains("写下你的心情")) {
                return editText
            }
        }
        
        return null
    }

    /**
     * 填充快手文案内容（参考 DouyinPublisher 的填充策略）
     */
    private suspend fun KuaishouContext.fillKuaishouTextContent(): Boolean {
        val edit = findKuaishouTextEdit() ?: run {
            log("❌ 未找到快手文字输入框")
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

        val target = contentTemplate

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

    /**
     * 查找"下一步"按钮（RelativeLayout 下的 TextView，text="下一步"，resourceId="com.smile.gifmaker:id/next_step"）
     */
    private fun findNextStepButton(): AccessibilityNodeInfo? {
        // 优先根据 resource-id 精确匹配
        AssistsCore.findByTags("android.widget.RelativeLayout").forEach { relativeLayout ->
            relativeLayout.findByTags("android.widget.TextView").forEach { textView ->
                val resId = textView.viewIdResourceName.orEmpty()
                val text = textView.text?.toString().orEmpty()
                if (resId == "com.smile.gifmaker:id/next_step" && text == "下一步") {
                    return textView
                }
            }
        }
        
        // 回退方案：直接查找所有 TextView，匹配 resource-id 或 text
        AssistsCore.findByTags("android.widget.TextView").forEach { textView ->
            val resId = textView.viewIdResourceName.orEmpty()
            val text = textView.text?.toString().orEmpty()
            if (resId == "com.smile.gifmaker:id/next_step" && text == "下一步") {
                return textView
            }
        }
        
        // 再次回退：仅按 text 匹配
        AssistsCore.findByText("下一步").forEach { node ->
            if (node.className?.contains("TextView") == true) {
                return node
            }
        }
        
        return null
    }

    /**
     * 点击"下一步"按钮（参考 DouyinPublisher 的点击策略）
     */
    private suspend fun KuaishouContext.clickNextStepButton(): Boolean {
        val nextButton = findNextStepButton() ?: run {
            log("❌ 未找到下一步按钮")
            return false
        }

        showClickEffect(nextButton, "快手 下一步按钮")
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
     * 查找"发布"按钮（FrameLayout 下的 TextView，text="发布"，resourceId="com.smile.gifmaker:id/next_step_button_text"）
     */
    private fun findPublishButton(): AccessibilityNodeInfo? {
        // 优先根据 resource-id 精确匹配
        AssistsCore.findByTags("android.widget.FrameLayout").forEach { frameLayout ->
            frameLayout.findByTags("android.widget.TextView").forEach { textView ->
                val resId = textView.viewIdResourceName.orEmpty()
                val text = textView.text?.toString().orEmpty()
                if (resId == "com.smile.gifmaker:id/next_step_button_text" && text == "发布") {
                    return textView
                }
            }
        }
        
        // 回退方案：直接查找所有 TextView，匹配 resource-id 或 text
        AssistsCore.findByTags("android.widget.TextView").forEach { textView ->
            val resId = textView.viewIdResourceName.orEmpty()
            val text = textView.text?.toString().orEmpty()
            if (resId == "com.smile.gifmaker:id/next_step_button_text" && text == "发布") {
                return textView
            }
        }
        
        // 再次回退：仅按 text 匹配
        AssistsCore.findByText("发布").forEach { node ->
            if (node.className?.contains("TextView") == true) {
                return node
            }
        }
        
        return null
    }

    /**
     * 点击"发布"按钮（参考 DouyinPublisher 的点击策略）
     */
    private suspend fun KuaishouContext.clickPublishButton(): Boolean {
        val publishButton = findPublishButton() ?: run {
            log("❌ 未找到发布按钮")
            return false
        }

        showClickEffect(publishButton, "快手 发布按钮")
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

    /**
     * 辅助方法：点击节点或其可点击父节点
     */
    private fun AccessibilityNodeInfo.clickSelfOrParent(): Boolean {
        if (isClickable && click()) return true
        return findParentClickable()?.click() == true
    }

    /**
     * 辅助方法：查找可点击的父节点
     */
    private fun AccessibilityNodeInfo.findParentClickable(): AccessibilityNodeInfo? {
        var parent = this.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * 辅助方法：显示节点点击特效
     */
    private suspend fun KuaishouContext.showClickEffect(node: AccessibilityNodeInfo, label: String = "") {
        showNodeEffect(node, label)
    }
    
    /**
     * 辅助方法：显示坐标点击特效
     */
    private suspend fun KuaishouContext.showClickEffect(x: Float, y: Float, label: String = "") {
        showPointEffect(x, y, label)
    }
}
