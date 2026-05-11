package com.ven.assists.simple.xianyu

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.blankj.utilcode.util.ScreenUtils
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.findById
import com.ven.assists.AssistsCore.findByTags
import com.ven.assists.AssistsCore.findByText
import com.ven.assists.AssistsCore.findFirstParentClickable
import com.ven.assists.AssistsCore.getBoundsInScreen
import com.ven.assists.AssistsCore.getAllNodes
import com.ven.assists.AssistsCore.nodeGestureClick
import com.ven.assists.service.AssistsService
import com.ven.assists.simple.AutomationLog
import com.ven.assists.simple.common.LogWrapper
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.stepper.StepManager
import kotlinx.coroutines.yield
import java.text.Normalizer

/**
 * ## 功能
 * 闲鱼批量自动化（无障碍）：枚举本机**名称或包名**含「闲鱼」的应用，对每个 App **启动 → 点底部「消息」→
 * 检测到界面出现 **content-desc 为「通知消息」** 的节点（只检测、不点该节点）→ 再处理下一个；直至全部跑完。
 * 启动方式仅用 `PackageManager` + `startActivity`，不依赖桌面图标。
 *
 * ## 逻辑概要
 * 1. **收集应用**：[XianyuInstalledApps.collectXianyuApps]（与 [XianyuPolishRunner] 共用枚举与黑名单）。
 * 2. **启动**：[XianyuInstalledApps.launchXianyuApp]。
 * 3. **进入消息页**：多轮重试 [CLICK_RETRY]；每轮先 **策略①** 找 `tab_title` + 文案「消息」并快捷点击，
 *    再 **策略②** 底部 Tab 比例点（常量 `MESSAGE_TAB_REL_X` / `MESSAGE_TAB_REL_Y`）；每策略后等待 `PER_STRATEGY_SUCCESS_WAIT_MS` 并检测成功。
 * 4. **成功判定**：遍历 **所有无障碍窗口** 子树（避免本应用日志浮窗/悬浮球盖住闲鱼时 `rootInActiveWindow` 只剩覆盖层）；
 *    节点 `packageName` 为空或含 `idlefish`，`content-desc` 经 NFKC 与空白规整后等于「通知消息」，且 bounds 宽高大于 0。
 *
 * ## 使用方法
 * - 调用 `run(createLogOnlyContext())`：`log` 写入 [LogWrapper]；任务开始时 [AutomationLog.startLongRunningAutomation] 显示日志浮窗。
 * - 可调参数：黑名单与包名匹配见 [XianyuInstalledApps]；本文件内 `PER_STRATEGY_*`、`CLICK_RETRY` 等。
 * - 停止：日志浮窗「停止」、[com.ven.assists.simple.AutomationStop]、音量加键均会置 [StepManager.isStop] 并 `requestStop()`。
 */
object XianyuBatchRunner {

    private const val PER_STRATEGY_SUCCESS_WAIT_MS = 1_100L
    private const val CLICK_RETRY = 2

    private const val IDLEFISH_MESSAGE_TAB_VIEW_ID = "com.taobao.idlefish:id/tab_title"
    private const val IDLEFISH_MESSAGE_TAB_TEXT = "消息"
    private const val MESSAGE_TAB_REL_X = 0.707f
    private const val MESSAGE_TAB_REL_Y = 0.975f

    private const val FILTER_AT_COLLECT = true

    /** 名称黑名单（与 [XianyuInstalledApps.BLACKLIST_NAMES] 同步） */
    val BLACKLIST_NAMES: List<String> get() = XianyuInstalledApps.BLACKLIST_NAMES

    var BLACKLIST_NAME_MODE: String
        get() = XianyuInstalledApps.BLACKLIST_NAME_MODE
        set(value) {
            XianyuInstalledApps.BLACKLIST_NAME_MODE = value
        }

    val BLACKLIST_PACKAGES: Set<String> get() = XianyuInstalledApps.BLACKLIST_PACKAGES

    val PACKAGE_MATCH_HINTS: List<String> get() = XianyuInstalledApps.PACKAGE_MATCH_HINTS

    var REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING: Boolean
        get() = XianyuInstalledApps.REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING
        set(value) {
            XianyuInstalledApps.REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING = value
        }

    @Volatile
    private var stopRequested: Boolean = false

    fun requestStop() {
        stopRequested = true
    }

    private fun shouldAbort(): Boolean = stopRequested || StepManager.isStop

    fun createLogOnlyContext(): WeiboPublisher.Context {
        return WeiboPublisher.Context(
            log = { message ->
                LogWrapper.logAppend("[闲鱼] $message")
            },
            showNodeEffect = { _, _ -> },
            showPointEffect = { _, _, _ -> }
        )
    }

    suspend fun run(context: WeiboPublisher.Context) = with(context) {
        stopRequested = false
        AutomationLog.startLongRunningAutomation()
        val apps = XianyuInstalledApps.collectXianyuApps(this@with)
        if (apps.isEmpty()) {
            log("❌ 未找到应用名包含「闲鱼」的包（已放宽与 Hamibot 一致的枚举方式，仍为空请看下一条日志）。")
            XianyuInstalledApps.logCollectDiagnostics(this@with)
            log("提示：若已装闲鱼仍为空，请到系统设置中查看是否限制本应用「读取应用列表」；分身与宿主同包名时系统只有一条记录。")
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
            log("======== 进度 ${index + 1}/${apps.size} | ${item.label} | ${item.packageName} ========")
            if (!XianyuInstalledApps.launchXianyuApp(
                    this@with,
                    item,
                    shouldAbort = { shouldAbort() },
                    treeLogHint = "仍继续尝试点击消息"
                )
            ) {
                log("⚠️ 启动或进入前台超时，跳过: ${item.packageName}")
                continue
            }
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束任务。")
                return@with
            }
            val ok = openMessage(this@with)
            if (ok) {
                log("✅ [${item.packageName}] 已出现「通知消息」界面")
            } else {
                log("⚠️ [${item.packageName}] 未检测到「通知消息」界面")
            }
            if (index < apps.lastIndex) {
                yield()
            }
        }
        if (!shouldAbort()) {
            log("🎉 闲鱼批量任务全部处理完成。")
        }
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

    private fun matchesNotifyMessageDescription(raw: CharSequence?): Boolean {
        if (raw == null) return false
        val s = Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .trim()
            .replace(Regex("\\s+"), "")
        return s == "通知消息"
    }

    private fun isNotificationMessageSuccess(): Boolean {
        for (node in collectNodesFromAllAccessibilityWindows()) {
            val pkg = node.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && !pkg.contains("idlefish", ignoreCase = true)) continue
            if (!matchesNotifyMessageDescription(node.contentDescription)) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            return true
        }
        return false
    }

    private suspend fun waitNotificationMessageUi(ctx: WeiboPublisher.Context, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            if (isNotificationMessageSuccess()) {
                ctx.log("✅ 已检测到「通知消息」节点（仅检测，未点击该节点）")
                return true
            }
            yield()
        }
        return false
    }

    private suspend fun WeiboPublisher.Context.clickMessageTabQuick(tab: AccessibilityNodeInfo): Boolean {
        showNodeEffect(tab, "闲鱼 消息Tab")
        yield()
        repeat(2) { round ->
            log("Tab「消息」快捷尝试 ${round + 1}/2")
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

    private fun findIdlefishMessageTabNode(): AccessibilityNodeInfo? {
        findById(IDLEFISH_MESSAGE_TAB_VIEW_ID).firstOrNull { it.text?.toString() == IDLEFISH_MESSAGE_TAB_TEXT }?.let { return it }
        findByTags(
            "android.widget.TextView",
            viewId = IDLEFISH_MESSAGE_TAB_VIEW_ID,
            text = IDLEFISH_MESSAGE_TAB_TEXT
        ).firstOrNull()?.let { return it }
        findByText(IDLEFISH_MESSAGE_TAB_TEXT).firstOrNull { it.viewIdResourceName == IDLEFISH_MESSAGE_TAB_VIEW_ID }?.let { return it }
        return null
    }

    private suspend fun WeiboPublisher.Context.clickBottomTabRatioOnce(): Boolean {
        val w = ScreenUtils.getScreenWidth().toFloat()
        val h = ScreenUtils.getScreenHeight().toFloat()
        val x = w * MESSAGE_TAB_REL_X
        val y = h * MESSAGE_TAB_REL_Y
        log("底部「消息」比例点 (${MESSAGE_TAB_REL_X},${MESSAGE_TAB_REL_Y})")
        showPointEffect(x, y, "闲鱼底栏消息")
        val ok = AssistsCore.gestureClick(x, y, duration = 55)
        if (ok) log("已派发底部比例手势")
        return ok
    }

    private suspend fun openMessage(ctx: WeiboPublisher.Context): Boolean {
        val slice = PER_STRATEGY_SUCCESS_WAIT_MS
        repeat(CLICK_RETRY) { attempt ->
            if (shouldAbort()) return false
            ctx.log("──────── 进入消息 第 ${attempt + 1}/$CLICK_RETRY 轮 ────────")

            val tab = findIdlefishMessageTabNode()
            if (tab != null) {
                ctx.log("策略① 底部 tab_title「消息」节点")
                ctx.clickMessageTabQuick(tab)
                if (waitNotificationMessageUi(ctx, slice)) return true
            } else {
                ctx.log("策略① 跳过：未找到底部「消息」节点")
            }

            ctx.log("策略② 底部 Tab 比例点")
            ctx.clickBottomTabRatioOnce()
            if (waitNotificationMessageUi(ctx, slice)) return true

            yield()
        }
        ctx.log("⚠️ 多种方式尝试后仍未出现「通知消息」")
        return false
    }
}
