package com.ven.assists.simple.overlays

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.BarUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.TimeUtils
import com.blankj.utilcode.util.ToastUtils
import com.lzy.okgo.OkGo
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.longClick
import com.ven.assists.AssistsCore.getAllNodes
import com.ven.assists.AssistsCore.getBoundsInScreen
import com.ven.assists.AssistsCore.longPressGestureAutoPaste
import com.ven.assists.AssistsCore.nodeGestureClick
import com.ven.assists.AssistsCore.paste
import com.ven.assists.AssistsCore.scrollBackward
import com.ven.assists.AssistsCore.scrollForward
import com.ven.assists.AssistsCore.findByTags
import com.ven.assists.AssistsCore.findFirstParentClickable
import com.ven.assists.AssistsCore.selectionText
import com.ven.assists.AssistsCore.setNodeText
import com.ven.assists.service.AssistsService
import com.ven.assists.service.AssistsServiceListener
import com.ven.assists.window.AssistsWindowManager
import com.ven.assists.window.AssistsWindowManager.overlayToast
import com.ven.assists.window.AssistsWindowWrapper
import com.ven.assists.simple.MultiTouchDrawingActivity
import com.ven.assists.simple.ScreenshotReviewActivity
import com.ven.assists.simple.TestActivity
import com.ven.assists.simple.common.LogWrapper
import com.ven.assists.simple.databinding.BasicOverlayBinding
import com.ven.assists.simple.weibo.WeiboPublisher
import com.ven.assists.utils.AudioPlayerUtil
import com.ven.assists.utils.CoroutineWrapper
import com.ven.assists.utils.FileDownloadUtil
import com.ven.assists.utils.runMain
import kotlinx.coroutines.delay
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic
import java.io.File

object OverlayBasic : AssistsServiceListener {

    @SuppressLint("StaticFieldLeak")
    var viewBinding: BasicOverlayBinding? = null
        private set
        get() {
            if (field == null) {
                field = BasicOverlayBinding.inflate(LayoutInflater.from(AssistsService.instance)).apply {
                    btnWeibo.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            overlayLogging("开始执行微博流程")
                            if (!clickTextWithRetry("首页")) {
                                overlayLogging("未找到“首页”入口")
                                return@launch
                            }
                            delay(600)
                            if (!clickWeiboAddEntry()) {
                                overlayLogging("未找到微博加号入口")
                                return@launch
                            }
                            delay(500)
                            if (!clickTextWithRetry("图片")) {
                                overlayLogging("未找到\"图片\"入口")
                                return@launch
                            }
                            overlayLogging("已打开图片，等待加载")
                            delay(1500) // 等待图片加载
                            // 使用微博专用的相册选择方法：RecyclerView -> FrameLayout -> TextView(checkbox)
                            if (!selectWeiboImages()) {
                                overlayLogging("未能选中图片")
                                return@launch
                            }
                            delay(500)
                            // 第一次点击"下一步"（从相册选择页面进入图片编辑页面）
                            if (!clickTextWithRetry("下一步")) {
                                overlayLogging("未找到\"下一步\"按钮")
                                return@launch
                            }
                            overlayLogging("已点击第一步的下一步，等待页面加载")
                            
                            // 等待新页面加载完成
                            delay(2000) // 延时等待新页面加载
                            
                            // 第二次点击"下一步"（从图片编辑页面进入发布页面）
                            overlayLogging("准备点击第二步的下一步")
                            if (!clickTextWithRetry("下一步")) {
                                overlayLogging("未找到第二步的\"下一步\"按钮")
                                return@launch
                            }
                            overlayLogging("已点击第二步的下一步，进入发布页面")
                            
                            // 等待发布页面加载
                            delay(1000)
                            
                            // 参考文章方案：先获取输入框焦点，再读取剪切板（适配Android 10+）
                            overlayLogging("开始处理：先获取输入框焦点，再读取剪切板")
                            
                            // 步骤1: 查找输入框（android.widget.ScrollView下的android.widget.EditText，text是"分享新鲜事..."）
                            overlayLogging("步骤1: 查找输入框")
                            var inputEditText: AccessibilityNodeInfo? = null
                            
                            AssistsCore.findByTags("android.widget.ScrollView").forEach { scrollView ->
                                scrollView.findByTags("android.widget.EditText").forEach { editText ->
                                    val editTextValue = editText.text?.toString() ?: ""
                                    val contentDesc = editText.contentDescription?.toString() ?: ""
                                    if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事")) {
                                        overlayLogging("找到输入框")
                                        inputEditText = editText
                                        return@forEach
                                    }
                                }
                                if (inputEditText != null) return@forEach
                            }
                            
                            // 如果方法1没找到，尝试直接查找EditText
                            if (inputEditText == null) {
                                AssistsCore.findByTags("android.widget.EditText").forEach { editText ->
                                    val editTextValue = editText.text?.toString() ?: ""
                                    val contentDesc = editText.contentDescription?.toString() ?: ""
                                    if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事")) {
                                        overlayLogging("通过直接查找找到输入框")
                                        inputEditText = editText
                                        return@forEach
                                    }
                                }
                            }
                            
                            inputEditText?.let { editTextNode ->
                                // 步骤2: 点击输入框获得焦点（关键步骤）
                                overlayLogging("步骤2: 点击输入框获得焦点")
                                
                                // 点击输入框中心位置
                                val bounds = editTextNode.getBoundsInScreen()
                                val centerX = bounds.centerX().toFloat()
                                val centerY = bounds.centerY().toFloat()
                                
                                overlayLogging("点击输入框坐标: ($centerX, $centerY) 获取焦点")
                                showClickEffect(centerX, centerY, "点击输入框获取焦点")
                                
                                // 方式1: 直接点击输入框
                                if (editTextNode.isClickable) {
                                    editTextNode.click()
                                } else {
                                    editTextNode.findFirstParentClickable()?.click()
                                }
                                
                                // 方式2: 执行 FOCUS 操作
                                editTextNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                
                                // 方式3: 手势点击（备选）
                                AssistsCore.gestureClick(centerX, centerY)
                                
                                overlayLogging("等待输入框获得焦点并稳定...")
                                delay(800) // 等待焦点稳定
                                
                                // 步骤3: 记录粘贴前的输入框状态
                                overlayLogging("步骤3: 记录粘贴前的输入框状态")
                                val beforeText = if (editTextNode.refresh()) {
                                    editTextNode.text?.toString() ?: ""
                                } else {
                                    ""
                                }
                                val beforeLength = beforeText.length
                                overlayLogging("粘贴前输入框文本长度: $beforeLength")
                                overlayLogging("粘贴前输入框内容: ${beforeText.take(50)}")
                                
                                // 步骤4: 执行粘贴操作（尝试多种方式）
                                overlayLogging("步骤4: 执行粘贴操作")
                                
                                var pasteAttempted = false
                                
                                // 方式1: 使用 ACTION_PASTE
                                overlayLogging("方式1: 尝试 ACTION_PASTE")
                                editTextNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                delay(300)
                                val pasteSuccess = editTextNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                overlayLogging("ACTION_PASTE 执行结果: $pasteSuccess")
                                if (pasteSuccess) {
                                    pasteAttempted = true
                                }
                                
                                // 方式2: 如果 ACTION_PASTE 失败，尝试点击粘贴按钮
                                if (!pasteSuccess) {
                                    overlayLogging("方式2: ACTION_PASTE 失败，尝试点击粘贴按钮")
                                    delay(300)
                                    
                                    // 查找并点击粘贴按钮
                                    var pasteButtonClicked = false
                                    
                                    // 2.1: 通过文本查找粘贴按钮
                                    AssistsCore.findByText("粘贴").forEach { pasteButton ->
                                        if (pasteButton.isClickable) {
                                            showClickEffect(pasteButton, "点击粘贴按钮")
                                            delay(100)
                                            if (pasteButton.click() || pasteButton.findFirstParentClickable()?.click() == true) {
                                                pasteButtonClicked = true
                                                pasteAttempted = true
                                                return@forEach
                                            }
                                        }
                                    }
                                    
                                    // 2.2: 通过坐标点击粘贴按钮（输入框下方）
                                    if (!pasteButtonClicked) {
                                        val bounds2 = editTextNode.getBoundsInScreen()
                                        val pasteX = bounds2.centerX().toFloat()
                                        val pasteY = bounds2.bottom + 80f
                                        overlayLogging("尝试点击粘贴按钮坐标: ($pasteX, $pasteY)")
                                        showClickEffect(pasteX, pasteY, "坐标点击粘贴按钮")
                                        delay(100)
                                        if (AssistsCore.gestureClick(pasteX, pasteY)) {
                                            pasteAttempted = true
                                        }
                                    }
                                }
                                
                                if (pasteAttempted) {
                                    overlayLogging("粘贴操作已执行，等待粘贴完成...")
                                } else {
                                    overlayLogging("⚠️ 所有粘贴方式都失败，但仍会尝试检测文本变化")
                                }
                                
                                delay(800) // 等待粘贴操作完成
                                
                                // 步骤5: 在输入框末尾添加 #前端收徒#
                                overlayLogging("步骤5: 在输入框末尾添加标签 #前端收徒#")
                                delay(500)
                                
                                // 重新查找输入框获取最新内容
                                var finalEditText: AccessibilityNodeInfo? = null
                                AssistsCore.findByTags("android.widget.ScrollView").forEach { scrollView ->
                                    scrollView.findByTags("android.widget.EditText").forEach { editText ->
                                        val editTextValue = editText.text?.toString() ?: ""
                                        val contentDesc = editText.contentDescription?.toString() ?: ""
                                        if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事") || editTextValue.isNotEmpty()) {
                                            if (editText.refresh()) {
                                                finalEditText = editText
                                                return@forEach
                                            }
                                        }
                                    }
                                    if (finalEditText != null) return@forEach
                                }
                                
                                // 如果没找到，直接查找所有EditText
                                if (finalEditText == null) {
                                    AssistsCore.findByTags("android.widget.EditText").forEach { editText ->
                                        val editTextValue = editText.text?.toString() ?: ""
                                        if (editTextValue.isNotEmpty() || editText.contentDescription?.toString()?.contains("分享新鲜事") == true) {
                                            if (editText.refresh()) {
                                                finalEditText = editText
                                                return@forEach
                                            }
                                        }
                                    }
                                }
                                
                                finalEditText?.let { currentEditText ->
                                    val currentText = currentEditText.text?.toString() ?: ""
                                    overlayLogging("当前输入框内容长度: ${currentText.length}")
                                    
                                    if (currentText.isNotEmpty() && !currentText.endsWith("#前端收徒#")) {
                                        // 拼接完整文本（在末尾添加标签）
                                        val finalText = currentText.trimEnd() + " #前端收徒#"
                                        overlayLogging("准备添加标签，最终文本: ${finalText.take(50)}...")
                                        
                                        // 先点击输入框末尾位置
                                        val bounds2 = currentEditText.getBoundsInScreen()
                                        val endX = bounds2.right - 20f
                                        val centerY2 = bounds2.centerY().toFloat()
                                        
                                        // 点击输入框末尾以定位光标
                                        AssistsCore.gestureClick(endX, centerY2)
                                        delay(300)
                                        
                                        // 使用 setNodeText 设置完整文本
                                        if (currentEditText.setNodeText(finalText)) {
                                            overlayLogging("✅ 标签添加成功（setNodeText）")
                                        } else {
                                            // 备选方案：直接粘贴标签
                                            overlayLogging("setNodeText失败，尝试粘贴标签")
                                            delay(300)
                                            currentEditText.paste(" #前端收徒#")
                                        }
                                    } else if (currentText.endsWith("#前端收徒#")) {
                                        overlayLogging("标签已存在，无需添加")
                                    } else {
                                        overlayLogging("输入框为空，直接设置标签")
                                        currentEditText.setNodeText("#前端收徒#")
                                    }
                                } ?: overlayLogging("未找到输入框，无法添加标签")
                                
                                delay(500)
                                
                            } ?: overlayLogging("❌ 未找到输入框，无法继续")
                            
                            // 步骤6: 点击发送按钮
                            overlayLogging("步骤6: 查找并点击发送按钮")
                            delay(500)
                            
                            // 点击发送按钮（android.widget.LinearLayout下的android.widget.TextView，text是"发送"）
                            overlayLogging("查找发送按钮")
                            
                            // 方法1: 通过LinearLayout -> TextView查找
                            var sendButtonFound = false
                            AssistsCore.findByTags("android.widget.LinearLayout").forEach { linearLayout ->
                                linearLayout.findByTags("android.widget.TextView").forEach { textView ->
                                    val text = textView.text?.toString() ?: ""
                                    if (text == "发送") {
                                        overlayLogging("找到发送按钮，准备点击")
                                        textView.findFirstParentClickable()?.let { parent ->
                                            if (parent.click()) {
                                                overlayLogging("发送按钮点击成功")
                                                sendButtonFound = true
                                                return@forEach
                                            }
                                        }
                                        // 如果父节点点击失败，直接点击TextView
                                        if (!sendButtonFound && textView.isClickable && textView.click()) {
                                            overlayLogging("发送按钮点击成功（直接点击）")
                                            sendButtonFound = true
                                            return@forEach
                                        }
                                    }
                                }
                                if (sendButtonFound) return@forEach
                            }
                            
                            // 方法2: 直接通过文本查找
                            if (!sendButtonFound) {
                                AssistsCore.findByText("发送").forEach { textView ->
                                    overlayLogging("通过文本查找找到发送按钮")
                                    textView.findFirstParentClickable()?.let { parent ->
                                        if (parent.click()) {
                                            overlayLogging("发送按钮点击成功")
                                            sendButtonFound = true
                                            return@forEach
                                        }
                                    }
                                    if (!sendButtonFound && textView.isClickable && textView.click()) {
                                        overlayLogging("发送按钮点击成功（直接点击）")
                                        sendButtonFound = true
                                    }
                                }
                            }
                            
                            if (sendButtonFound) {
                                overlayLogging("✅ 微博发布流程完成")
                            } else {
                                overlayLogging("❌ 未找到发送按钮")
                            }
                        }
                    }
                    //点击
                    btnClick.setOnClickListener {
                        CoroutineWrapper.launch {
                            AssistsService.instance?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)
                            AssistsCore.findById("com.ven.assists.demo:id/btn_test").firstOrNull()?.click()
                        }
                    }
                    //手势点击
                    btnGestureClick.setOnClickListener {
                        CoroutineWrapper.launch {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)
                            AssistsCore.findById("com.ven.assists.demo:id/btn_test").firstOrNull()?.nodeGestureClick()
                        }
                    }
                    //长按
                    btnLongClick.setOnClickListener {
                        CoroutineWrapper.launch {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)
                            AssistsCore.findById("com.ven.assists.demo:id/btn_test").firstOrNull()?.longClick()
                        }
                    }
                    //手势长按
                    btnGestureLongClick.setOnClickListener {
                        CoroutineWrapper.launch {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)
                            AssistsCore.findById("com.ven.assists.demo:id/btn_test").firstOrNull()?.nodeGestureClick(duration = 1000)
                        }
                    }
                    //单指手势（画圆）
                    btnGestureSingleDraw.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()
                                ?.startActivity(Intent(AssistsService.instance, MultiTouchDrawingActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            delay(1000)

                            performCircularGestureSingle()
                        }
                    }
                    //双指手势（画圆）
                    btnGestureDoubleDraw.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()
                                ?.startActivity(Intent(AssistsService.instance, MultiTouchDrawingActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            delay(1000)

                            performCircularGestureDouble()
                        }
                    }
                    //单指手势（不规则）
                    btnGestureThreeDraw.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()
                                ?.startActivity(Intent(AssistsService.instance, MultiTouchDrawingActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            delay(1000)

                            performSnakeGesture()
                        }
                    }
                    //选择文本
                    btnSelectText.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)

                            TestActivity.scrollUp?.invoke()
                            delay(500)

                            AssistsCore.findById("com.ven.assists.demo:id/et_input").firstOrNull()?.let {
                                it.selectionText(it.text.length - 3, it.text.length)
                            }
                        }

                    }
                    //修改文本
                    btnChangeText.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)

                            TestActivity.scrollUp?.invoke()
                            delay(500)
                            AssistsCore.findById("com.ven.assists.demo:id/et_input").firstOrNull()?.let {
                                it.setNodeText("测试修改文本: ${TimeUtils.getNowString()}")
                            }
                        }
                    }
                    //向前滚动
                    btnListScroll.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)

                            TestActivity.scrollUp?.invoke()
                            delay(500)
                            var next = true
                            while (next) {
                                AssistsCore.findById("com.ven.assists.demo:id/scrollView").firstOrNull()?.let {
                                    next = it.scrollForward()
                                    delay(1000)
                                }
                            }
                            "已滚动到底部".overlayToast()
                        }

                    }
                    //向后滚动
                    btnListScrollBack.setOnClickListener {
                        CoroutineWrapper.launch(isMain = true) {
                            ActivityUtils.getTopActivity()?.startActivity(Intent(AssistsService.instance, TestActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            delay(1000)

                            TestActivity.scrollDown?.invoke()
                            delay(500)
                            var next = true
                            while (next) {
                                AssistsCore.findById("com.ven.assists.demo:id/scrollView").firstOrNull()?.let {
                                    next = it.scrollBackward()
                                    delay(1000)
                                }
                            }
                            "已滚动到顶部".overlayToast()
                        }

                    }

                    //返回
                    btnBack.setOnClickListener {
                        AssistsCore.back()
                    }
                    //桌面
                    btnHome.setOnClickListener { AssistsCore.home() }
                    //通知
                    btnTask.setOnClickListener { AssistsCore.recentApps() }
                    //最新任务
                    btnNotification.setOnClickListener { AssistsCore.notifications() }

                    btnPowerDialog.setOnClickListener {
                        AssistsService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)

                    }
                    btnToggleSplitScreen.setOnClickListener {
                        AssistsService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

                    }
                    btnLockScreen.setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            AssistsService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        } else {
                            "仅支持Android9及以上版本".overlayToast()
                        }
                    }
                    btnTakeScreenshot.setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            AssistsService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                        } else {
                            "仅支持Android9及以上版本".overlayToast()
                        }
                    }
                    btn1.setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            AssistsService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
                        } else {
                            "仅支持Android12及以上版本".overlayToast()
                        }
                    }
                    btn2.setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            AssistsService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
                        } else {
                            "仅支持Android12及以上版本".overlayToast()
                        }
                    }
                    btnToast.setOnClickListener {
                        "这是浮窗级别Toast：${TimeUtils.getNowString()}".overlayToast(delay = 3000)
                    }
                }
            }
            return field
        }

    var onClose: ((parent: View) -> Unit)? = null

    var showed = false
        private set
        get() {
            assistWindowWrapper?.let {
                return AssistsWindowManager.isVisible(it.getView())
            } ?: return false
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
                        viewBinding.tvTitle.text = "基础示例"

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

    /**
     * 为微博自动化发布提供统一的上下文配置，
     * 复用当前浮窗里的日志与点击特效能力。
     */
    fun createWeiboAutomationContext(): WeiboPublisher.Context {
        return WeiboPublisher.Context(
            log = { message ->
                overlayLogging(message)
            },
            showNodeEffect = { node, label ->
                showClickEffect(node, label)
            },
            showPointEffect = { x, y, label ->
                showClickEffect(x, y, label)
            }
        )
    }

    fun hide() {
        AssistsWindowManager.removeView(assistWindowWrapper?.getView())
    }

    override fun onUnbind() {
        viewBinding = null
        assistWindowWrapper = null
    }

    suspend fun performCircularGestureSingle() {
        val screenWidth = ScreenUtils.getScreenWidth()
        val screenHeight = ScreenUtils.getScreenHeight()
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val radius1 = 200f // 第一个圆的半径

        // 创建第一个圆的路径
        val path1 = Path()
        path1.addCircle(centerX, centerY, radius1, Path.Direction.CW)

        // 创建两个手势描述
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 2000)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(stroke1)

        // 分发手势
        AssistsCore.dispatchGesture(gestureBuilder.build())
    }

    suspend fun performCircularGestureDouble() {
        val screenWidth = ScreenUtils.getScreenWidth()
        val screenHeight = ScreenUtils.getScreenHeight()
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val radius1 = 200f // 第一个圆的半径
        val radius2 = 300f // 第二个圆的半径

        // 创建第一个圆的路径
        val path1 = Path()
        path1.addCircle(centerX, centerY, radius1, Path.Direction.CW)

        // 创建第二个圆的路径
        val path2 = Path()
        path2.addCircle(centerX, centerY, radius2, Path.Direction.CW)

        // 创建两个手势描述
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 2000)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, 2000)

        // 创建手势构建器并添加两个手势
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(stroke1)
        gestureBuilder.addStroke(stroke2)

        // 分发手势
        AssistsCore.dispatchGesture(gestureBuilder.build())
    }

    suspend fun performSnakeGesture() {
        val screenWidth = ScreenUtils.getScreenWidth()
        val screenHeight = ScreenUtils.getScreenHeight()
        val segmentHeight = 200f // 每段路径的垂直高度
        val maxHorizontalOffset = 300f // 水平方向的最大偏移量

        val path = Path()
        var currentY = BarUtils.getStatusBarHeight() + BarUtils.getActionBarHeight().toFloat() + 100f
        var currentX = screenWidth / 2f // 从屏幕中间开始

        // 移动到起点
        path.moveTo(currentX, currentY)

        // 生成蛇形路径
        while (currentY < screenHeight) {
            // 随机生成水平偏移量
            val offsetX = (Math.random() * maxHorizontalOffset * 2 - maxHorizontalOffset).toFloat()
            currentX += offsetX
            currentY += segmentHeight

            // 确保 X 坐标在屏幕范围内
            currentX = currentX.coerceIn(0f, screenWidth.toFloat())

            // 添加路径点
            path.lineTo(currentX, currentY)
        }

        // 创建手势描述
        val stroke = GestureDescription.StrokeDescription(path, 0, 5000) // 5秒完成手势
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(stroke)

        // 分发手势
        AssistsCore.dispatchGesture(gestureBuilder.build())
    }

    /**
     * 读取剪切板第一条内容（在输入框获得焦点后调用，适配Android 10+）
     * 参考文章：https://juejin.cn/post/7124972221491052575
     * 关键：先获取焦点，再读取剪切板
     */
    private suspend fun getClipboardTextAfterFocus(): String {
        return try {
            var result = ""
            
            // 尝试在主线程中读取
            runMain {
                try {
                    // 尝试多种 Context，优先使用当前 Activity Context（因为输入框已获得焦点）
                    val contexts = listOfNotNull(
                        ActivityUtils.getTopActivity(), // 优先使用当前 Activity（输入框所在页面）
                        AssistsService.instance?.applicationContext,
                        AssistsService.instance
                    )
                    
                    for (context in contexts) {
                        try {
                            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                ?: continue
                            
                            if (!clipboardManager.hasPrimaryClip()) {
                                overlayLogging("Context ${context.javaClass.simpleName}: hasPrimaryClip=false")
                                continue
                            }
                            
                            val clip = clipboardManager.primaryClip
                            if (clip == null) {
                                overlayLogging("Context ${context.javaClass.simpleName}: primaryClip=null")
                                continue
                            }
                            
                            if (clip.itemCount <= 0) {
                                overlayLogging("Context ${context.javaClass.simpleName}: itemCount=${clip.itemCount}")
                                continue
                            }
                            
                            val item = clip.getItemAt(0)
                            if (item == null) {
                                overlayLogging("Context ${context.javaClass.simpleName}: item[0]=null")
                                continue
                            }
                            
                            // 优先使用 coerceToText（文章推荐的方式，适配 Android 10+）
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val coercedText = item.coerceToText(context)
                                    result = coercedText?.toString() ?: ""
                                    if (result.isNotEmpty()) {
                                        overlayLogging("成功读取剪切板（coerceToText，${context.javaClass.simpleName}），长度: ${result.length}")
                                        return@runMain
                                    }
                                } catch (e: Exception) {
                                    overlayLogging("coerceToText失败（${context.javaClass.simpleName}）: ${e.message}")
                                }
                            }
                            
                            // 备选：直接获取 text
                            result = item.text?.toString() ?: ""
                            if (result.isNotEmpty()) {
                                overlayLogging("成功读取剪切板（直接获取text，${context.javaClass.simpleName}），长度: ${result.length}")
                                return@runMain
                            }
                            
                        } catch (e: Exception) {
                            overlayLogging("使用 ${context.javaClass.simpleName} 读取失败: ${e.message}")
                            LogUtils.e("读取剪切板异常", e)
                            continue
                        }
                    }
                } catch (e: Exception) {
                    overlayLogging("读取剪切板异常: ${e.message}")
                    LogUtils.e("读取剪切板异常", e)
                }
            }
            
            if (result.isEmpty()) {
                overlayLogging("❌ 所有方式都无法读取剪切板（请确认输入框已获得焦点）")
            }
            result
        } catch (e: Exception) {
            overlayLogging("getClipboardTextAfterFocus 外层异常: ${e.message}")
            LogUtils.e("getClipboardTextAfterFocus异常", e)
            ""
        }
    }

    /**
     * 读取剪切板第一条内容（旧版本，保留备用）
     */
    private suspend fun getClipboardText(): String {
        return getClipboardTextAfterFocus()
    }

    /**
     * 查找输入框并粘贴内容
     */
    private suspend fun pasteToEditText(text: String) {
        overlayLogging("查找输入框，准备粘贴内容")
        var inputFound = false
        
        // 方法1: 通过ScrollView -> EditText查找
        AssistsCore.findByTags("android.widget.ScrollView").forEach { scrollView ->
            scrollView.findByTags("android.widget.EditText").forEach { editText ->
                val editTextValue = editText.text?.toString() ?: ""
                val contentDesc = editText.contentDescription?.toString() ?: ""
                if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事")) {
                    overlayLogging("找到输入框，准备输入内容")
                    editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(300)
                    
                    // 方式1: 尝试直接设置文本
                    if (editText.setNodeText(text)) {
                        overlayLogging("内容设置成功（setNodeText）")
                        inputFound = true
                    } else {
                        // 方式2: 尝试粘贴
                        if (editText.paste(text)) {
                            overlayLogging("内容粘贴成功")
                            inputFound = true
                        } else {
                            // 方式3: 尝试长按粘贴
                            overlayLogging("粘贴失败，尝试长按粘贴")
                            delay(500)
                            if (editText.longPressGestureAutoPaste(text, timeoutMillis = 2000)) {
                                overlayLogging("长按粘贴成功")
                                inputFound = true
                            }
                        }
                    }
                    return@forEach
                }
            }
            if (inputFound) return@forEach
        }
        
        // 方法2: 直接查找EditText
        if (!inputFound) {
            AssistsCore.findByTags("android.widget.EditText").forEach { editText ->
                val editTextValue = editText.text?.toString() ?: ""
                val contentDesc = editText.contentDescription?.toString() ?: ""
                if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事")) {
                    overlayLogging("通过直接查找找到输入框，准备输入内容")
                    editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    delay(300)
                    
                    // 方式1: 尝试直接设置文本
                    if (editText.setNodeText(text)) {
                        overlayLogging("内容设置成功（setNodeText）")
                        inputFound = true
                    } else {
                        // 方式2: 尝试粘贴
                        if (editText.paste(text)) {
                            overlayLogging("内容粘贴成功")
                            inputFound = true
                        } else {
                            delay(500)
                            if (editText.longPressGestureAutoPaste(text, timeoutMillis = 2000)) {
                                overlayLogging("长按粘贴成功")
                                inputFound = true
                            }
                        }
                    }
                    return@forEach
                }
            }
        }
        
        if (!inputFound) {
            overlayLogging("未找到输入框")
        }
        
        delay(500)
    }

    private suspend fun clickTextWithRetry(
        text: String,
        attempts: Int = 5,
        interval: Long = 500L
    ): Boolean {
        repeat(attempts) {
            AssistsCore.findByText(text).firstOrNull()?.let { node ->
                showClickEffect(node, "点击: $text")
                delay(100)
                if (node.clickSelfOrParent()) {
                    overlayLogging("点击文本\"$text\"成功")
                    return true
                }
            }
            delay(interval)
        }
        return false
    }

    private suspend fun clickWeiboAddEntry(): Boolean {
        // 临时跳过前面的策略，直接使用坐标点击进行测试
        overlayLogging("直接执行硬编码坐标点击")
        return clickWeiboAddByCoordinate()
        
        // 原始逻辑（已注释，用于后续恢复）
        /*
        overlayLogging("开始定位微博加号（文本匹配）")
        val quickTexts = listOf("发布", "写微博", "+")
        quickTexts.forEach { text ->
            if (clickTextWithRetry(text, attempts = 1, interval = 0L)) {
                overlayLogging("通过文本\"$text\"点击加号成功")
                return true
            }
        }
        overlayLogging("文本匹配未命中，尝试节点扫描")
        if (tryClickWeiboAddByNodeMatch()) return true
        overlayLogging("节点扫描未命中，尝试区域内控件")
        if (tryClickWeiboAddByLikelyRegion()) return true
        overlayLogging("区域内控件未命中，开始执行硬编码坐标序列")
        return clickWeiboAddByCoordinate()
        */
    }

    private suspend fun tryClickWeiboAddByNodeMatch(): Boolean {
        val keywords = listOf("发布", "发微博", "写微博", "加号", "更多", "创建", "compose")
        val nodes = AssistsCore.getAllNodes()
        val candidates = nodes.filter { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val matchByWord = keywords.any { keyword ->
                text.contains(keyword, ignoreCase = true) || desc.contains(keyword, ignoreCase = true)
            }
            val matchById = viewId.contains("plus", ignoreCase = true) ||
                    viewId.contains("add", ignoreCase = true) ||
                    viewId.contains("compose", ignoreCase = true) ||
                    viewId.contains("publish", ignoreCase = true) ||
                    viewId.contains("create", ignoreCase = true)
            val isImageLike = node.className?.contains("Image", ignoreCase = true) == true ||
                    node.className?.contains("View", ignoreCase = true) == true
            (matchByWord || matchById) && node.isVisibleToUser && isImageLike
        }.sortedByDescending { it.getBoundsInScreen().centerX() }
        candidates.forEach { node ->
            if (node.smartGestureClick()) {
                overlayLogging("节点匹配命中，已尝试点击")
                return true
            }
        }
        return false
    }

    private suspend fun tryClickWeiboAddByLikelyRegion(): Boolean {
        val nodes = AssistsCore.getAllNodes()
        val screenWidth = ScreenUtils.getScreenWidth()
        val screenHeight = ScreenUtils.getScreenHeight()
        val regionLeft = (screenWidth * 0.65).toInt()
        val regionTop = BarUtils.getStatusBarHeight()
        val regionBottom = (screenHeight * 0.35).toInt()
        nodes.filter { node ->
            val bounds = node.getBoundsInScreen()
            bounds.left >= regionLeft &&
                    bounds.top in regionTop..regionBottom &&
                    node.className?.contains("Image", ignoreCase = true) == true
        }.sortedByDescending { it.getBoundsInScreen().centerX() }
            .forEach { node ->
                if (node.smartGestureClick()) {
                    overlayLogging("区域内 ImageView 命中，已尝试点击")
                    return true
                }
            }
        return false
    }

    private suspend fun clickWeiboAddByCoordinate(): Boolean {
        val x = 1200f
        val y = 150f
        overlayLogging("准备使用坐标点击: x=$x, y=$y")
        val attempts: List<Pair<String, suspend () -> Boolean>> = listOf(
            "方案A：标准单击" to suspend { performPointerTap(x, y, duration = 60) },
            "方案B：加长单击" to suspend { performPointerTap(x, y, duration = 120) },
            "方案C：长按手势" to suspend { performPointerLongPress(x, y) },
            "方案D：双击手势" to suspend { performPointerDoubleTap(x, y) },
            "方案E：轻微偏移单击" to suspend { performPointerTap(x + 8f, y + 6f, duration = 60) }
        )
        attempts.forEach { (desc, action) ->
            overlayLogging(desc)
            if (action() && waitForWeiboPopup()) {
                overlayLogging("$desc 成功触发弹窗")
                return true
            } else {
                overlayLogging("$desc 未检测到弹窗")
            }
        }
        overlayLogging("所有坐标方案执行完成，仍未检测到弹窗")
        return false
    }

    private suspend fun performPointerTap(x: Float, y: Float, duration: Long): Boolean {
        return runCatching {
            overlayLogging("执行坐标点击: x=$x, y=$y")
            showClickEffect(x, y, "点击")
            runMain { AssistsWindowManager.nonTouchableByAll() }
            delay(120)
            overlayLogging("调用gestureClick前坐标: x=$x, y=$y")
            val result = AssistsCore.gestureClick(x, y, duration)
            delay(120)
            runMain { AssistsWindowManager.touchableByAll() }
            result
        }.getOrDefault(false)
    }

    private suspend fun performPointerLongPress(x: Float, y: Float): Boolean {
        return runCatching {
            showClickEffect(x, y, "长按")
            runMain { AssistsWindowManager.nonTouchableByAll() }
            delay(120)
            val result = AssistsCore.longPressByGesture(x, y, duration = 250)
            delay(120)
            runMain { AssistsWindowManager.touchableByAll() }
            result
        }.getOrDefault(false)
    }

    private suspend fun performPointerDoubleTap(x: Float, y: Float): Boolean {
        return runCatching {
            showClickEffect(x, y, "双击")
            runMain { AssistsWindowManager.nonTouchableByAll() }
            delay(120)
            val first = AssistsCore.gestureClick(x, y, duration = 45)
            delay(120)
            showClickEffect(x, y, "双击-2")
            val second = AssistsCore.gestureClick(x, y, duration = 45)
            delay(120)
            runMain { AssistsWindowManager.touchableByAll() }
            first && second
        }.getOrDefault(false)
    }

    private suspend fun waitForWeiboPopup(timeoutMs: Long = 2000L): Boolean {
        var elapsed = 0L
        val step = 200L
        while (elapsed <= timeoutMs) {
            if (AssistsCore.findByText("写微博").isNotEmpty() ||
                AssistsCore.findByText("图片").isNotEmpty() ||
                AssistsCore.findByText("签到/点评").isNotEmpty()
            ) {
                return true
            }
            delay(step)
            elapsed += step
        }
        return false
    }

    private suspend fun AccessibilityNodeInfo.smartGestureClick(): Boolean {
        showClickEffect(this, "节点点击")
        delay(100)
        if (clickSelfOrParent()) return true
        val bounds = getBoundsInScreen()
        showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "手势点击")
        delay(100)
        return nodeGestureClick()
    }

    private fun AccessibilityNodeInfo.clickSelfOrParent(): Boolean {
        if (isClickable && click()) {
            overlayLogging("节点直接点击成功")
            return true
        }
        if (findFirstParentClickable()?.click() == true) {
            overlayLogging("通过父节点点击成功")
            return true
        }
        return false
    }

    private fun overlayLogging(message: String) {
        message.overlayToast()
        LogWrapper.logAppend("[OverlayBasic] $message")
    }

    /**
     * 选中图片中的第二张和第三张图片
     */
    private suspend fun selectFirstRowImages(): Boolean {
        overlayLogging("开始查找图片，准备选中第二张和第三张")
        var selectedCount = 0
        val targetIndices = listOf(1, 2) // 第二张（索引1）和第三张（索引2）
        
        // 尝试查找RecyclerView
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        recyclerViews.addAll(AssistsCore.findByTags("android.support.v7.widget.RecyclerView"))
        recyclerViews.addAll(AssistsCore.findByTags("androidx.recyclerview.widget.RecyclerView"))
        
        if (recyclerViews.isEmpty()) {
            overlayLogging("未找到RecyclerView，尝试其他方式")
            // 尝试通过图片节点直接查找
            return selectImagesByDirectSearch(2, targetIndices)
        }
        
        for (recyclerView in recyclerViews) {
            overlayLogging("找到RecyclerView，开始遍历子项")
            val allImages = mutableListOf<AccessibilityNodeInfo>()
            
            // 遍历RecyclerView的所有子项，收集所有图片
            for (index in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(index) ?: continue
                
                // 查找包含图片的布局（可能是RelativeLayout、FrameLayout等）
                val imageContainer = findImageContainer(child)
                if (imageContainer != null) {
                    allImages.add(imageContainer)
                }
            }
            
            overlayLogging("共找到${allImages.size}张图片，准备选中第2张和第3张")
            
            // 选中第二张和第三张图片（索引1和2）
            for (imageIndex in targetIndices) {
                if (imageIndex < allImages.size) {
                    val container = allImages[imageIndex]
                    val displayIndex = imageIndex + 1 // 显示用的索引（从1开始）
                    
                    overlayLogging("尝试选中第${displayIndex}张图片")
                    val bounds = container.getBoundsInScreen()
                    showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "选中图片${displayIndex}")
                    delay(200)
                    
                    // 尝试多种方式选中图片
                    val selected = trySelectImage(container)
                    if (selected) {
                        selectedCount++
                        overlayLogging("成功选中第${displayIndex}张图片")
                    } else {
                        overlayLogging("未能选中第${displayIndex}张图片")
                    }
                    delay(300)
                } else {
                    overlayLogging("图片索引${imageIndex + 1}不存在（总共只有${allImages.size}张）")
                }
            }
            
            // 如果找到图片就退出循环
            if (allImages.isNotEmpty()) break
        }
        
        if (selectedCount >= targetIndices.size) {
            overlayLogging("已成功选中${selectedCount}张图片（第2张和第3张）")
            return true
        } else if (selectedCount > 0) {
            overlayLogging("仅选中${selectedCount}张图片，尝试其他方式")
            return selectImagesByDirectSearch(2, targetIndices)
        }
        
        return false
    }
    
    /**
     * 在节点中查找包含图片的容器
     */
    private fun findImageContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 查找ImageView
        val imageViews = node.findByTags("android.widget.ImageView")
        if (imageViews.isNotEmpty()) {
            // 返回包含ImageView的父容器
            return imageViews.first().parent?.takeIf { 
                it.className?.contains("Layout") == true 
            } ?: imageViews.first().parent
        }
        
        // 查找可点击的容器
        if (node.isClickable && (node.className?.contains("Layout") == true || 
            node.contentDescription?.contains("图片") == true)) {
            return node
        }
        
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findImageContainer(child)?.let { return it }
            }
        }
        
        return null
    }
    
    /**
     * 尝试选中图片（多种方式）
     */
    private suspend fun trySelectImage(container: AccessibilityNodeInfo): Boolean {
        // 方式1: 查找并点击CheckBox
        val checkBoxes = container.findByTags("android.widget.CheckBox")
        checkBoxes.forEach { checkbox ->
            if (!checkbox.isChecked) {
                showClickEffect(checkbox, "点击CheckBox")
                delay(100)
                if (checkbox.click() || checkbox.findFirstParentClickable()?.click() == true) {
                    return true
                }
            } else {
                return true // 已经选中
            }
        }
        
        // 方式2: 查找ImageView并点击其父容器
        val imageViews = container.findByTags("android.widget.ImageView")
        imageViews.forEach { imageView ->
            imageView.findFirstParentClickable()?.let { parent ->
                showClickEffect(parent, "点击图片容器")
                delay(100)
                if (parent.click()) {
                    return true
                }
            }
        }
        
        // 方式3: 直接点击容器本身
        if (container.isClickable) {
            showClickEffect(container, "直接点击容器")
            delay(100)
            if (container.click()) {
                return true
            }
        }
        
        // 方式4: 通过坐标手势点击
        val bounds = container.getBoundsInScreen()
        showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "手势点击")
        delay(100)
        return container.nodeGestureClick()
    }
    
    /**
     * 通过直接搜索方式选中图片
     */
    private suspend fun selectImagesByDirectSearch(targetCount: Int, targetIndices: List<Int>? = null): Boolean {
        overlayLogging("使用直接搜索方式查找图片")
        val allNodes = AssistsCore.getAllNodes()
        val imageNodes = allNodes.filter { node ->
            node.className?.contains("Image", ignoreCase = true) == true ||
            node.contentDescription?.contains("图片") == true ||
            node.contentDescription?.contains("photo") == true
        }
        
        // 按Y坐标和X坐标排序，获取所有可见图片
        val allVisibleImages = imageNodes
            .filter { it.isVisibleToUser }
            .sortedWith(compareBy(
                { it.getBoundsInScreen().top }, // 先按Y坐标排序
                { it.getBoundsInScreen().left }  // 再按X坐标排序
            ))
        
        overlayLogging("共找到${allVisibleImages.size}张可见图片")
        
        // 确定要选择的图片索引
        val indicesToSelect = targetIndices ?: (0 until targetCount).toList()
        
        var selectedCount = 0
        indicesToSelect.forEach { imageIndex ->
            if (imageIndex < allVisibleImages.size) {
                val node = allVisibleImages[imageIndex]
                val displayIndex = imageIndex + 1 // 显示用的索引（从1开始）
                
                overlayLogging("尝试选中第${displayIndex}张图片")
                val bounds = node.getBoundsInScreen()
                showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "选中图片${displayIndex}")
                delay(200)
                
                if (trySelectImage(node)) {
                    selectedCount++
                    overlayLogging("成功选中第${displayIndex}张图片")
                } else {
                    overlayLogging("未能选中第${displayIndex}张图片")
                }
                delay(300)
            } else {
                overlayLogging("图片索引${imageIndex + 1}不存在（总共只有${allVisibleImages.size}张）")
            }
        }
        
        return selectedCount >= indicesToSelect.size
    }

    /**
     * 微博相册选择逻辑：选中第二张和第三张图片
     * UI结构: RecyclerView -> FrameLayout -> TextView(checkbox)
     * 根据用户描述：在RecyclerView根节点下遍历获取FrameLayout，再点击FrameLayout下的TextView来选中图片
     */
    private suspend fun selectWeiboImages(): Boolean {
        overlayLogging("开始查找微博相册图片，准备选中第二张和第三张")
        var selectedCount = 0
        val targetIndices = listOf(1, 2) // 第二张（索引1）和第三张（索引2）
        
        // 1. 查找RecyclerView根节点
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        recyclerViews.addAll(AssistsCore.findByTags("android.support.v7.widget.RecyclerView"))
        recyclerViews.addAll(AssistsCore.findByTags("androidx.recyclerview.widget.RecyclerView"))
        
        if (recyclerViews.isEmpty()) {
            overlayLogging("未找到RecyclerView")
            return false
        }
        
        // 2. 遍历每个RecyclerView
        for (recyclerView in recyclerViews) {
            overlayLogging("找到RecyclerView，开始遍历FrameLayout子项")
            val allFrameLayouts = mutableListOf<AccessibilityNodeInfo>()
            
            // 3. 遍历RecyclerView的所有子项，收集所有FrameLayout
            for (index in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(index) ?: continue
                
                // ⭐ 关键：检查是否为FrameLayout
                if (TextUtils.equals("android.widget.FrameLayout", child.className)) {
                    allFrameLayouts.add(child)
                }
            }
            
            overlayLogging("共找到${allFrameLayouts.size}个FrameLayout，准备选中第2张和第3张")
            
            // 4. 选中指定索引的图片（第二张和第三张）
            for (targetIndex in targetIndices) {
                if (targetIndex < allFrameLayouts.size) {
                    val frameLayout = allFrameLayouts[targetIndex]
                    val displayIndex = targetIndex + 1 // 显示用的索引（从1开始）
                    
                    overlayLogging("尝试选中第${displayIndex}张图片")
                    val bounds = frameLayout.getBoundsInScreen()
                    showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "选中图片${displayIndex}")
                    delay(200)
                    
                    // 5. 在FrameLayout中查找TextView（checkbox）并点击
                    val selected = trySelectWeiboImage(frameLayout)
                    if (selected) {
                        selectedCount++
                        overlayLogging("成功选中第${displayIndex}张图片")
                    } else {
                        overlayLogging("未能选中第${displayIndex}张图片")
                    }
                    delay(300)
                } else {
                    overlayLogging("图片索引${targetIndex + 1}不存在（总共只有${allFrameLayouts.size}张）")
                }
            }
            
            // 如果找到FrameLayout就退出循环
            if (allFrameLayouts.isNotEmpty()) break
        }
        
        if (selectedCount >= targetIndices.size) {
            overlayLogging("已成功选中${selectedCount}张图片（第2张和第3张）")
            return true
        }
        
        return false
    }

    /**
     * 尝试选中微博图片：在FrameLayout中查找并点击TextView（checkbox）
     * TextView作为checkbox使用，点击它来选中图片
     */
    private suspend fun trySelectWeiboImage(frameLayout: AccessibilityNodeInfo): Boolean {
        // 方式1: 在FrameLayout中查找并点击TextView（checkbox）
        val textViews = frameLayout.findByTags("android.widget.TextView")
        textViews.forEach { textView ->
            // TextView作为checkbox使用，点击它来选中图片
            showClickEffect(textView, "点击TextView(checkbox)")
            delay(100)
            
            // 尝试直接点击TextView
            if (textView.isClickable && textView.click()) {
                return true
            }
            
            // 如果TextView不可点击，尝试点击其父节点
            textView.findFirstParentClickable()?.let { parent ->
                if (parent.click()) {
                    return true
                }
            }
        }
        
        // 方式2: 如果TextView点击失败，尝试点击FrameLayout本身
        if (frameLayout.isClickable) {
            showClickEffect(frameLayout, "点击FrameLayout容器")
            delay(100)
            if (frameLayout.click()) {
                return true
            }
        }
        
        // 方式3: 通过坐标手势点击
        val bounds = frameLayout.getBoundsInScreen()
        showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "手势点击")
        delay(100)
        return frameLayout.nodeGestureClick()
    }

    /**
     * 显示点击特效在指定坐标
     * @param x 点击X坐标
     * @param y 点击Y坐标
     * @param label 可选的标签文本
     */
    private suspend fun showClickEffect(x: Float, y: Float, label: String = "") {
        AssistsService.instance?.let { service ->
            runMain {
                val effectView = ClickEffectView(service, x, y, label)
                val layoutParams = AssistsWindowManager.createLayoutParams().apply {
                    width = ScreenUtils.getScreenWidth()
                    height = ScreenUtils.getScreenHeight()
                }
                AssistsWindowManager.add(effectView, layoutParams, isTouchable = false)
                CoroutineWrapper.launch {
                    delay(800) // 显示800ms后自动移除
                    runMain {
                        AssistsWindowManager.removeWindow(effectView)
                    }
                }
            }
        }
    }

    /**
     * 显示点击特效在节点位置
     */
    private suspend fun showClickEffect(node: AccessibilityNodeInfo, label: String = "") {
        val bounds = node.getBoundsInScreen()
        showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), label)
    }

    /**
     * 点击特效View - 显示圆形波纹和坐标信息
     */
    private class ClickEffectView(
        context: Context,
        private val clickX: Float,
        private val clickY: Float,
        private val label: String
    ) : View(context) {

        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF4081")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            alpha = 200
        }

        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF4081")
            style = Paint.Style.FILL
            alpha = 255
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SizeUtils.sp2px(12f).toFloat()
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val startTime = System.currentTimeMillis()
        private val animationDuration = 800L
        private var rippleRadius = 0f

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > animationDuration) {
                return
            }

            val progress = elapsed.toFloat() / animationDuration

            // 绘制中心点（固定）
            canvas.drawCircle(clickX, clickY, 15f, centerPaint)

            // 绘制波纹圆环（向外扩散并淡出）
            val maxRadius = 80f
            rippleRadius = maxRadius * progress
            ripplePaint.alpha = ((1f - progress) * 200).toInt()
            
            if (progress < 1f) {
                canvas.drawCircle(clickX, clickY, rippleRadius, ripplePaint)
                
                // 绘制第二层波纹（延迟出现）
                if (progress > 0.3f) {
                    val progress2 = (progress - 0.3f) / 0.7f
                    val radius2 = maxRadius * progress2
                    ripplePaint.alpha = ((1f - progress2) * 150).toInt()
                    canvas.drawCircle(clickX, clickY, radius2, ripplePaint)
                }
            }

            // 绘制坐标和标签文本（带背景）
            val textY = clickY - 35f
            val padding = 8f
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#80000000")
                style = Paint.Style.FILL
            }
            
            if (label.isNotEmpty()) {
                val textWidth = textPaint.measureText(label)
                canvas.drawRect(
                    clickX - textWidth / 2 - padding,
                    textY - textPaint.textSize - padding,
                    clickX + textWidth / 2 + padding,
                    textY + padding,
                    bgPaint
                )
                canvas.drawText(label, clickX, textY, textPaint)
            }
            
            val coordText = "(${clickX.toInt()}, ${clickY.toInt()})"
            val coordWidth = textPaint.measureText(coordText)
            val coordY = textY + textPaint.textSize + 8
            canvas.drawRect(
                clickX - coordWidth / 2 - padding,
                coordY - textPaint.textSize - padding,
                clickX + coordWidth / 2 + padding,
                coordY + padding,
                bgPaint
            )
            canvas.drawText(coordText, clickX, coordY, textPaint)
            
            // 持续重绘以更新动画
            if (progress < 1f) {
                invalidate()
            }
        }
    }
}