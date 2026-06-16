package com.ven.assists.simple.xianyu

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.blankj.utilcode.util.ScreenUtils
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.back
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.findByTags
import com.ven.assists.AssistsCore.findByText
import com.ven.assists.AssistsCore.findFirstParentClickable
import com.ven.assists.AssistsCore.getBoundsInScreen
import com.ven.assists.AssistsCore.getAllNodes
import com.ven.assists.AssistsCore.nodeGestureClick
import com.ven.assists.mp.MPManager
import com.ven.assists.service.AssistsService
import com.ven.assists.simple.AutomationLog
import com.ven.assists.simple.common.LogWrapper
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.stepper.StepManager
import com.ven.assists.web.utils.TextRecognitionChineseLocator
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.text.Normalizer

/**
 * 闲鱼擦亮：与 [XianyuBatchRunner] 相同枚举本机名称/包名含「闲鱼」的应用，**每个包各跑一遍**：
 * 启动 → 点「我的」→ 点 **content-desc** 以「我发布的」开头的 `ImageView` → 点「一键擦亮」（OCR 优先，固定坐标兜底）→ **返回**。
 */
object XianyuPolishRunner {

    private const val FILTER_AT_COLLECT = true
    private const val TAB_TITLE_ID = "com.taobao.idlefish:id/tab_title"
    private const val MY_TAB_TEXT = "我的"
    /** 底部「我的」比例兜底（与 uiautomator 底栏靠右区域一致，随 ROM 可再调） */
    private const val MY_TAB_REL_X = 0.92f
    private const val MY_TAB_REL_Y = 0.975f
    /** uiautomatorviewer 测得「一键擦亮」屏幕坐标（策略②兜底） */
    private const val POLISH_TAP_ABS_X = 245f
    private const val POLISH_TAP_ABS_Y = 750f
    /** 固定坐标容错：像素级微移 */
    private const val POLISH_TAP_JITTER_PX = 14f

    private const val STABILITY_WAIT_MS = 1_200L
    private const val PUBLISHED_ENTRY_WAIT_MAX_MS = 12_000L
    private const val POST_PUBLISHED_SETTLE_MS = 1_200L
    private const val OVERLAY_HIDDEN_DELAY_MS = 250L
    /** OCR 只扫上半屏，减少列表误匹配 */
    private const val OCR_REGION_HEIGHT_RATIO = 0.45f
    private const val CLICK_RETRY = 2
    private const val PER_STRATEGY_SLICE_MS = 1_100L

    private const val PUBLISHED_DESC_PREFIX = "我发布的"
    private val POLISH_OCR_KEYWORDS = listOf("一键擦亮", "全部擦亮", "擦亮")

    @Volatile
    private var stopRequested: Boolean = false

    fun requestStop() {
        stopRequested = true
    }

    private fun shouldAbort(): Boolean = stopRequested || StepManager.isStop

    fun createLogOnlyContext(): WeiboPublisher.Context {
        return WeiboPublisher.Context(
            log = { message -> LogWrapper.logAppend("[闲鱼擦亮] $message") },
            showNodeEffect = { _, _ -> },
            showPointEffect = { _, _, _ -> }
        )
    }

    suspend fun run(context: WeiboPublisher.Context) = with(context) {
        stopRequested = false
        AutomationLog.startLongRunningAutomation()
        val apps = XianyuInstalledApps.collectXianyuApps(this@with)
        if (apps.isEmpty()) {
            log("❌ 未找到名称或包名匹配「闲鱼」的应用。")
            XianyuInstalledApps.logCollectDiagnostics(this@with)
            log("提示：若已装闲鱼仍为空，请到系统设置中查看是否限制本应用「读取应用列表」。")
            return@with
        }
        log("发现匹配应用数量(过滤黑名单后): ${apps.size}")
        for ((index, item) in apps.withIndex()) {
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束任务。")
                return@with
            }
            if (!FILTER_AT_COLLECT && XianyuInstalledApps.isInBlacklist(item.label, item.packageName)) {
                log("跳过(黑名单): ${item.label} (${item.packageName})")
                continue
            }
            log("======== 擦亮 进度 ${index + 1}/${apps.size} | ${item.label} | ${item.packageName} ========")
            if (!XianyuInstalledApps.launchXianyuApp(this@with, item, shouldAbort = { shouldAbort() })) {
                log("⚠️ 启动或进入前台超时，跳过: ${item.packageName}")
                continue
            }
            AutomationLog.waitUnlessStopped(STABILITY_WAIT_MS)
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束。")
                return@with
            }
            if (!openMyTab(this@with)) {
                log("⚠️ [${item.packageName}] 未能进入「我的」页，跳过。")
                continue
            }
            if (!clickPublishedEntry(this@with)) {
                log("⚠️ [${item.packageName}] 未找到或未点到「我发布的」入口，跳过。")
                continue
            }
            if (!shouldAbort()) {
                log("✅ [${item.packageName}] 已点击「我发布的」入口。")
            }
            AutomationLog.waitUnlessStopped(POST_PUBLISHED_SETTLE_MS)
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束。")
                return@with
            }
            val polishTapOk = clickPolishButton(this@with)
            if (!polishTapOk) {
                log("⚠️ [${item.packageName}] OCR 与固定坐标均未点到「一键擦亮」，不执行返回。")
                continue
            }
            if (!shouldAbort()) {
                log("✅ [${item.packageName}] 已点击「一键擦亮」。")
            }
            AutomationLog.waitUnlessStopped(320L)
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束。")
                return@with
            }
            if (back()) {
                log("✅ [${item.packageName}] 已调用返回上一页。")
            } else {
                log("⚠️ [${item.packageName}] 返回上一页 (GLOBAL_ACTION_BACK) 未成功。")
            }
            if (index < apps.lastIndex) {
                yield()
            }
        }
        if (!shouldAbort()) {
            log("🎉 闲鱼擦亮任务全部处理完成。")
        }
    }

    private suspend fun openMyTab(ctx: WeiboPublisher.Context): Boolean {
        repeat(CLICK_RETRY) { attempt ->
            if (shouldAbort()) return false
            ctx.log("──────── 进入「我的」第 ${attempt + 1}/$CLICK_RETRY 轮 ────────")
            val tab = findIdlefishMyTabNode()
            if (tab != null) {
                ctx.log("策略① tab_title「我的」")
                if (clickMyTabQuick(ctx, tab) && waitPublishedEntryVisible(ctx, PER_STRATEGY_SLICE_MS)) return true
            } else {
                ctx.log("策略① 跳过：未找到「我的」Tab 节点")
            }
            ctx.log("策略② 底部「我的」比例点")
            if (clickMyTabRatio(ctx) && waitPublishedEntryVisible(ctx, PER_STRATEGY_SLICE_MS)) return true
            yield()
        }
        ctx.log("⚠️ 多轮尝试后仍未出现「我发布的」相关入口")
        return false
    }

    private fun findIdlefishMyTabNode(): AccessibilityNodeInfo? {
        for (node in collectNodesFromAllAccessibilityWindows()) {
            if (node.viewIdResourceName != TAB_TITLE_ID) continue
            if (node.text?.toString() != MY_TAB_TEXT) continue
            return node
        }
        findByTags(
            "android.widget.TextView",
            viewId = TAB_TITLE_ID,
            text = MY_TAB_TEXT
        ).firstOrNull()?.let { return it }
        findByText(MY_TAB_TEXT).firstOrNull { it.viewIdResourceName == TAB_TITLE_ID }?.let { return it }
        return null
    }

    private suspend fun clickMyTabQuick(ctx: WeiboPublisher.Context, tab: AccessibilityNodeInfo): Boolean {
        ctx.showNodeEffect(tab, "闲鱼 我的Tab")
        yield()
        repeat(2) { round ->
            ctx.log("「我的」快捷点击 ${round + 1}/2")
            tab.refresh()
            if (tab.isClickable && tab.click()) return true
            tab.findFirstParentClickable()?.let { parent -> if (parent.click()) return true }
            if (tab.clickSelfOrParent()) return true
            val b = tab.getBoundsInScreen()
            if (AssistsCore.gestureClick(b.centerX().toFloat(), b.centerY().toFloat(), duration = 65)) return true
            if (tab.nodeGestureClick()) return true
            yield()
        }
        return false
    }

    private fun AccessibilityNodeInfo.clickSelfOrParent(): Boolean {
        if (isClickable && click()) return true
        var p = parent
        while (p != null) {
            if (p.isClickable && p.click()) return true
            p = p.parent
        }
        return false
    }

    /** 策略① OCR → 策略② 固定坐标 (245,750) */
    private suspend fun clickPolishButton(ctx: WeiboPublisher.Context): Boolean {
        repeat(CLICK_RETRY) { attempt ->
            if (shouldAbort()) return false
            ctx.log("──────── 点击「一键擦亮」第 ${attempt + 1}/$CLICK_RETRY 轮 ────────")
            ctx.log("策略① OCR 识别")
            if (clickPolishViaOcr(ctx)) return true
            ctx.log("策略② 固定坐标 (${POLISH_TAP_ABS_X.toInt()},${POLISH_TAP_ABS_Y.toInt()}) 容错点击")
            if (clickPolishTargetAbsWithRedundancy(ctx)) return true
            AutomationLog.waitUnlessStopped(800L)
        }
        return false
    }

    private suspend fun clickPolishViaOcr(ctx: WeiboPublisher.Context): Boolean {
        for (keyword in POLISH_OCR_KEYWORDS) {
            if (shouldAbort()) return false
            val position = ocrFindPolishPosition(ctx, keyword) ?: continue
            val cx = (position.left + position.right) / 2f
            val cy = (position.top + position.bottom) / 2f
            ctx.log("OCR 命中「$keyword」中心 ($cx,$cy) 框=[${position.left},${position.top},${position.right},${position.bottom}]")
            ctx.showPointEffect(cx, cy, "OCR擦亮")
            if (AssistsCore.gestureClick(cx, cy, duration = 65)) return true
        }
        ctx.log("OCR 未识别到「一键擦亮」相关文字")
        return false
    }

    private suspend fun ocrFindPolishPosition(
        ctx: WeiboPublisher.Context,
        targetText: String
    ): TextRecognitionChineseLocator.WordPosition? {
        AssistsWindowManager.hideAll()
        delay(OVERLAY_HIDDEN_DELAY_MS)
        return try {
            val w = ScreenUtils.getScreenWidth()
            val h = ScreenUtils.getScreenHeight()
            val region = Rect(0, 0, w, (h * OCR_REGION_HEIGHT_RATIO).toInt().coerceAtLeast(1))
            val recognitionResult = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    TextRecognitionChineseLocator.findWordPositionsInScreenshotRegion(
                        region = region,
                        targetText = targetText
                    )
                } else {
                    val bitmap = MPManager.takeScreenshot2Bitmap()
                        ?: throw IllegalStateException("MediaProjection 截图失败")
                    try {
                        TextRecognitionChineseLocator.findWordPositionsInRegion(
                            bitmap = bitmap,
                            region = region,
                            targetText = targetText
                        )
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }
            val recognition = recognitionResult.getOrNull()
            if (recognition == null) {
                ctx.log("OCR 异常: ${recognitionResult.exceptionOrNull()?.message}")
                null
            } else {
                ctx.log("OCR「$targetText」耗时 ${recognition.processingTimeMillis}ms，匹配 ${recognition.targetPositions.size} 处")
                pickBestPolishOcrPosition(recognition.targetPositions)
            }
        } finally {
            AssistsWindowManager.showTop()
        }
    }

    /** 多候选时优先最靠上、面积较小的框（通常是顶部操作按钮） */
    private fun pickBestPolishOcrPosition(
        positions: List<TextRecognitionChineseLocator.WordPosition>
    ): TextRecognitionChineseLocator.WordPosition? {
        if (positions.isEmpty()) return null
        return positions.minWith(compareBy({ it.top }, { it.width * it.height }))
    }

    /**
     * 在 uiautomatorviewer 坐标 (245,750) 附近做多点容错点击。
     */
    private suspend fun clickPolishTargetAbsWithRedundancy(ctx: WeiboPublisher.Context): Boolean {
        val bx = POLISH_TAP_ABS_X
        val by = POLISH_TAP_ABS_Y
        val j = POLISH_TAP_JITTER_PX
        val points = listOf(
            bx to by,
            bx - j to by,
            bx + j to by,
            bx to by - j,
            bx to by + j,
            bx - j to by - j,
            bx + j to by + j,
        )
        var anyOk = false
        for ((index, pair) in points.withIndex()) {
            if (shouldAbort()) return anyOk
            val (px, py) = pair
            ctx.log("固定坐标尝试 ${index + 1}/${points.size} ($px,$py)")
            ctx.showPointEffect(px, py, "擦亮")
            val duration = when (index % 3) {
                0 -> 55L
                1 -> 65L
                else -> 45L
            }
            if (AssistsCore.gestureClick(px, py, duration = duration)) anyOk = true
            AutomationLog.waitUnlessStopped(260L)
        }
        if (!shouldAbort()) {
            ctx.log("固定坐标中心双击补点")
            ctx.showPointEffect(bx, by, "擦亮中心")
            if (AssistsCore.gestureClick(bx, by, duration = 45)) anyOk = true
            delay(90)
            if (AssistsCore.gestureClick(bx, by, duration = 45)) anyOk = true
        }
        return anyOk
    }

    private suspend fun clickMyTabRatio(ctx: WeiboPublisher.Context): Boolean {
        val w = ScreenUtils.getScreenWidth().toFloat()
        val h = ScreenUtils.getScreenHeight().toFloat()
        val x = w * MY_TAB_REL_X
        val y = h * MY_TAB_REL_Y
        ctx.log("底部比例点 ($MY_TAB_REL_X, $MY_TAB_REL_Y)")
        ctx.showPointEffect(x, y, "闲鱼底栏我的")
        return AssistsCore.gestureClick(x, y, duration = 55)
    }

    private fun normalizeDesc(raw: CharSequence?): String {
        if (raw == null) return ""
        return Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /** content-desc 以「我发布的」开头即可（后面跟空格与数字等，不做整串强匹配） */
    private fun matchesPublishedContentDesc(raw: CharSequence?): Boolean {
        val s = normalizeDesc(raw)
        return s.startsWith(PUBLISHED_DESC_PREFIX)
    }

    /** 当前前台闲鱼分身包名可能与官方不同，允许前台包或 [XianyuInstalledApps.PACKAGE_MATCH_HINTS] 命中 */
    private fun nodePackageAcceptableForCurrentFish(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val fg = AssistsCore.getPackageName().orEmpty()
        if (fg.isNotEmpty() && pkg == fg) return true
        if (pkg.contains("idlefish", ignoreCase = true)) return true
        return XianyuInstalledApps.PACKAGE_MATCH_HINTS.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun findPublishedImageNode(): AccessibilityNodeInfo? {
        for (node in collectNodesFromAllAccessibilityWindows()) {
            val pkg = node.packageName?.toString().orEmpty()
            if (!nodePackageAcceptableForCurrentFish(pkg)) continue
            if (node.className?.toString() != "android.widget.ImageView") continue
            if (!matchesPublishedContentDesc(node.contentDescription)) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            return node
        }
        return null
    }

    private suspend fun waitPublishedEntryVisible(ctx: WeiboPublisher.Context, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            if (findPublishedImageNode() != null) {
                ctx.log("✅ 已检测到「我发布的」入口节点")
                return true
            }
            yield()
        }
        return false
    }

    private suspend fun clickPublishedEntry(ctx: WeiboPublisher.Context): Boolean {
        val end = System.currentTimeMillis() + PUBLISHED_ENTRY_WAIT_MAX_MS
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            val node = findPublishedImageNode()
            if (node != null) {
                ctx.showNodeEffect(node, "我发布的")
                yield()
                node.refresh()
                if (node.isClickable && node.click()) return true
                node.findFirstParentClickable()?.let { if (it.click()) return true }
                if (node.clickSelfOrParent()) return true
                val b = node.getBoundsInScreen()
                if (AssistsCore.gestureClick(b.centerX().toFloat(), b.centerY().toFloat(), duration = 65)) return true
                if (node.nodeGestureClick()) return true
            }
            yield()
        }
        return false
    }

    private fun collectNodesFromAllAccessibilityWindows(): List<AccessibilityNodeInfo> {
        val svc = AssistsService.instance ?: return AssistsCore.getAllNodes()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return AssistsCore.getAllNodes()
        val out = ArrayList<AccessibilityNodeInfo>(2048)
        runCatching {
            val windows = svc.windows
            if (!windows.isNullOrEmpty()) {
                for (win in windows) {
                    val root = win.root ?: continue
                    root.collectSubtreeNodes(out)
                    if (out.size >= 12_000) break
                }
                if (out.isNotEmpty()) return out
            }
        }
        return AssistsCore.getAllNodes()
    }

    private fun AccessibilityNodeInfo.collectSubtreeNodes(out: ArrayList<AccessibilityNodeInfo>) {
        out.add(this)
        if (out.size >= 12_000) return
        for (i in 0 until childCount) {
            getChild(i)?.collectSubtreeNodes(out)
        }
    }
}
