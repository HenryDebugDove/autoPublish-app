package com.ven.assists.simple

import com.ven.assists.simple.common.LogWrapper
import com.ven.assists.simple.overlays.OverlayLog
import com.ven.assists.stepper.StepManager
import com.ven.assists.utils.CoroutineWrapper
import kotlinx.coroutines.delay

/**
 * 长耗时自动化：统一弹出可滚动日志浮窗、可中断等待，与 [AutomationStop] / 日志「停止」、音量加配合。
 */
object AutomationLog {

    /** 任务开始时调用：显示 [OverlayLog] 并清除 [StepManager.isStop]（新任务可跑）。 */
    fun startLongRunningAutomation() {
        StepManager.isStop = false
        CoroutineWrapper.launch(isMain = true) { OverlayLog.show() }
    }

    fun shouldStop(): Boolean = StepManager.isStop

    /** 写入日志缓存（[OverlayLog] 打开时会滚动展示）。 */
    fun append(tag: String, message: String) {
        LogWrapper.logAppend("[$tag] $message")
    }

    /**
     * 可中断的等待：期间用户点日志「停止」或按音量加会置 [StepManager.isStop]，此处提前结束。
     */
    suspend fun waitUnlessStopped(totalMs: Long, chunkMs: Long = 400L) {
        var left = totalMs
        while (left > 0 && !StepManager.isStop) {
            val step = minOf(chunkMs, left)
            delay(step)
            left -= step
        }
    }
}
