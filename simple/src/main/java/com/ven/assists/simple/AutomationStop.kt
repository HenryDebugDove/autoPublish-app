package com.ven.assists.simple

import com.ven.assists.simple.xianyu.XianyuBatchRunner
import com.ven.assists.simple.xianyu.XianyuPolishRunner
import com.ven.assists.stepper.StepManager

/**
 * 统一停止：步骤器 [StepManager]、闲鱼批量 [XianyuBatchRunner]、闲鱼擦亮 [XianyuPolishRunner] 等。
 * 日志浮窗「停止」、音量加键等均应走此入口。
 */
object AutomationStop {

    fun requestStopAllScripts() {
        StepManager.isStop = true
        XianyuBatchRunner.requestStop()
        XianyuPolishRunner.requestStop()
    }
}
