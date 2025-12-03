package com.ven.assists.simple.quickball

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import com.blankj.utilcode.util.SizeUtils
import com.ven.assists.service.AssistsService
import com.ven.assists.simple.databinding.FloatingQuickBallBinding
import com.ven.assists.simple.overlays.OverlayBasic
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.utils.CoroutineWrapper
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全局悬浮小球，提供快速入口
 */
object FloatingQuickBallController {

    private var binding: FloatingQuickBallBinding? = null
    private var viewWrapper: AssistsWindowManager.ViewWrapper? = null
    private var isActionVisible = false

    fun show() {
        if (binding != null) return
        val service = AssistsService.instance ?: return
        val inflater = LayoutInflater.from(service)
        binding = FloatingQuickBallBinding.inflate(inflater).apply {
            btnBall.setOnClickListener { toggleMenu() }
            btnBall.setOnTouchListener(createDragListener())
            btnWeiboQuick.setOnClickListener {
                toggleMenu(false)
                CoroutineWrapper.launch(isMain = true) {
                    WeiboPublisher.publish(OverlayBasic.createWeiboAutomationContext())
                }
            }
        }
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            x = SizeUtils.dp2px(8f)
            y = 0
        }
        viewWrapper = AssistsWindowManager.add(binding?.root, params, isStack = true, isTouchable = true)
    }

    fun hide() {
        binding?.root?.let { AssistsWindowManager.removeWindow(it) }
        binding = null
        viewWrapper = null
        isActionVisible = false
    }

    private fun toggleMenu(forceShow: Boolean? = null) {
        val target = forceShow ?: !isActionVisible
        isActionVisible = target
        binding?.actionContainer?.apply {
            if (target) {
                visibility = android.view.View.VISIBLE
                startAnimation(AlphaAnimation(0f, 1f).apply { duration = 150 })
            } else {
                startAnimation(AlphaAnimation(1f, 0f).apply {
                    duration = 150
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation) {}
                        override fun onAnimationEnd(animation: Animation) {
                            visibility = android.view.View.GONE
                        }
                        override fun onAnimationRepeat(animation: Animation) {}
                    })
                })
            }
        }
    }

    private fun createDragListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            val wrapper = viewWrapper ?: return@OnTouchListener false
            val params = wrapper.layoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    if (!dragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x -= dx.toInt()
                        params.y += dy.toInt()
                        lastX = event.rawX
                        lastY = event.rawY
                        CoroutineWrapper.launch(isMain = true) {
                            AssistsWindowManager.updateViewLayout(binding?.root, params)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        // Treat as click handled elsewhere
                        return@OnTouchListener false
                    }
                }
            }
            dragging
        }
    }

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
}

