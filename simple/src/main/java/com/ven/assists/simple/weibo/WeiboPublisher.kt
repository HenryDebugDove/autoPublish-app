package com.ven.assists.simple.weibo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import com.blankj.utilcode.util.BarUtils
import com.blankj.utilcode.util.ScreenUtils
import com.ven.assists.AssistsCore
import com.ven.assists.AssistsCore.click
import com.ven.assists.AssistsCore.findByTags
import com.ven.assists.AssistsCore.findByText
import com.ven.assists.AssistsCore.findFirstParentClickable
import com.ven.assists.AssistsCore.getAllNodes
import com.ven.assists.AssistsCore.getBoundsInScreen
import com.ven.assists.AssistsCore.gestureClick
import com.ven.assists.AssistsCore.longPressByGesture
import com.ven.assists.AssistsCore.nodeGestureClick
import com.ven.assists.AssistsCore.paste
import com.ven.assists.AssistsCore.setNodeText
import com.ven.assists.service.AssistsService

import com.ven.assists.utils.FileDownloadUtil
import com.ven.assists.utils.FileDownloadUtil.DownloadResult
import com.ven.assists.window.AssistsWindowManager
import com.ven.assists.window.AssistsWindowManager.nonTouchableByAll
import com.ven.assists.window.AssistsWindowManager.touchableByAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 微博自动化发布流程，抽离为可复用组件
 */
object WeiboPublisher {

    private const val SERVER_BASE_URL = "http://192.168.89.192:4001"

    /**
     * 微博文案尾部标签，可在外部进行配置。
     * 例如：#前端收徒#、#日常记录# 等
     */
//    var tailTag: String = "#前端收徒# #前端教学# #前端入门# #程序员#"
      var tailTag: String = "#python收徒# #python教学# #python入门# #程序员#"
        @Synchronized get
        @Synchronized set

    private val tailTagWithSpace: String
        get() = " $tailTag"

    private const val TEMP_SUB_DIR = "weibo_album_temp"
    private var lastCreatedAlbumName: String? = null
    private val savedImageUris = mutableListOf<Uri>()
    
    // 从接口动态获取的图片路径列表
    private var remoteWeiboImagePaths: List<String>? = null

    private val httpClient = OkHttpClient()

    data class Context(
        val log: (String) -> Unit,
        val showNodeEffect: suspend (AccessibilityNodeInfo, String) -> Unit,
        val showPointEffect: suspend (Float, Float, String) -> Unit
    )

    private const val IMAGE_BASE_URL = "https://yxx-1251927313.image.myqcloud.com"
    
    private fun getWeiboImagePaths(): List<String> {
        // 优先使用从接口获取的路径
        remoteWeiboImagePaths?.let { paths ->
            if (paths.isNotEmpty()) {
                android.util.Log.d("WeiboPublisher", "使用接口返回的 ${paths.size} 条图片路径")
                return paths
            }
        }
        android.util.Log.w("WeiboPublisher", "未从接口获取到图片路径")
        return emptyList()
    }
    
    private val WEIBO_IMAGE_PATHS: List<String>
        get() = getWeiboImagePaths()
    
    private val WEIBO_IMAGE_URLS: List<String>
        get() = WEIBO_IMAGE_PATHS.map { "$IMAGE_BASE_URL/$it" }

    data class RemoteConfig(
        val remoteTailTag: String,
        val contentTemplates: List<String>,
        val weiboImagePaths: List<String>
    )

    private suspend fun fetchRemoteConfig(log: (String) -> Unit): RemoteConfig? {
        return try {
            withContext(Dispatchers.IO) {
                log("开始请求控制面板配置: ${SERVER_BASE_URL}/api/config")
                val request = Request.Builder()
                    .url("${SERVER_BASE_URL}/api/config")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log("❌ 拉取控制面板配置失败: HTTP ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string().orEmpty()
                    log("接口响应: ${body.take(200)}...")
                    if (body.isBlank()) {
                        log("❌ 控制面板配置响应为空")
                        return@withContext null
                    }
                    val json = JSONObject(body)
                    val remoteTail = json.optString("tailTag")
                    
                    // 解析 contentTemplates 数组
                    val contentTemplates = mutableListOf<String>()
                    json.optJSONArray("contentTemplates")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            contentTemplates.add(arr.getString(i))
                        }
                    }
                    
                    val imagePaths = mutableListOf<String>()
                    json.optJSONArray("weiboImagePaths")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            imagePaths.add(arr.getString(i))
                        }
                    }
                    if (remoteTail.isBlank() || contentTemplates.isEmpty()) {
                        log("⚠️ 控制面板返回的配置不完整")
                    } else {
                        log("✅ 已从控制面板获取配置，文案数: ${contentTemplates.size}，图片路径数: ${imagePaths.size}")
                    }
                    RemoteConfig(remoteTail, contentTemplates, imagePaths)
                }
            }
        } catch (e: Exception) {
            log("❌ 拉取控制面板配置异常: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun copyContentToClipboard(text: String, log: (String) -> Unit) {
        val service = AssistsService.instance ?: run {
            log("⚠️ AssistsService 未初始化，无法写入剪贴板")
            return
        }
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            log("⚠️ 无法获取剪贴板服务")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("weibo_content", text))
        log("✅ 已将控制面板配置的文本写入剪贴板")
    }

    suspend fun publish(context: Context) = with(context) {
        // 启动时优先从控制面板后端获取 tailTag 和文案列表
        val remoteConfig = fetchRemoteConfig(log)
        if (remoteConfig == null) {
            log("⚠️ 未能从控制面板获取配置，终止流程")
            return
        }
        
        if (remoteConfig.remoteTailTag.isNotBlank()) {
            tailTag = remoteConfig.remoteTailTag
            log("已从控制面板更新 tailTag：$tailTag")
        }
        
        if (remoteConfig.contentTemplates.isEmpty()) {
            log("❌ 控制面板返回的 contentTemplates 为空，终止流程")
            return
        }
        
        if (remoteConfig.weiboImagePaths.isNotEmpty()) {
            remoteWeiboImagePaths = remoteConfig.weiboImagePaths
            log("✅ 已从控制面板获取 ${remoteConfig.weiboImagePaths.size} 条图片路径")
        } else {
            log("⚠️ 控制面板返回的 weiboImagePaths 为空")
        }
        
        // 校验图片路径是否已获取
        if (remoteWeiboImagePaths.isNullOrEmpty()) {
            log("❌ 图片路径未获取成功(remoteWeiboImagePaths为空)，终止流程")
            return
        }
        
        val totalCount = remoteConfig.contentTemplates.size
        log("共有 $totalCount 条文案需要发布")
        
        // 循环发布每条文案
        remoteConfig.contentTemplates.forEachIndexed { index, contentTemplate ->
            log("========== 开始发布第 ${index + 1}/$totalCount 条文案 ==========")
            log("文案内容: ${contentTemplate.take(50)}...")
            
            // 将当前文案写入剪贴板
            copyContentToClipboard(contentTemplate, log)
            
            // 执行单次发布流程
            val success = publishSingle(context)
            
            if (success) {
                log("✅ 第 ${index + 1}/$totalCount 条文案发布成功")
            } else {
                log("❌ 第 ${index + 1}/$totalCount 条文案发布失败")
            }
            
            // 如果不是最后一条，等待30秒后继续下一条
            if (index < totalCount - 1) {
                log("等待 20 秒后发布下一条文案...")
                delay(20000)
            }
        }
        
        log("========== 所有 $totalCount 条文案发布完成 ==========")
    }
    
    /**
     * 单次发布流程
     */
    private suspend fun publishSingle(context: Context): Boolean = with(context) {
        if (!prepareWeiboAlbumImages(log)) {
            log("❌ 初始化微博素材图片失败")
            return false
        }
        log("开始执行微博流程")
        if (!clickTextWithRetry("首页")) {
            log("未找到“首页”入口")
            return false
        }
        delay(600)
        if (!clickWeiboAddEntry()) {
            log("未找到微博加号入口")
            return false
        }
        delay(500)
        if (!clickTextWithRetry("图片")) {
            log("未找到\"图片\"入口")
            return false
        }
        log("已打开图片，等待加载")
        delay(1500)
        if (!selectWeiboImages()) {
            log("未能选中图片")
            return false
        }
        delay(500)
        if (!clickTextWithRetry("下一步")) {
            log("未找到\"下一步\"按钮")
            return false
        }
        log("已点击第一步的下一步，等待页面加载")
        delay(2000)
        log("准备点击第二步的下一步")
        if (!clickTextWithRetry("下一步")) {
            log("未找到第二步的\"下一步\"按钮")
            return false
        }
        log("已点击第二步的下一步，进入发布页面")
        delay(1000)
        return handlePublishPageWithResult()
    }

    private suspend fun Context.handlePublishPageWithResult(): Boolean {
        log("开始处理：先获取输入框焦点，再读取剪切板")
        val inputEditText = findPublishEditText()
        if (inputEditText == null) {
            log("❌ 未找到输入框，无法继续")
            return false
        }

        log("步骤2: 点击输入框获得焦点")
        val bounds = inputEditText.getBoundsInScreen()
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        showClickEffect(centerX, centerY, "点击输入框获取焦点")
        if (inputEditText.isClickable) {
            inputEditText.click()
        } else {
            inputEditText.findFirstParentClickable()?.click()
        }
        inputEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        AssistsCore.gestureClick(centerX, centerY)

        log("等待输入框获得焦点并稳定...")
        delay(800)

        log("步骤3: 记录粘贴前的输入框状态")
        val beforeText = if (inputEditText.refresh()) inputEditText.text?.toString() ?: "" else ""
        val beforeLength = beforeText.length
        log("粘贴前输入框文本长度: $beforeLength")

        log("步骤4: 执行粘贴操作")
        var pasteAttempted = false
        log("方式1: 尝试 ACTION_PASTE")
        inputEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(300)
        val pasteSuccess = inputEditText.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        log("ACTION_PASTE 执行结果: $pasteSuccess")
        if (pasteSuccess) {
            pasteAttempted = true
        } else {
            log("方式2: ACTION_PASTE 失败，尝试点击粘贴按钮")
            delay(300)
            var pasteButtonClicked = false
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
            if (!pasteButtonClicked) {
                val bounds2 = inputEditText.getBoundsInScreen()
                val pasteX = bounds2.centerX().toFloat()
                val pasteY = bounds2.bottom + 80f
                showClickEffect(pasteX, pasteY, "坐标点击粘贴按钮")
                delay(100)
                if (AssistsCore.gestureClick(pasteX, pasteY)) {
                    pasteAttempted = true
                }
            }
        }

        if (pasteAttempted) {
            log("粘贴操作已执行，等待粘贴完成...")
        } else {
            log("⚠️ 所有粘贴方式都失败，但仍会尝试检测文本变化")
        }
        delay(800)

        log("步骤5: 在输入框末尾添加标签 $tailTag")
        delay(500)
        
        // 刷新输入框，读取粘贴后的内容（这就是剪切板的内容）
        val currentEditText = if (inputEditText.refresh()) inputEditText else findPublishEditText()
        if (currentEditText == null) {
            log("未找到输入框，无法添加标签")
            return false
        }
        
        val currentText = if (currentEditText.refresh()) {
            currentEditText.text?.toString() ?: ""
        } else {
            ""
        }
        log("当前输入框内容长度: ${currentText.length}")
        
        if (currentText.isNotEmpty() && !currentText.endsWith(tailTag)) {
            // 拼接完整文本（在末尾添加标签）
            val finalText = currentText.trimEnd() + tailTagWithSpace
            log("准备添加标签，最终文本: ${finalText.take(50)}...")
            
            // 先点击输入框末尾位置
            val bounds2 = currentEditText.getBoundsInScreen()
            val endX = bounds2.right - 20f
            val centerY2 = bounds2.centerY().toFloat()
            
            // 点击输入框末尾以定位光标
            AssistsCore.gestureClick(endX, centerY2)
            delay(300)
            
            // 使用 setNodeText 设置完整文本
            if (currentEditText.refresh() && currentEditText.setNodeText(finalText)) {
                log("✅ 标签添加成功（setNodeText）")
            } else {
                // 备选方案：直接粘贴标签
                log("setNodeText失败，尝试粘贴标签")
                delay(300)
                if (currentEditText.refresh()) {
                    currentEditText.paste(tailTagWithSpace)
                }
            }
        } else if (currentText.endsWith(tailTag)) {
            log("标签已存在，无需添加")
        } else {
            log("输入框为空，直接设置标签")
            if (currentEditText.refresh()) {
                currentEditText.setNodeText(tailTag)
            }
        }

        log("步骤6: 查找并点击发送按钮")
//        delay(500)
        log("等待3秒后再点击发送按钮...")
        delay(5000)
        return if (clickSendButton()) {
            log("✅ 微博发布流程完成")
            cleanupWeiboAlbumResources(log)
            true
        } else {
            log("❌ 未找到发送按钮")
            false
        }
    }

    private suspend fun prepareWeiboAlbumImages(log: (String) -> Unit): Boolean {
        lastCreatedAlbumName = null
        savedImageUris.clear()
        val service = AssistsService.instance ?: run {
            log("❌ AssistsService 未初始化，无法下载微博图片")
            return false
        }
        val albumName = generateRandomAlbumName()
        log("准备下载微博素材图片，目标相册：$albumName")
        val tempFiles = mutableListOf<File>()
        
        // 校验图片URL列表
        val allUrls = WEIBO_IMAGE_URLS
        if (allUrls.isEmpty()) {
            log("❌ 图片URL列表为空，无法下载")
            return false
        }
        log("可用图片URL数量: ${allUrls.size}")
        
        val selectedUrls = allUrls.shuffled().take(2)
        if (selectedUrls.isEmpty()) {
            log("❌ 未选中任何图片进行下载")
            return false
        }
        log("本次将下载 ${selectedUrls.size} 张随机素材: ${selectedUrls.joinToString(", ")}")
        try {
            selectedUrls.forEachIndexed { index, url ->
                when (val result = FileDownloadUtil.downloadFile(
                    service,
                    url,
                    fileName = "weibo_source_${index + 1}_${System.currentTimeMillis()}.jpg",
                    subDir = TEMP_SUB_DIR
                )) {
                    is DownloadResult.Success -> {
                        tempFiles += result.file
                        log("图片${index + 1} 下载完成")
                    }
                    is DownloadResult.Error -> {
                        log("❌ 图片${index + 1} 下载失败: ${result.exception.message}")
                        return false
                    }
                    else -> Unit
                }
            }

            val savedCount = tempFiles.mapIndexed { index, file ->
                if (saveImageToAlbum(service, file, albumName, index)) 1 else 0
            }.sum()

            return if (savedCount == selectedUrls.size) {
                lastCreatedAlbumName = albumName
                log("✅ 已创建相册：$albumName，并保存所有素材图片")
                true
            } else {
                lastCreatedAlbumName = null
                log("❌ 仅成功保存 $savedCount/${selectedUrls.size} 张图片到相册 $albumName")
                false
            }
        } catch (e: Exception) {
            log("❌ 下载微博素材图片时发生异常: ${e.message}")
            return false
        } finally {
            tempFiles.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private fun saveImageToAlbum(
        context: android.content.Context,
        sourceFile: File,
        albumName: String,
        index: Int
    ): Boolean {
        val resolver = context.contentResolver
        val fileName = "weibo_${index + 1}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + albumName
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val albumDir = File(picturesDir, albumName).apply {
                    if (!exists()) mkdirs()
                }
                val destFile = File(albumDir, fileName)
                put(MediaStore.Images.Media.DATA, destFile.absolutePath)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, pendingValues, null, null)
            }
            savedImageUris += uri
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun cleanupWeiboAlbumResources(log: (String) -> Unit) {
        val service = AssistsService.instance ?: run {
            log("⚠️ AssistsService 未初始化，无法清理相册资源")
            return
        }
        val resolver = service.contentResolver
        if (savedImageUris.isEmpty()) {
            log("未记录到需要清理的图片Uri")
        } else {
            savedImageUris.forEach { uri ->
                runCatching { resolver.delete(uri, null, null) }
            }
            log("已删除 ${savedImageUris.size} 张素材图片")
            savedImageUris.clear()
        }
        lastCreatedAlbumName?.let { album ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_PICTURES + File.separator + album
                runCatching {
                    resolver.delete(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                        arrayOf(relativePath)
                    )
                }.onSuccess { log("已清理相册(RELATIVE_PATH): $album") }
                    .onFailure { log("⚠️ 删除相册(RELATIVE_PATH)失败: ${it.message}") }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val albumDir = File(picturesDir, album)
                if (albumDir.exists()) {
                    runCatching { albumDir.deleteRecursively() }
                        .onFailure { log("⚠️ 删除相册目录失败: ${it.message}") }
                        .onSuccess { log("已删除相册目录: $album") }
                } else {
                    log("未找到需要删除的相册目录: $album")
                }
            }
        }
        lastCreatedAlbumName = null
    }

    private fun generateRandomAlbumName(): String {
        val datePart = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val randomPart = UUID.randomUUID().toString().take(6)
        return "WeiboAlbum_${datePart}_$randomPart"
    }

    private suspend fun Context.findPublishEditText(): AccessibilityNodeInfo? {
        var inputEditText: AccessibilityNodeInfo? = null
        AssistsCore.findByTags("android.widget.ScrollView").forEach { scrollView ->
            scrollView.findByTags("android.widget.EditText").forEach { editText ->
                val editTextValue = editText.text?.toString() ?: ""
                val contentDesc = editText.contentDescription?.toString() ?: ""
                if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事")) {
                    inputEditText = editText
                    return@forEach
                }
            }
            if (inputEditText != null) return@forEach
        }
        if (inputEditText == null) {
            AssistsCore.findByTags("android.widget.EditText").forEach { editText ->
                val editTextValue = editText.text?.toString() ?: ""
                val contentDesc = editText.contentDescription?.toString() ?: ""
                if (editTextValue.contains("分享新鲜事") || contentDesc.contains("分享新鲜事")) {
                    inputEditText = editText
                    return@forEach
                }
            }
        }
        return inputEditText
    }

    private suspend fun Context.clickSendButton(): Boolean {
        var sendButtonFound = false
        AssistsCore.findByTags("android.widget.LinearLayout").forEach { linearLayout ->
            linearLayout.findByTags("android.widget.TextView").forEach { textView ->
                val text = textView.text?.toString() ?: ""
                if (text == "发送") {
                    log("找到发送按钮，准备点击")
                    textView.findFirstParentClickable()?.let { parent ->
                        if (parent.click()) {
                            log("发送按钮点击成功")
                            sendButtonFound = true
                            return@forEach
                        }
                    }
                    if (!sendButtonFound && textView.isClickable && textView.click()) {
                        log("发送按钮点击成功（直接点击）")
                        sendButtonFound = true
                        return@forEach
                    }
                }
            }
            if (sendButtonFound) return@forEach
        }

        if (!sendButtonFound) {
            AssistsCore.findByText("发送").forEach { textView ->
                log("通过文本查找找到发送按钮")
                textView.findFirstParentClickable()?.let { parent ->
                    if (parent.click()) {
                        log("发送按钮点击成功")
                        sendButtonFound = true
                        return@forEach
                    }
                }
                if (!sendButtonFound && textView.isClickable && textView.click()) {
                    log("发送按钮点击成功（直接点击）")
                    sendButtonFound = true
                }
            }
        }
        return sendButtonFound
    }

    private suspend fun Context.clickTextWithRetry(
        text: String,
        attempts: Int = 5,
        interval: Long = 500L
    ): Boolean {
        repeat(attempts) {
            findByText(text).firstOrNull()?.let { node ->
                showClickEffect(node, "点击: $text")
                delay(100)
                if (node.clickSelfOrParent()) {
                    log("点击文本\"$text\"成功")
                    return true
                }
            }
            delay(interval)
        }
        return false
    }

    private suspend fun Context.clickWeiboAddEntry(): Boolean {
        log("直接执行硬编码坐标点击")
        return clickWeiboAddByCoordinate()
    }

    private suspend fun Context.tryClickWeiboAddByNodeMatch(): Boolean {
        val keywords = listOf("发布", "发微博", "写微博", "加号", "更多", "创建", "compose")
        val nodes = getAllNodes()
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
            if (node.smartGestureClick(this)) {
                log("节点匹配命中，已尝试点击")
                return true
            }
        }
        return false
    }

    private suspend fun Context.tryClickWeiboAddByLikelyRegion(): Boolean {
        val nodes = getAllNodes()
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
                if (node.smartGestureClick(this)) {
                    log("区域内 ImageView 命中，已尝试点击")
                    return true
                }
            }
        return false
    }

    private suspend fun Context.clickWeiboAddByCoordinate(): Boolean {
        val x = 1200f
        val y = 150f
        log("准备使用坐标点击: x=$x, y=$y")
        val attempts: List<Pair<String, suspend () -> Boolean>> = listOf(
            "方案A：标准单击" to suspend { performPointerTap(x, y, duration = 60) },
            "方案B：加长单击" to suspend { performPointerTap(x, y, duration = 120) },
            "方案C：长按手势" to suspend { performPointerLongPress(x, y) },
            "方案D：双击手势" to suspend { performPointerDoubleTap(x, y) },
            "方案E：轻微偏移单击" to suspend { performPointerTap(x + 8f, y + 6f, duration = 60) }
        )
        attempts.forEach { (desc, action) ->
            log(desc)
            if (action() && waitForWeiboPopup()) {
                log("$desc 成功触发弹窗")
                return true
            } else {
                log("$desc 未检测到弹窗")
            }
        }
        log("所有坐标方案执行完成，仍未检测到弹窗")
        return false
    }

    private suspend fun Context.performPointerTap(x: Float, y: Float, duration: Long): Boolean {
        return runCatching {
            log("执行坐标点击: x=$x, y=$y")
            showClickEffect(x, y, "点击")
            AssistsWindowManager.nonTouchableByAll()
            delay(120)
            val result = AssistsCore.gestureClick(x, y, duration)
            delay(120)
            AssistsWindowManager.touchableByAll()
            result
        }.getOrDefault(false)
    }

    private suspend fun Context.performPointerLongPress(x: Float, y: Float): Boolean {
        return runCatching {
            showClickEffect(x, y, "长按")
            AssistsWindowManager.nonTouchableByAll()
            delay(120)
            val result = AssistsCore.longPressByGesture(x, y, duration = 250)
            delay(120)
            AssistsWindowManager.touchableByAll()
            result
        }.getOrDefault(false)
    }

    private suspend fun Context.performPointerDoubleTap(x: Float, y: Float): Boolean {
        return runCatching {
            showClickEffect(x, y, "双击")
            AssistsWindowManager.nonTouchableByAll()
            delay(120)
            val first = AssistsCore.gestureClick(x, y, duration = 45)
            delay(120)
            showClickEffect(x, y, "双击-2")
            val second = AssistsCore.gestureClick(x, y, duration = 45)
            delay(120)
            AssistsWindowManager.touchableByAll()
            first && second
        }.getOrDefault(false)
    }

    private suspend fun Context.waitForWeiboPopup(timeoutMs: Long = 2000L): Boolean {
        var elapsed = 0L
        val step = 200L
        while (elapsed <= timeoutMs) {
            if (findByText("写微博").isNotEmpty() ||
                findByText("图片").isNotEmpty() ||
                findByText("签到/点评").isNotEmpty()
            ) {
                return true
            }
            delay(step)
            elapsed += step
        }
        return false
    }

    private suspend fun Context.selectWeiboImages(): Boolean {
        val targetAlbum = lastCreatedAlbumName
        if (targetAlbum.isNullOrEmpty()) {
            log("❌ 未记录到最新创建的相册名称，无法选择图片")
            return false
        }
        log("开始查找微博相册图片，准备打开相册 $targetAlbum")
        if (!openAlbumSelectorEntry("相机胶卷")) {
            log("❌ 未找到“相机胶卷”入口")
            return false
        }
        delay(500)
        if (!chooseAlbumFromDialog(targetAlbum)) {
            log("❌ 相册列表中未找到 $targetAlbum")
            return false
        }
        log("已选择相册 $targetAlbum，准备全选图片")
        delay(800)
        return selectAllImagesInCurrentAlbum()
    }

    private suspend fun Context.openAlbumSelectorEntry(entryText: String): Boolean {
        AssistsCore.findByTags("android.widget.FrameLayout").forEach { frame ->
            frame.findByTags("android.widget.TextView").forEach { textView ->
                val text = textView.text?.toString() ?: ""
                if (text == entryText) {
                    showClickEffect(textView, "切换到$entryText")
                    delay(100)
                    if (frame.click() || textView.findFirstParentClickable()?.click() == true || textView.click()) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private suspend fun Context.chooseAlbumFromDialog(albumName: String): Boolean {
        log("开始查找相册: $albumName")
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        recyclerViews.addAll(findByTags("androidx.recyclerview.widget.RecyclerView"))
        recyclerViews.addAll(findByTags("android.support.v7.widget.RecyclerView"))
        log("找到 ${recyclerViews.size} 个 RecyclerView")
        
        val foundAlbumNames = mutableListOf<String>()
        recyclerViews.forEach { recyclerView ->
            log("RecyclerView 子节点数: ${recyclerView.childCount}")
            for (index in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(index) ?: continue
                if (child.className?.contains("ViewGroup", ignoreCase = true) == true) {
                    child.findByTags("android.widget.TextView").forEach { textView ->
                        val text = textView.text?.toString() ?: ""
                        if (text.isNotEmpty()) {
                            foundAlbumNames.add(text)
                        }
                        if (text == albumName) {
                            log("✅ 找到目标相册: $albumName")
                            showClickEffect(textView, "选择相册 $albumName")
                            delay(100)
                            if (child.click() || textView.findFirstParentClickable()?.click() == true || textView.click()) {
                                return true
                            }
                        }
                    }
                }
            }
        }
        log("相册列表中找到的所有名称: ${foundAlbumNames.joinToString(", ")}")
        return false
    }

    private suspend fun Context.selectAllImagesInCurrentAlbum(): Boolean {
        val recyclerViews = mutableListOf<AccessibilityNodeInfo>()
        recyclerViews.addAll(findByTags("android.support.v7.widget.RecyclerView"))
        recyclerViews.addAll(findByTags("androidx.recyclerview.widget.RecyclerView"))
        var totalImages = 0
        var selectedCount = 0
        if (recyclerViews.isEmpty()) {
            log("❌ 未找到用于展示图片的RecyclerView")
            return false
        }
        for (recyclerView in recyclerViews) {
            val frameLayouts = mutableListOf<AccessibilityNodeInfo>()
            for (index in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(index) ?: continue
                if (TextUtils.equals("android.widget.FrameLayout", child.className)) {
                    frameLayouts.add(child)
                }
            }
            if (frameLayouts.isEmpty()) continue
            totalImages = frameLayouts.size
            log("检测到相册中共有 $totalImages 张图片，开始逐一选中")
            frameLayouts.forEachIndexed { idx, frame ->
                val bounds = frame.getBoundsInScreen()
                showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "选中图片 ${idx + 1}")
                delay(200)
                if (trySelectWeiboImage(frame)) {
                    selectedCount++
                    log("成功选中第${idx + 1}张图片")
                } else {
                    log("未能选中第${idx + 1}张图片")
                }
                delay(300)
            }
            break
        }
        if (totalImages == 0) {
            log("❌ 未读取到可选图片")
            return false
        }
        log("已尝试选中 $selectedCount/$totalImages 张图片")
        return selectedCount == totalImages
    }

    private suspend fun Context.trySelectWeiboImage(frameLayout: AccessibilityNodeInfo): Boolean {
        val textViews = frameLayout.findByTags("android.widget.TextView")
        textViews.forEach { textView ->
            showClickEffect(textView, "点击TextView(checkbox)")
            delay(100)
            if (textView.isClickable && textView.click()) {
                return true
            }
            textView.findFirstParentClickable()?.let { parent ->
                if (parent.click()) {
                    return true
                }
            }
        }

        if (frameLayout.isClickable) {
            showClickEffect(frameLayout, "点击FrameLayout容器")
            delay(100)
            if (frameLayout.click()) {
                return true
            }
        }

        val bounds = frameLayout.getBoundsInScreen()
        showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "手势点击")
        delay(100)
        return frameLayout.nodeGestureClick()
    }

    private suspend fun Context.showClickEffect(node: AccessibilityNodeInfo, label: String = "") {
        showNodeEffect(node, label)
    }

    private suspend fun Context.showClickEffect(x: Float, y: Float, label: String = "") {
        showPointEffect(x, y, label)
    }

    private fun AccessibilityNodeInfo.clickSelfOrParent(): Boolean {
        if (isClickable && click()) {
            return true
        }
        if (findFirstParentClickable()?.click() == true) {
            return true
        }
        return false
    }

    private suspend fun AccessibilityNodeInfo.smartGestureClick(context: Context): Boolean {
        context.showClickEffect(this, "节点点击")
        delay(100)
        if (clickSelfOrParent()) return true
        val bounds = getBoundsInScreen()
        context.showClickEffect(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "手势点击")
        delay(100)
        return nodeGestureClick()
    }
}

