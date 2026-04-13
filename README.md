# Assists 框架 - 社交媒体发布助手

Assists 是一个专注于社交媒体平台发布功能的 Android 无障碍服务框架，提供了抖音、微博和快手的自动化发布能力。

## 项目简介

Assists 框架旨在为开发者提供一套完整的社交媒体发布解决方案，通过封装 Android 系统的无障碍服务 API，简化抖音、微博和快手的自动化发布流程，提高发布效率。

## 支持平台

- **抖音**：支持视频、图片发布
- **微博**：支持图文、视频发布
- **快手**：支持视频、图片发布

## 核心功能

### 抖音发布功能
- 自动打开抖音应用
- 进入发布页面
- 选择媒体文件（视频/图片）
- 添加描述文本
- 选择话题和位置
- 发布视频/图片

### 微博发布功能
- 自动打开微博应用
- 进入发布页面
- 编辑发布内容
- 添加图片/视频
- 添加话题和@提及
- 发布微博

### 快手发布功能
- 自动打开快手应用
- 进入发布页面
- 选择媒体文件
- 添加描述和话题
- 设置封面
- 发布作品

## 快速开始

### 1. 集成框架

在项目的 build.gradle 文件中添加依赖：

```gradle
dependencies {
    implementation project(':assists-core')
}
```

### 2. 初始化框架

在 Application 类的 onCreate 方法中初始化 AssistsCore：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AssistsCore.init(this)
    }
}
```

### 3. 检查和开启无障碍服务

```kotlin
// 检查无障碍服务是否已开启
if (!AssistsCore.isAccessibilityServiceEnabled()) {
    // 打开系统无障碍服务设置页面
    AssistsCore.openAccessibilitySetting()
}
```

## 使用示例

### 发布抖音视频

```kotlin
// 打开抖音并发布视频
AssistsCore.launchApp("com.ss.android.ugc.aweme")
// 等待应用启动
Thread.sleep(3000)
// 进入发布页面（假设点击发布按钮的坐标）
AssistsCore.gestureClick(1000, 1800)
// 后续步骤：选择视频、添加描述、发布
```

### 发布微博

```kotlin
// 打开微博并发布内容
AssistsCore.launchApp("com.sina.weibo")
// 等待应用启动
Thread.sleep(3000)
// 进入发布页面（假设点击发布按钮的坐标）
AssistsCore.gestureClick(900, 1700)
// 后续步骤：编辑内容、添加图片、发布
```

### 发布快手作品

```kotlin
// 打开快手并发布作品
AssistsCore.launchApp("com.smile.gifmaker")
// 等待应用启动
Thread.sleep(3000)
// 进入发布页面（假设点击发布按钮的坐标）
AssistsCore.gestureClick(800, 1600)
// 后续步骤：选择视频、添加描述、发布
```

## 注意事项

- 本框架需要 Android 无障碍服务权限
- 使用过程中请遵守各平台的使用规范和法律法规
- 由于各应用版本更新可能导致 UI 变化，需要根据实际情况调整坐标和操作流程
- 建议在测试环境中充分测试后再用于生产环境

## 许可证

本项目采用 GNU General Public License v3.0 许可证。详见 [LICENSE](LICENSE) 文件。

## 联系作者

- 个人微信：x39598

---

感谢您使用 Assists 框架！如有任何问题或建议，欢迎联系开发者。