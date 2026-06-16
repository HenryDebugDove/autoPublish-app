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
 * 闲鱼上架：点底部「我的」→「我发布的」→「草稿」Tab，循环发布草稿；
 * 全部完成后额外返回上一页。
 */
object XianyuListingRunner {

    private const val TAB_TITLE_ID = "com.taobao.idlefish:id/tab_title"
    private const val MY_TAB_TEXT = "我的"
    private const val MY_TAB_REL_X = 0.92f
    private const val MY_TAB_REL_Y = 0.975f
    private const val PUBLISHED_DESC_PREFIX = "我发布的"
    private const val PUBLISHED_ENTRY_WAIT_MAX_MS = 12_000L
    private const val POST_PUBLISHED_SETTLE_MS = 1_200L
    private const val PER_STRATEGY_SLICE_MS = 1_100L

    private const val DRAFT_PUBLISH_ROUNDS = 5
    private const val PAGE_SETTLE_MS = 1_200L
    /** 每次点击「发布」后短暂等待 */
    private const val POST_PUBLISH_CLICK_WAIT_MS = 1_500L
    /** 判定发布成功前等待页面稳定 */
    private const val POST_PUBLISH_SETTLE_MS = 2_000L
    private const val PUBLISH_SUCCESS_MAX_MS = 35_000L
    private const val PUBLISH_CLICK_MAX_ATTEMPTS = 10
    /** 发布成功页回到草稿列表需连续返回次数 */
    private const val BACK_TO_DRAFT_COUNT = 2
    private const val DRAFT_TAB_WAIT_MAX_MS = 8_000L
    private const val EDIT_OCR_WAIT_MAX_MS = 10_000L
    private const val CLICK_RETRY = 2
    private const val OVERLAY_HIDDEN_DELAY_MS = 250L
    /** 每条发布成功后，再发下一条前的随机间隔（毫秒） */
    private const val INTER_ROUND_DELAY_MIN_MS = 3_000L
    private const val INTER_ROUND_DELAY_MAX_MS = 5_000L

    /** uiautomatorviewer：草稿 Tab bounds [238,892][480,1046] 中心（1260 宽屏） */
    private const val DRAFT_TAB_ABS_X = 359f
    private const val DRAFT_TAB_ABS_Y = 969f

    /** 草稿列表 OCR 区域最小 top（Tab 下方列表区） */
    private const val DRAFT_LIST_MIN_TOP = 1046

    private const val EDIT_OCR_KEYWORD = "编辑"

    private val DRAFT_TAB_DESC_REGEX = Regex("第\\s*2\\s*个标签.*共\\s*3\\s*个")
    private val PUBLISH_DESC_REGEX = Regex("^发布(,\\s*发布)?$")

    @Volatile
    private var stopRequested: Boolean = false

    fun requestStop() {
        stopRequested = true
    }

    private fun shouldAbort(): Boolean = stopRequested || StepManager.isStop

    fun createLogOnlyContext(): WeiboPublisher.Context {
        return WeiboPublisher.Context(
            log = { message -> LogWrapper.logAppend("[闲鱼上架] $message") },
            showNodeEffect = { _, _ -> },
            showPointEffect = { _, _, _ -> }
        )
    }

    suspend fun run(context: WeiboPublisher.Context) = with(context) {
        stopRequested = false
        AutomationLog.startLongRunningAutomation()
        log("将发布 $DRAFT_PUBLISH_ROUNDS 条草稿。")

        if (!openMyTab(this@with)) {
            log("❌ 未能进入「我的」页，任务结束。")
            return@with
        }
        log("✅ 已进入「我的」页。")
        if (!clickPublishedEntry(this@with)) {
            log("❌ 未找到或未点到「我发布的」入口，任务结束。")
            return@with
        }
        log("✅ 已点击「我发布的」入口。")
        if (!waitPublishedListPageReady(this@with)) {
            log("⚠️ 「我发布的」页 Tab 栏未就绪，仍尝试点草稿。")
        }
        AutomationLog.waitUnlessStopped(POST_PUBLISHED_SETTLE_MS + PAGE_SETTLE_MS)

        if (!clickDraftTab(this@with)) {
            log("❌ 未能进入或确认「草稿」Tab，任务结束。")
            return@with
        }
        log("✅ 已在「草稿」Tab。")

        var successCount = 0
        for (round in 1..DRAFT_PUBLISH_ROUNDS) {
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束任务。")
                return@with
            }
            log("======== 上架 第 $round/$DRAFT_PUBLISH_ROUNDS 轮 ========")
            if (!clickFirstEditViaOcr(this@with)) {
                log("⚠️ 第 $round 轮：OCR 未识别或未点到「编辑」，跳过。")
                continue
            }
            log("✅ 第 $round 轮：已 OCR 点击第一个「编辑」。")
            AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束任务。")
                return@with
            }
            if (!publishUntilSuccess(this@with)) {
                log("⚠️ 第 $round 轮：反复点击「发布」仍未成功，尝试返回。")
                backTwiceToDraftList(this@with)
                continue
            }
            log("✅ 第 $round 轮：发布成功。")
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束任务。")
                return@with
            }
            backTwiceToDraftList(this@with)
            AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
            successCount++
            if (round < DRAFT_PUBLISH_ROUNDS) {
                waitBeforeNextPublishRound(this@with)
            }
        }
        if (!shouldAbort()) {
            log("🎉 闲鱼上架任务结束，成功完成 $successCount/$DRAFT_PUBLISH_ROUNDS 轮。")
            AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
            if (back()) {
                log("✅ 全部草稿发布完成，已返回上一页。")
            } else {
                log("⚠️ 全部草稿发布完成，返回上一页未成功。")
            }
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

    private suspend fun clickMyTabRatio(ctx: WeiboPublisher.Context): Boolean {
        val w = ScreenUtils.getScreenWidth().toFloat()
        val h = ScreenUtils.getScreenHeight().toFloat()
        val x = w * MY_TAB_REL_X
        val y = h * MY_TAB_REL_Y
        ctx.log("底部比例点 ($MY_TAB_REL_X, $MY_TAB_REL_Y)")
        ctx.showPointEffect(x, y, "闲鱼底栏我的")
        return AssistsCore.gestureClick(x, y, duration = 55)
    }

    private fun matchesPublishedContentDesc(raw: CharSequence?): Boolean {
        val s = normalizeDesc(raw)
        return s.startsWith(PUBLISHED_DESC_PREFIX)
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

    /** 发布成功并回到草稿列表后，随机等待 3～5 秒再发下一条 */
    private suspend fun waitBeforeNextPublishRound(ctx: WeiboPublisher.Context) {
        val waitMs = (INTER_ROUND_DELAY_MIN_MS..INTER_ROUND_DELAY_MAX_MS).random()
        ctx.log("发布成功，等待 ${waitMs / 1000.0}s 后继续下一条")
        AutomationLog.waitUnlessStopped(waitMs)
    }

    /** 等待「我发布的」页 Tab 栏出现（含草稿 Tab 节点） */
    private suspend fun waitPublishedListPageReady(ctx: WeiboPublisher.Context): Boolean {
        val end = System.currentTimeMillis() + PUBLISHED_ENTRY_WAIT_MAX_MS
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            if (findDraftTabNode() != null) {
                ctx.log("✅ 「我发布的」页 Tab 栏已就绪")
                return true
            }
            yield()
        }
        return false
    }

    /**
     * 是否已在「草稿」Tab 且列表就绪。
     * 不能仅凭 OCR 识别到「编辑」：在售等 Tab 列表项也可能带「编辑」，会误判跳过点草稿。
     */
    private suspend fun isDraftListReady(ctx: WeiboPublisher.Context): Boolean {
        val tab = findDraftTabNode() ?: return false
        if (!tab.isSelected) return false
        if (ocrFindFirstEditPosition(ctx) != null) return true
        return waitDraftListVisible(ctx, 800L)
    }

    private suspend fun clickDraftTab(ctx: WeiboPublisher.Context): Boolean {
        repeat(CLICK_RETRY) { attempt ->
            if (shouldAbort()) return false
            ctx.log("──────── 点「草稿」Tab 第 ${attempt + 1}/$CLICK_RETRY 轮 ────────")
            if (isDraftListReady(ctx)) {
                ctx.log("✅ 草稿列表已可见，无需再点 Tab")
                return true
            }
            val end = System.currentTimeMillis() + DRAFT_TAB_WAIT_MAX_MS
            while (System.currentTimeMillis() < end && !shouldAbort()) {
                if (isDraftListReady(ctx)) {
                    ctx.log("✅ 草稿列表已可见")
                    return true
                }
                val tab = findDraftTabNode()
                if (tab != null) {
                    if (clickNodeWithFallbacks(ctx, tab, "草稿Tab")) {
                        AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
                        if (isDraftListReady(ctx)) {
                            return true
                        }
                    }
                }
                yield()
            }
            ctx.log("策略② 草稿 Tab 固定坐标 (${DRAFT_TAB_ABS_X.toInt()},${DRAFT_TAB_ABS_Y.toInt()})")
            ctx.showPointEffect(DRAFT_TAB_ABS_X, DRAFT_TAB_ABS_Y, "草稿Tab")
            if (AssistsCore.gestureClick(DRAFT_TAB_ABS_X, DRAFT_TAB_ABS_Y, duration = 65)) {
                AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
                if (isDraftListReady(ctx)) {
                    return true
                }
            }
            yield()
        }
        ctx.log("⚠️ 多轮尝试后仍未进入草稿列表")
        return false
    }

    /** OCR 识别「编辑」，多个时点击最靠上的第一个 */
    private suspend fun clickFirstEditViaOcr(ctx: WeiboPublisher.Context): Boolean {
        repeat(CLICK_RETRY) { attempt ->
            if (shouldAbort()) return false
            ctx.log("──────── OCR 点「编辑」第 ${attempt + 1}/$CLICK_RETRY 轮 ────────")
            val end = System.currentTimeMillis() + EDIT_OCR_WAIT_MAX_MS
            while (System.currentTimeMillis() < end && !shouldAbort()) {
                val position = ocrFindFirstEditPosition(ctx)
                if (position == null) {
                    yield()
                    continue
                }
                val cx = (position.left + position.right) / 2f
                val cy = (position.top + position.bottom) / 2f
                ctx.log("OCR 命中「$EDIT_OCR_KEYWORD」中心 ($cx,$cy) 框=[${position.left},${position.top},${position.right},${position.bottom}]")
                ctx.showPointEffect(cx, cy, "编辑")
                if (AssistsCore.gestureClick(cx, cy, duration = 65)) return true
                yield()
            }
            ctx.log("⚠️ 第 ${attempt + 1} 次 OCR 未点到「编辑」")
            yield()
        }
        return false
    }

    /** 反复点击「发布」直至离开编辑页（发布按钮消失且页面稳定） */
    private suspend fun publishUntilSuccess(ctx: WeiboPublisher.Context): Boolean {
        val deadline = System.currentTimeMillis() + PUBLISH_SUCCESS_MAX_MS
        var clickAttempts = 0
        ctx.log("──────── 反复点击「发布」直至发布成功 ────────")
        while (System.currentTimeMillis() < deadline && !shouldAbort()) {
            if (clickAttempts > 0 && isPublishSucceeded()) {
                ctx.log("✅ 发布成功（编辑页「发布」按钮已消失且页面稳定）")
                return true
            }
            val publishNode = findPublishButtonNode()
            if (publishNode != null) {
                if (clickAttempts >= PUBLISH_CLICK_MAX_ATTEMPTS) {
                    ctx.log("⚠️ 已达最大点击次数 $PUBLISH_CLICK_MAX_ATTEMPTS，停止继续点「发布」")
                    break
                }
                clickAttempts++
                ctx.log("「发布」第 $clickAttempts/$PUBLISH_CLICK_MAX_ATTEMPTS 次点击")
                clickNodeWithFallbacks(ctx, publishNode, "发布")
                AutomationLog.waitUnlessStopped(POST_PUBLISH_CLICK_WAIT_MS)
                continue
            }
            if (clickAttempts > 0) {
                AutomationLog.waitUnlessStopped(POST_PUBLISH_SETTLE_MS)
                if (isPublishSucceeded()) {
                    ctx.log("✅ 发布成功（已进入成功/结果页）")
                    return true
                }
            }
            yield()
        }
        if (clickAttempts > 0 && isPublishSucceeded()) {
            ctx.log("✅ 发布成功（超时前最后确认）")
            return true
        }
        ctx.log("⚠️ 发布未成功（共点击「发布」${clickAttempts} 次）")
        return false
    }

    /** 编辑页「发布」按钮已消失，并额外等待页面稳定 */
    private suspend fun isPublishSucceeded(): Boolean {
        if (findPublishButtonNode() != null) return false
        AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
        return findPublishButtonNode() == null
    }

    /** 发布成功页连续两次返回，回到草稿列表 */
    private suspend fun backTwiceToDraftList(ctx: WeiboPublisher.Context) {
        ctx.log("成功页连续返回 $BACK_TO_DRAFT_COUNT 次，回到草稿列表")
        AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
        repeat(BACK_TO_DRAFT_COUNT) { index ->
            if (shouldAbort()) return
            if (back()) {
                ctx.log("✅ 第 ${index + 1}/$BACK_TO_DRAFT_COUNT 次返回上一页")
            } else {
                ctx.log("⚠️ 第 ${index + 1}/$BACK_TO_DRAFT_COUNT 次返回未成功")
            }
            AutomationLog.waitUnlessStopped(PAGE_SETTLE_MS)
        }
    }

    private suspend fun ocrFindFirstEditPosition(
        ctx: WeiboPublisher.Context
    ): TextRecognitionChineseLocator.WordPosition? {
        AssistsWindowManager.hideAll()
        delay(OVERLAY_HIDDEN_DELAY_MS)
        return try {
            val w = ScreenUtils.getScreenWidth()
            val h = ScreenUtils.getScreenHeight()
            val listTop = DRAFT_LIST_MIN_TOP.coerceAtMost(h - 1)
            val region = Rect(0, listTop, w, h)
            val recognitionResult = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    TextRecognitionChineseLocator.findWordPositionsInScreenshotRegion(
                        region = region,
                        targetText = EDIT_OCR_KEYWORD
                    )
                } else {
                    val bitmap = MPManager.takeScreenshot2Bitmap()
                        ?: throw IllegalStateException("MediaProjection 截图失败")
                    try {
                        TextRecognitionChineseLocator.findWordPositionsInRegion(
                            bitmap = bitmap,
                            region = region,
                            targetText = EDIT_OCR_KEYWORD
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
                ctx.log("OCR「$EDIT_OCR_KEYWORD」耗时 ${recognition.processingTimeMillis}ms，匹配 ${recognition.targetPositions.size} 处")
                pickFirstOcrPosition(recognition.targetPositions)
            }
        } finally {
            AssistsWindowManager.showTop()
        }
    }

    /** 多个「编辑」时取最靠上（top 最小）的第一个 */
    private fun pickFirstOcrPosition(
        positions: List<TextRecognitionChineseLocator.WordPosition>
    ): TextRecognitionChineseLocator.WordPosition? {
        if (positions.isEmpty()) return null
        return positions.minWith(compareBy({ it.top }, { it.left }))
    }

    private suspend fun clickNodeWithFallbacks(
        ctx: WeiboPublisher.Context,
        node: AccessibilityNodeInfo,
        label: String
    ): Boolean {
        ctx.showNodeEffect(node, label)
        yield()
        repeat(2) { round ->
            ctx.log("「$label」快捷点击 ${round + 1}/2")
            node.refresh()
            if (node.isClickable && node.click()) return true
            node.findFirstParentClickable()?.let { parent -> if (parent.click()) return true }
            if (node.clickSelfOrParent()) return true
            val b = node.getBoundsInScreen()
            if (AssistsCore.gestureClick(b.centerX().toFloat(), b.centerY().toFloat(), duration = 65)) return true
            if (node.nodeGestureClick()) return true
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

    private fun normalizeLabel(raw: CharSequence?): String {
        if (raw == null) return ""
        return Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .trim()
            .replace(Regex("\\s+"), "")
    }

    private fun normalizeDesc(raw: CharSequence?): String {
        if (raw == null) return ""
        return Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun matchesDraftTab(node: AccessibilityNodeInfo): Boolean {
        val desc = normalizeDesc(node.contentDescription)
        val text = normalizeDesc(node.text)
        if (DRAFT_TAB_DESC_REGEX.containsMatchIn(desc.replace(" ", ""))) return true
        if (desc.contains("草稿") || text.contains("草稿")) return true
        return false
    }

    private fun matchesPublishDesc(raw: CharSequence?): Boolean {
        val s = normalizeLabel(raw)
        if (s.isEmpty()) return false
        if (PUBLISH_DESC_REGEX.matches(s)) return true
        return s.startsWith("发布")
    }

    private fun nodePackageAcceptableForCurrentFish(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val fg = AssistsCore.getPackageName().orEmpty()
        if (fg.isNotEmpty() && pkg == fg) return true
        if (pkg.contains("idlefish", ignoreCase = true)) return true
        return XianyuInstalledApps.PACKAGE_MATCH_HINTS.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun findDraftTabNode(): AccessibilityNodeInfo? {
        for (node in collectNodesFromAllAccessibilityWindows()) {
            val pkg = node.packageName?.toString().orEmpty()
            if (!nodePackageAcceptableForCurrentFish(pkg)) continue
            if (!matchesDraftTab(node)) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            return node
        }
        return null
    }

    private fun findPublishButtonNode(): AccessibilityNodeInfo? {
        for (node in collectNodesFromAllAccessibilityWindows()) {
            val pkg = node.packageName?.toString().orEmpty()
            if (!nodePackageAcceptableForCurrentFish(pkg)) continue
            if (node.className?.toString() != "android.widget.Button") continue
            if (!matchesPublishDesc(node.contentDescription)) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            return node
        }
        return null
    }

    private suspend fun waitDraftListVisible(ctx: WeiboPublisher.Context, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            if (ocrFindFirstEditPosition(ctx) != null) {
                ctx.log("✅ 已 OCR 识别到草稿列表「编辑」按钮")
                return true
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
