package com.ven.assists.simple

import android.app.Application
import com.blankj.utilcode.util.Utils
import com.ven.assists.AssistsCore
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import com.ven.assists.simple.quickball.FloatingQuickBallController
import com.ven.assists.simple.step.GestureBottomTab
import com.ven.assists.simple.step.GestureScrollSocial
import com.ven.assists.simple.step.OpenWechatSocial
import com.ven.assists.simple.step.PublishSocial
import com.ven.assists.simple.step.ScrollContacts
import com.ven.assists.stepper.StepManager
import com.ven.assists.utils.CoroutineWrapper
import kotlinx.coroutines.delay

class App : Application() {

    companion object {
        const val TARGET_PACKAGE_NAME = "com.tencent.mm"
    }

    private val quickBallListener = object : AssistsServiceListener {
        override fun onServiceConnected(service: AssistsService) {
            // 服务连接时显示悬浮球
            FloatingQuickBallController.show()
        }

        override fun onUnbind() {
            // 服务断开时隐藏悬浮球
            FloatingQuickBallController.hide()
        }

        override fun onInterrupt() {
            // 服务中断时隐藏悬浮球
            FloatingQuickBallController.hide()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Utils.init(this)
        //设置全局步骤默认间隔时长
        StepManager.DEFAULT_STEP_DELAY = 1000L
        // 注册悬浮球监听器，使其在服务连接时自动显示
        AssistsService.listeners.add(quickBallListener)
        // 延迟检查服务是否已连接，如果已连接则显示悬浮球
        CoroutineWrapper.launch {
            delay(500) // 等待服务初始化
            if (AssistsService.instance != null) {
                FloatingQuickBallController.show()
            }
        }
    }
}