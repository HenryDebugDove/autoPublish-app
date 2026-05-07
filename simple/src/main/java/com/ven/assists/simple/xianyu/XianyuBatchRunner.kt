package com.ven.assists.simple.xianyu

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.Utils
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
import com.ven.assists.simple.common.LogWrapper
import com.ven.assists.simple.overlays.OverlayLog
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.stepper.StepManager
import com.ven.assists.utils.runMain
import kotlinx.coroutines.yield
import java.text.Normalizer
import java.util.Collections

/**
 * ## 功能
 * 闲鱼批量自动化（无障碍）：枚举本机**名称或包名**含「闲鱼」的应用，对每个 App **启动 → 点底部「消息」→
 * 检测到界面出现 **content-desc 为「通知消息」** 的节点（只检测、不点该节点）→ 再处理下一个；直至全部跑完。
 * 启动方式仅用 `PackageManager` + `startActivity`，不依赖桌面图标。
 *
 * ## 逻辑概要
 * 1. **收集应用**：`MAIN/LAUNCHER` 与 `getInstalledApplications` / `getInstalledPackages` 并集去重；
 *    [BLACKLIST_NAMES]、[BLACKLIST_PACKAGES]、[PACKAGE_MATCH_HINTS] 控制匹配与黑名单。
 * 2. **启动**：`getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`，轮询 [AssistsCore.getPackageName] 直至目标闲鱼包或超时。
 * 3. **进入消息页**：多轮重试 [CLICK_RETRY]；每轮先 **策略①** 找 `tab_title` + 文案「消息」并快捷点击，
 *    再 **策略②** 底部 Tab 比例点（常量 `MESSAGE_TAB_REL_X` / `MESSAGE_TAB_REL_Y`）；每策略后等待 `PER_STRATEGY_SUCCESS_WAIT_MS` 并检测成功。
 * 4. **成功判定**：遍历 **所有无障碍窗口** 子树（避免本应用日志浮窗/悬浮球盖住闲鱼时 `rootInActiveWindow` 只剩覆盖层）；
 *    节点 `packageName` 为空或含 `idlefish`，`content-desc` 经 NFKC 与空白规整后等于「通知消息」，且 bounds 宽高大于 0。
 *
 * ## 使用方法
 * - 调用 `run(createLogOnlyContext())`：`log` 写入 [LogWrapper]，任务开始时会 `OverlayLog.show()` 显示滚动日志。
 * - 悬浮球入口示例：`CoroutineWrapper.launch(isMain = true) { XianyuBatchRunner.run(XianyuBatchRunner.createLogOnlyContext()) }`
 * - 可调参数：文件内 `LAUNCH_*`、`PER_STRATEGY_*`、`CLICK_RETRY`、黑名单与 [PACKAGE_MATCH_HINTS] 等。
 * - 停止：日志浮窗「停止」、[com.ven.assists.simple.AutomationStop]、音量加键均会置 [StepManager.isStop] 并 `requestStop()`。
 */
object XianyuBatchRunner {

    private const val LAUNCH_WAIT_MAX_MS = 15_000L
    /** 每种点击策略派发后的短时等待窗口，未出现「通知消息」则立刻换下一策略 */
    private const val PER_STRATEGY_SUCCESS_WAIT_MS = 1_100L
    private const val TREE_READY_MAX_MS = 8_000L
    private const val CLICK_RETRY = 5

    private const val IDLEFISH_PKG = "com.taobao.idlefish"
    private const val IDLEFISH_MESSAGE_TAB_VIEW_ID = "com.taobao.idlefish:id/tab_title"
    private const val IDLEFISH_MESSAGE_TAB_TEXT = "消息"
    /** 底部「消息」Tab 比例兜底 */
    private const val MESSAGE_TAB_REL_X = 0.707f
    private const val MESSAGE_TAB_REL_Y = 0.975f
    /** 名称黑名单：完全等于或包含某串由 [BLACKLIST_NAME_MODE] 决定 */
    val BLACKLIST_NAMES: List<String> = listOf("闲鱼群控助手")

    /** "exact"：显示名完全等于； "contains"：显示名包含即跳过 */
    var BLACKLIST_NAME_MODE: String = "exact"

    val BLACKLIST_PACKAGES: Set<String> = emptySet()

    /**
     * 当应用标题不含「闲鱼」时，仍可按包名子串匹配（如官方 com.taobao.idlefish）。
     */
    val PACKAGE_MATCH_HINTS: List<String> = listOf("idlefish", "taobao.idlefish")

    /**
     * 与 Hamibot 一致为 false：收集阶段不要求已有启动 Intent（避免部分 ROM 上误判为不可启动而整包被跳过）。
     */
    var REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING: Boolean = false

    private const val FILTER_AT_COLLECT = true

    @Volatile
    private var stopRequested: Boolean = false

    fun requestStop() {
        stopRequested = true
    }

    private fun shouldAbort(): Boolean = stopRequested || StepManager.isStop

    /**
     * 仅日志浮窗：[LogWrapper] + [OverlayLog]，无点击特效（减轻干扰）。
     */
    fun createLogOnlyContext(): WeiboPublisher.Context {
        return WeiboPublisher.Context(
            log = { message ->
                LogWrapper.logAppend("[闲鱼] $message")
            },
            showNodeEffect = { _, _ -> },
            showPointEffect = { _, _, _ -> }
        )
    }

    /**
     * 依次处理每个匹配应用：启动 → 点「消息」→ 出现「通知消息」界面 → 下一个。
     */
    suspend fun run(context: WeiboPublisher.Context) = with(context) {
        stopRequested = false
        StepManager.isStop = false
        runMain { OverlayLog.show() }
        val apps = collectXianyuApps()
        if (apps.isEmpty()) {
            log("❌ 未找到应用名包含「闲鱼」的包（已放宽与 Hamibot 一致的枚举方式，仍为空请看下一条日志）。")
            logCollectDiagnostics()
            log("提示：若已装闲鱼仍为空，请到系统设置中查看是否限制本应用「读取应用列表」；分身与宿主同包名时系统只有一条记录。")
            return@with
        }
        log("发现匹配应用数量(过滤黑名单后): ${apps.size}")
        for ((index, item) in apps.withIndex()) {
            if (shouldAbort()) {
                log("⚠️ 已请求停止，结束任务。")
                return@with
            }
            if (!FILTER_AT_COLLECT && isInBlacklist(item.label, item.packageName)) {
                log("跳过(黑名单): ${item.label} (${item.packageName})")
                continue
            }
            log("======== 进度 ${index + 1}/${apps.size} | ${item.label} | ${item.packageName} ========")
            if (!launchAppItem(this@with, item)) {
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

    data class AppItem(val label: String, val packageName: String)

    private fun isInBlacklist(label: String, pkg: String): Boolean {
        if (BLACKLIST_NAMES.isNotEmpty()) {
            for (name in BLACKLIST_NAMES) {
                when (BLACKLIST_NAME_MODE) {
                    "exact" -> if (label == name) return true
                    else -> if (label.contains(name)) return true
                }
            }
        }
        if (BLACKLIST_PACKAGES.isNotEmpty() && pkg in BLACKLIST_PACKAGES) return true
        return false
    }

    private fun WeiboPublisher.Context.collectXianyuApps(): List<AppItem> {
        val pm = Utils.getApp().packageManager
        val result = ArrayList<AppItem>()
        val collectedPkgs = HashSet<String>()

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val launcherActivities = queryLauncherActivities(pm, launcherIntent)
        log("queryIntentActivities(MAIN/LAUNCHER, MATCH_ALL): ${launcherActivities.size} 条")
        for (ri in launcherActivities) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg in collectedPkgs) continue
            val nBefore = result.size
            tryAddApp(pm, pkg, ri, result)
            if (result.size > nBefore) collectedPkgs.add(pkg)
        }

        val appList = getInstalledApplicationsCompat(pm)
        log("getInstalledApplications: ${appList.size} 条")
        for (info in appList) {
            val pkg = info.packageName
            if (pkg in collectedPkgs) continue
            if (REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING && pm.getLaunchIntentForPackage(pkg) == null) continue
            try {
                val label = pm.getApplicationLabel(info).toString().trim()
                if (!matchesXianyu(label, pkg)) continue
                if (FILTER_AT_COLLECT && isInBlacklist(label, pkg)) {
                    log("收集阶段跳过黑名单: $label ($pkg)")
                    continue
                }
                collectedPkgs.add(pkg)
                result.add(AppItem(label = label, packageName = pkg))
            } catch (_: Exception) { }
        }

        runCatching {
            val pkgInfos = getInstalledPackagesCompat(pm)
            log("getInstalledPackages: ${pkgInfos.size} 条")
            for (pi in pkgInfos) {
                val pkg = pi.packageName
                if (pkg in collectedPkgs) continue
                if (REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING && pm.getLaunchIntentForPackage(pkg) == null) continue
                val ai = pi.applicationInfo ?: continue
                try {
                    val label = ai.loadLabel(pm).toString().trim()
                    if (!matchesXianyu(label, pkg)) continue
                    if (FILTER_AT_COLLECT && isInBlacklist(label, pkg)) {
                        log("收集阶段跳过黑名单: $label ($pkg)")
                        continue
                    }
                    collectedPkgs.add(pkg)
                    result.add(AppItem(label = label, packageName = pkg))
                } catch (_: Exception) { }
            }
        }

        val pmUniq = ArrayList(result.distinctBy { it.packageName })
        Collections.sort(pmUniq, compareBy({ it.label }, { it.packageName }))
        return pmUniq
    }

    private fun getInstalledApplicationsCompat(pm: PackageManager): List<ApplicationInfo> {
        var flags = PackageManager.GET_META_DATA.toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            flags = flags or PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags.toInt())
        }
    }

    private fun getInstalledPackagesCompat(pm: PackageManager): List<android.content.pm.PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
    }

    private fun queryLauncherActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_ALL).toLong()
        } else {
            PackageManager.MATCH_DEFAULT_ONLY.toLong()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, flags.toInt())
        }
    }

    private fun WeiboPublisher.Context.logCollectDiagnostics() {
        runCatching {
            val pm = Utils.getApp().packageManager
            val samples = ArrayList<String>()
            for (info in getInstalledApplicationsCompat(pm)) {
                val label = runCatching { pm.getApplicationLabel(info).toString().trim() }.getOrNull().orEmpty()
                if (label.contains("鱼") || info.packageName.contains("fish", ignoreCase = true)) {
                    samples.add("$label / ${info.packageName}")
                }
                if (samples.size >= 20) break
            }
            if (samples.isEmpty()) {
                log("诊断：getInstalledApplications 中未发现标签含「鱼」或包名含 fish 的项（可能被系统隐藏了应用列表）。")
            } else {
                log("诊断（前 ${samples.size} 条含「鱼」/fish 的应用，用于对照）:\n${samples.joinToString("\n")}")
            }
        }.onFailure {
            log("诊断日志失败: ${it.message}")
        }
    }

    private fun matchesXianyu(label: String, packageName: String): Boolean {
        if (label.contains("闲鱼")) return true
        val lower = packageName.lowercase()
        return PACKAGE_MATCH_HINTS.any { hint -> lower.contains(hint.lowercase()) }
    }

    private fun WeiboPublisher.Context.tryAddApp(
        pm: PackageManager,
        packageName: String,
        ri: ResolveInfo,
        out: MutableList<AppItem>
    ) {
        try {
            val label = ri.loadLabel(pm).toString().trim()
            if (!matchesXianyu(label, packageName)) return
            if (FILTER_AT_COLLECT && isInBlacklist(label, packageName)) {
                log("收集阶段跳过黑名单: $label ($packageName)")
                return
            }
            if (REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING && pm.getLaunchIntentForPackage(packageName) == null) return
            out.add(AppItem(label = label, packageName = packageName))
        } catch (_: Exception) { }
    }

    private suspend fun launchAppItem(
        ctx: WeiboPublisher.Context,
        item: AppItem
    ): Boolean {
        val app = Utils.getApp()
        val pm = app.packageManager
        val intent = pm.getLaunchIntentForPackage(item.packageName) ?: run {
            ctx.log("❌ 无启动 Intent: ${item.packageName}")
            return false
        }
        ctx.log("▶ 启动: ${item.label} (${item.packageName})")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        val start = System.currentTimeMillis()
        var ok = false
        while (System.currentTimeMillis() - start < LAUNCH_WAIT_MAX_MS) {
            if (shouldAbort()) return false
            val cur = AssistsCore.getPackageName()
            if (cur == item.packageName || packageLooksLikeXianyuForeground(cur)) {
                ok = true
                break
            }
            yield()
        }
        if (!ok) {
            ctx.log("❌ 未在 ${LAUNCH_WAIT_MAX_MS}ms 内进入前台: ${item.packageName}")
            return false
        }
        ctx.log("✅ 已进入 ${AssistsCore.getPackageName()}，等待无障碍树就绪…")
        waitForAccessibilityTreeNonEmpty(ctx)
        return true
    }

    private suspend fun waitForAccessibilityTreeNonEmpty(ctx: WeiboPublisher.Context) {
        val end = System.currentTimeMillis() + TREE_READY_MAX_MS
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            if (getAllNodes().isNotEmpty()) return
            yield()
        }
        ctx.log("⚠️ 超时内无障碍树仍为空，仍继续尝试点击消息")
    }

    private fun packageLooksLikeXianyuForeground(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return PACKAGE_MATCH_HINTS.any { pkg.contains(it, ignoreCase = true) }
    }

    /** 与 XPathLite content-desc 对齐（兼容全角空格、NFKC、少见空白） */
    private fun matchesNotifyMessageDescription(raw: CharSequence?): Boolean {
        if (raw == null) return false
        val s = Normalizer.normalize(raw.toString(), Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .trim()
            .replace(Regex("\\s+"), "")
        return s == "通知消息"
    }

    /**
     * [AssistsCore.getAllNodes] 只遍历「当前活动窗口」的根。
     * 开启本应用的日志浮窗 / 悬浮球时，活动窗口常变为覆盖层，导致拿不到闲鱼界面节点。
     * API 21+ 遍历 [AccessibilityService.getWindows] 下每个窗口的根子树。
     */
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

    /**
     * 成功：在任意无障碍窗口子树中，找到「通知消息」节点：
     * - `packageName` 为空或含 idlefish（忽略其它 App 同名描述）
     * - content-desc 经规范化后等于「通知消息」
     */
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

    /** 少量快速点击尝试，便于尽快切换到下一策略 */
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
