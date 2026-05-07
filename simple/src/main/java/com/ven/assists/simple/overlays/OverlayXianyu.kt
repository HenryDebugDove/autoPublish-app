package com.ven.assists.simple.overlays

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import com.blankj.utilcode.util.ScreenUtils
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import com.ven.assists.window.AssistsWindowManager
import com.ven.assists.window.AssistsWindowWrapper
import com.ven.assists.simple.databinding.XianyuOverlayBinding
import com.ven.assists.simple.xianyu.XianyuBatchRunner
import com.ven.assists.utils.CoroutineWrapper

/**
 * 主页「闲鱼」入口浮窗：内含「闲鱼刷新信息」（与悬浮小球同类批量逻辑）。
 */
@SuppressLint("StaticFieldLeak")
object OverlayXianyu : AssistsServiceListener {

    var onClose: ((parent: View) -> Unit)? = null

    var viewBinding: XianyuOverlayBinding? = null
        private set
        get() {
            if (field == null) {
                field = XianyuOverlayBinding.inflate(LayoutInflater.from(AssistsService.instance)).apply {
                    btnXianyuRefreshInfo.setOnClickListener {
                        hide()
                        CoroutineWrapper.launch(isMain = true) {
                            XianyuBatchRunner.run(XianyuBatchRunner.createLogOnlyContext())
                        }
                    }
                }
            }
            return field
        }

    var showed = false
        private set
        get() {
            assistWindowWrapper?.let {
                return AssistsWindowManager.isVisible(it.getView())
            } ?: return false
            return field
        }

    var assistWindowWrapper: AssistsWindowWrapper? = null
        private set
        get() {
            viewBinding?.let {
                if (field == null) {
                    field = AssistsWindowWrapper(
                        it.root,
                        wmLayoutParams = AssistsWindowManager.createLayoutParams().apply {
                            width = (ScreenUtils.getScreenWidth() * 0.8).toInt()
                            height = (ScreenUtils.getScreenHeight() * 0.5).toInt()
                        },
                        onClose = this.onClose
                    ).apply {
                        minWidth = (ScreenUtils.getScreenWidth() * 0.6).toInt()
                        minHeight = (ScreenUtils.getScreenHeight() * 0.4).toInt()
                        initialCenter = true
                        viewBinding.tvTitle.text = "闲鱼"
                    }
                }
            }
            return field
        }

    fun show() {
        if (!AssistsService.listeners.contains(this)) {
            AssistsService.listeners.add(this)
        }
        AssistsWindowManager.add(assistWindowWrapper)
    }

    fun hide() {
        AssistsWindowManager.removeView(assistWindowWrapper?.getView())
    }

    override fun onUnbind() {
        viewBinding = null
        assistWindowWrapper = null
    }
}
