package com.ven.assists.simple.xianyu

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.blankj.utilcode.util.Utils
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.getAllNodes
import com.ven.assists.simple.weibo.WeiboPublisher
import kotlinx.coroutines.yield
import java.util.Collections

/**
 * 与 [XianyuBatchRunner]、[XianyuPolishRunner] 共用的：枚举名称/包名匹配「闲鱼」的应用、启动并等待无障碍树。
 */
object XianyuInstalledApps {

    const val LAUNCH_WAIT_MAX_MS = 15_000L
    const val TREE_READY_MAX_MS = 8_000L

    /** 名称黑名单：完全等于或包含某串由 [BLACKLIST_NAME_MODE] 决定 */
    val BLACKLIST_NAMES: List<String> = listOf("闲鱼群控助手")

    /** "exact"：显示名完全等于； "contains"：显示名包含即跳过 */
    var BLACKLIST_NAME_MODE: String = "exact"

    val BLACKLIST_PACKAGES: Set<String> = emptySet()

    /** 当应用标题不含「闲鱼」时，仍可按包名子串匹配（如官方 com.taobao.idlefish）。 */
    val PACKAGE_MATCH_HINTS: List<String> = listOf("idlefish", "taobao.idlefish")

    /**
     * 与 Hamibot 一致为 false：收集阶段不要求已有启动 Intent（避免部分 ROM 上误判为不可启动而整包被跳过）。
     */
    var REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING: Boolean = false

    private const val FILTER_AT_COLLECT = true

    data class AppItem(val label: String, val packageName: String)

    fun isInBlacklist(label: String, pkg: String): Boolean {
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

    fun matchesXianyu(label: String, packageName: String): Boolean {
        if (label.contains("闲鱼")) return true
        val lower = packageName.lowercase()
        return PACKAGE_MATCH_HINTS.any { hint -> lower.contains(hint.lowercase()) }
    }

    fun packageLooksLikeXianyuForeground(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return PACKAGE_MATCH_HINTS.any { pkg.contains(it, ignoreCase = true) }
    }

    fun collectXianyuApps(ctx: WeiboPublisher.Context): List<AppItem> {
        val pm = Utils.getApp().packageManager
        val result = ArrayList<AppItem>()
        val collectedPkgs = HashSet<String>()

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val launcherActivities = queryLauncherActivities(pm, launcherIntent)
        ctx.log("queryIntentActivities(MAIN/LAUNCHER, MATCH_ALL): ${launcherActivities.size} 条")
        for (ri in launcherActivities) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg in collectedPkgs) continue
            val nBefore = result.size
            tryAddApp(ctx, pm, pkg, ri, result)
            if (result.size > nBefore) collectedPkgs.add(pkg)
        }

        val appList = getInstalledApplicationsCompat(pm)
        ctx.log("getInstalledApplications: ${appList.size} 条")
        for (info in appList) {
            val pkg = info.packageName
            if (pkg in collectedPkgs) continue
            if (REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING && pm.getLaunchIntentForPackage(pkg) == null) continue
            try {
                val label = pm.getApplicationLabel(info).toString().trim()
                if (!matchesXianyu(label, pkg)) continue
                if (FILTER_AT_COLLECT && isInBlacklist(label, pkg)) {
                    ctx.log("收集阶段跳过黑名单: $label ($pkg)")
                    continue
                }
                collectedPkgs.add(pkg)
                result.add(AppItem(label = label, packageName = pkg))
            } catch (_: Exception) { }
        }

        runCatching {
            val pkgInfos = getInstalledPackagesCompat(pm)
            ctx.log("getInstalledPackages: ${pkgInfos.size} 条")
            for (pi in pkgInfos) {
                val pkg = pi.packageName
                if (pkg in collectedPkgs) continue
                if (REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING && pm.getLaunchIntentForPackage(pkg) == null) continue
                val ai = pi.applicationInfo ?: continue
                try {
                    val label = ai.loadLabel(pm).toString().trim()
                    if (!matchesXianyu(label, pkg)) continue
                    if (FILTER_AT_COLLECT && isInBlacklist(label, pkg)) {
                        ctx.log("收集阶段跳过黑名单: $label ($pkg)")
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

    fun logCollectDiagnostics(ctx: WeiboPublisher.Context) {
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
                ctx.log("诊断：getInstalledApplications 中未发现标签含「鱼」或包名含 fish 的项（可能被系统隐藏了应用列表）。")
            } else {
                ctx.log("诊断（前 ${samples.size} 条含「鱼」/fish 的应用，用于对照）:\n${samples.joinToString("\n")}")
            }
        }.onFailure {
            ctx.log("诊断日志失败: ${it.message}")
        }
    }

    suspend fun launchXianyuApp(
        ctx: WeiboPublisher.Context,
        item: AppItem,
        shouldAbort: () -> Boolean,
        treeLogHint: String = "仍继续尝试后续步骤"
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
        waitForAccessibilityTreeNonEmpty(ctx, shouldAbort, treeLogHint)
        return true
    }

    suspend fun waitForAccessibilityTreeNonEmpty(
        ctx: WeiboPublisher.Context,
        shouldAbort: () -> Boolean,
        treeLogHint: String = "仍继续尝试后续步骤"
    ) {
        val end = System.currentTimeMillis() + TREE_READY_MAX_MS
        while (System.currentTimeMillis() < end && !shouldAbort()) {
            if (getAllNodes().isNotEmpty()) return
            yield()
        }
        ctx.log("⚠️ 超时内无障碍树仍为空，$treeLogHint")
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

    private fun tryAddApp(
        ctx: WeiboPublisher.Context,
        pm: PackageManager,
        packageName: String,
        ri: ResolveInfo,
        out: MutableList<AppItem>
    ) {
        try {
            val label = ri.loadLabel(pm).toString().trim()
            if (!matchesXianyu(label, packageName)) return
            if (FILTER_AT_COLLECT && isInBlacklist(label, packageName)) {
                ctx.log("收集阶段跳过黑名单: $label ($packageName)")
                return
            }
            if (REQUIRE_LAUNCH_INTENT_WHEN_COLLECTING && pm.getLaunchIntentForPackage(packageName) == null) return
            out.add(AppItem(label = label, packageName = packageName))
        } catch (_: Exception) { }
    }
}
