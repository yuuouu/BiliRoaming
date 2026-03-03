# BiliRoaming (哔哩漫游) 项目分析报告

本报告根据对 `BiliRoaming` 项目（用于哔哩哔哩 Android 客户端的 Xposed 模块项目）的源码审查，从多个维度进行了深度分析：

## 1. 项目结构与模块划分

项目的源码结构主要由以下顶层目录和包构成：

### 1.1 顶层目录
- `app/src/main/`：Android App 的核心源代码目录。
  - `assets/xposed_init`：Xposed 模块的入口声明文件，指定了启动类 `me.iacn.biliroaming.XposedInit`。
  - `java/me/iacn/biliroaming/`：主包名，包含了应用的核心 Java/Kotlin 代码。
  - `jni/`：C++ 代码目录，主要包含 `dex_builder` 和 `biliroaming.cc`，用于底层快速从目标应用的 dex 文件中搜索被混淆的类和方法。
  - `proto/`：Protobuf 定义文件，用于定义 Hook 相关的配置结构和 API 数据结构。

### 1.2 主要包（Package）和类的职责分配
- `me.iacn.biliroaming` 根目录：
  - `XposedInit.kt`：Xposed Hook 的核心入口点类，实现了 `IXposedHookLoadPackage` 和 `IXposedHookZygoteInit` 接口。它负责判断是否加载了目标 B 站应用，并根据不同的进程（主进程、web 进程、download 进程）分发不同的 Hook 任务。
  - `BiliBiliPackage.kt`：动态适配的基石。它主要负责在运行时利用 `DexHelper` 快速扫描 B 站客户端，通过特定的字符串或指令特征寻找被混淆的类、方法和字段，并缓存下来以供各种具体的 Hook 逻辑使用。
  - UI 相关的 Dialog 类（如 `SettingDialog.kt`, `ColorChooseDialog.kt`, `HomeFilterDialog.kt` 等）：这些通常是通过 Hook B 站客户端本身的界面，往其中插入的自定义设置弹窗或视图。
- `me.iacn.biliroaming.hook`：存放所有具体的 Hook 逻辑实现。每个功能通常对应一个类，职责单一，结构清晰。
  - 例如 `BangumiSeasonHook`（处理番剧数据和区域限制解除）、`DynamicHook`（处理动态内容的过滤和净化）、`LiveRoomHook`（直播间功能增强）、`TeenagersModeHook`（青少年模式弹窗拦截）等等。
- `me.iacn.biliroaming.network`：网络请求模块，`BiliRoamingApi` 负责和远程的代理服务器、CDN 服务器进行通信，用于获取海外或被限制的番剧信息、播放地址。
- `me.iacn.biliroaming.utils`：工具类集合，包括 `DexHelper`（JNI 加载 Dex 用于快速搜索方法的封装接口）、Kotlin 封装的 Xposed 扩展方法（如 `hookBeforeMethod`, `replaceMethod`）、日志输出等。

## 2. 核心技术栈与构建系统

基于 `app/build.gradle.kts` 的分析，项目的核心构建和配置信息如下：

### 2.1 第三方依赖
- **Xposed API**：`compileOnly(libs.xposed)` 仅参与编译，在宿主运行环境中由 Xposed/LSPosed 框架提供实际的 API。
- **Kotlin 语言生态**：`libs.kotlin.stdlib`, 各种协程 `libs.kotlin.coroutines.android` 等，主要使用 Coroutines 处理后台网络请求和异步文件操作。
- **Protobuf (Protocol Buffers)**：用于数据结构的强类型序列化（例如网络请求实体解析，或 Hook 的配置信息 `Configs.HookInfo`），项目中集成了 Protobuf 的 Kotlin/Java 插件 `generateProtoTasks`。
- **AndroidX & DocumentFile**：提供了如 `DocumentFile` 库以适配高版本 Android （如 SAF、文件系统权限等特性）。

### 2.2 构建配置信息
- `compileSdk = 35`, `targetSdk = 35`：适配 Android 14/15 级别。
- `minSdk = 24`：最低支持 Android 7.0。
- **代码混淆**：
    ```kotlin
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
    ```
    可见在 `release` 变体中启用了代码混淆和资源压缩。作为一个模块化项目，通常需要对外隐藏部分内部细节或精简体积。
- **C/C++ 原生层 (JNI)**：
    ```kotlin
    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }
    ```
    启用了 NDK 开发（通过 `cmaker` 插件甚至增加了 LTO 编译优化标记 `-flto` 和兼容页大小的宏 `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`），编译出了 `armeabi-v7a`, `arm64-v8a` 和 `x86` 下的动态库，这些原生代码的作用主要是为了使用内存映射等手段解析和搜索 Dex 文件。
- **签名与特殊 Transform**：使用 `lsplugin` 等一系列插件自动处理 APK 的签名，还定义了特殊打包任务（例如在发布时重命名为包含版本号的 APK，在 `installDebug` 之后强制重启目标 App 以立刻生效等）。

## 3. Xposed 入口与核心 Hook 逻辑分析

### 3.1 模块入口
在 `assets/xposed_init` 文件中，声明了本模块的入口类：
```
me.iacn.biliroaming.XposedInit
```
### 3.2 核心生命周期：`handleLoadPackage` 流程
`XposedInit` 实现了 `handleLoadPackage` 方法。其初始化流程如下：
1. **模块自检**：如果载入的包名是当前模块（`BuildConfig.APPLICATION_ID`），则将宿主的 `MainActivity.Companion.isModuleActive` 方法替换成返回 `true`。这通常用于在自身应用内检测“Xposed 框架/本模块是否已激活”。
2. **目标应用检测**：判断是否是哔哩哔哩客户端（`Constant.BILIBILI_PACKAGE_NAME.containsValue` 或具有 `MainActivityV2`）。
3. **初始化拦截**：使用 `Instrumentation::class.java.hookBeforeMethod("callApplicationOnCreate")` 作为总入口进行 Hook，以确保目标进程完成早期初始化和资源加载后执行后续代码。
4. **进程分发处理**：根据当前进程（`lpparam.processName`）的特征，分发给相应的 Hook 管理类：
   - **主进程（不包含 `:`）**：初始化主类 `BiliBiliPackage(lpparam.classLoader, context)`。这是扫描和缓存特征（类名和方法索引）的地方，它利用协程等获取区域配置（例如通过 `Constant.zoneUrl`）。接着，调用 `startHook` 启动多达几十个核心业务的 Hook 类（如 `BangumiSeasonHook`, `PegasusHook`, `SettingHook` 等）。
   - **`web` 进程（包含 `:web`）**：主要涉及网页界面的处理，启动了如 `CustomThemeHook`, `WebViewHook`, `ShareHook`, `RewardAdHook` 等。
   - **`download` 进程（包含 `:download`）**：针对下载服务进行控制，启动 `BangumiPlayUrlHook` 获取特定的代理下载链接。
5. **延迟执行的生命周期回调**：在 B 站应用的 `Activity.onResume` 时，触发此前各类 Hook 的 `lateInitHook()` 方法。

### 3.3 核心业务线的 Hook 归类
该项目 Hook 了 B 站应用的众多关键业务线。根据功能，可以大致归类为：

1. **番剧区域限制解除和资源获取**
   - **核心类：** `BangumiSeasonHook`, `BangumiPlayUrlHook`
   - **涉及逻辑：** 这是该项目的“灵魂”。通过拦截客户端发起的网络请求并篡改响应，允许被地域锁区的番剧进行播放。例如 `BangumiSeasonHook` 会拦截诸如 `api.bilibili.com/pgc/view/v2/app/season` 的返回内容并替换成漫游服务器的 JSON 响应，同时放宽 `area_limit` 的标记，并将下载权限（`rights` 中的 `allow_download`）解锁。
2. **应用行为精简、净化广告（UI 和拦截）**
   - **推荐流和广告：** `PegasusHook` (推荐页净化)，`BangumiPageAdHook` (番剧主页广告拦截)，`StoryPlayerAdHook` (短视频流广告拦截)。
   - **弹窗和功能开关：** `TeenagersModeHook` (青少年模式弹窗关闭)、`BlockUpdateHook` (阻止应用更新弹窗)。
3. **外观定制、主题破解**
   - **核心类：** `CustomThemeHook` (自定义主题色，主题解锁), `SplashHook` (开屏/启动页修改)。
   - **其他界面：** `DrawerHook` (侧边栏界面定制), `DialogBlurBackgroundHook` (弹窗模糊效果支持)。
4. **视频播放体验与实用工具增强**
   - **核心类：** `AutoLikeHook` (自动点赞), `CoverHook` (获取并保存视频/直播的封面), `CopyHook` (提供评论、简介文本复制功能)。
   - **播放属性修改：** `SpeedHook` / `LongPressSpeed` (视频长按倍速解锁或自定义倍速)，`VideoQualityHook` / `TryWatchVipQualityHook` (画质限制解除、画质自动选择等)。

## 4. 核心业务流程追踪

### 4.1 区域限制解除逻辑分析

针对哔哩哔哩客户端的**番剧区域限制**，该项目不是通过简单的修改 UI，而是**在底层网络请求库发起或处理请求的生命周期上进行拦截，并且重写响应（Response）和篡改鉴权结构体来实现解锁。** 以 `BangumiSeasonHook` 和 `BiliRoamingApi` 为例：

1. **核心请求类识别：** 在 `BiliBiliPackage` 初始阶段通过 `DexHelper` 已经获取到用于处理 HTTP 响应的核心类（如 `RetrofitResponse`, `RxGeneralResponse`）。
2. **请求重定向/代理拦截：**
   当系统触发网络请求到番剧 URL（例如 `https://api.bilibili.com/pgc/view/v2/app/season`）时，`BangumiSeasonHook` 中的 `hookBeforeAllConstructors` 会拦截其响应流的实例化过程：
   - 首先拦截 JSON 数据：获取原始 JSON，并检查 `rights` 和 `code` 等对象，判断其 `isBangumiWithWatchPermission()`。
   - 触发重试逻辑：如果检测到没有观看权限（`area_limit`），项目将发起自己的协程调用（通过 `BiliRoamingApi.getSeason`），携带当前 `season_id` / `ep_id` 向用户在插件中配置的区域服务器（例如泰区，港澳台代理服务器）请求未锁区的番剧信息。
   - 解析修改数据包：解析来自漫游服务器的正确结果，通过修改内部的 JSON（如修改 `status`, 将 `area_limit` 设置为 0 并调用 `allowDownload()` 赋予缓存下载权限）。
3. **Protobuf 鉴权绕过（新版 B 站机制）：**
   对于新版的 B 站 APP（使用 gRPC/Protobuf），项目拦截了 `ViewUniteMossClass.executeView` 或对应的内部方法 `view`，解析出返回的 Protobuf 数据对象 `ViewPgcAny` 和 `ViewUniteReply`：
   - 项目获取序列化数据，并用模块自身的 Kotlin 类反序列化。
   - 通过将原数据的 `areaLimit = 0`，`allowDownload = 1` 等设为特权值（见 `fixViewProto` 逻辑）。
   - 将重新组装的权限 Protobuf Payload 覆盖掉原 Response，从而以底层形式实现“免检”播放。

### 4.2 动态适配机制

B 站客户端存在频繁的版本更新，这就不可避免地导致原代码经历不同版本的混淆处理，硬编码的类名往往失效。该项目采用了一套极为高级的**原生搜索与运行时扫描（Dex Scanning / Runtime Pattern Matching）机制**：

1. **利用 JNI 加速的 Dex 解析（`dex_builder`）：**
   该项目开发了 `me.iacn.biliroaming.utils.DexHelper`。这个类在 C++ 层面直接读取并解析 B 站进程内加载的 Dex 文件字节流（见 `biliroaming.cc`）。
2. **利用字符串常量和方法调用链寻找被混淆的方法和类：**
   在 `BiliBiliPackage.initHookInfo` 中，应用不是通过 `com.bilibili.XClass` 来绑定 Hook，而是通过它内部会抛出的特征字符串，如 `"rawResponse must be successful response"`、`"Cannot switch to quality "`。
   ```kotlin
   val retrofitResponseClass = dexHelper.findMethodUsingString(
       "rawResponse must be successful response",
       false, -1, -1, null, -1, null, null, null, true
   ).asSequence().firstNotNullOfOrNull {
       dexHelper.decodeMethodIndex(it)
   }?.declaringClass
   ```
   通过 C++ 函数 `findMethodUsingString`，搜索含有指定特征字符串的方法，进而回溯定位到该方法所在的被混淆类（`declaringClass`）。
3. **利用协议缓存配置：**
   项目维护了 `Configs.HookInfo` Protobuf 结构。由于扫描过程非常耗时，因此每次扫描完都会根据 B 站包的 `lastUpdateTime` 和插件版本等信息生成 `HookInfo` 的 Cache 并写入本地存储，以后在不改变版本时直接读取这个 Protobuf 缓存（极大加速了应用冷启动的速度），进而通过 `from(mClassLoader)` 对特征类进行实例化使用。
4. **适配反射：**
   一旦有了这些真实的运行时 Class 引用，所有的 `hookAfterMethod`, `replaceMethod` 都可以正常通过 Java 反射和 Xposed API 进行挂载。

## 5. 总结与二次开发建议

### 5.1 代码架构设计模式评估

该项目的代码架构设计非常成熟且具有高超的逆向工程技巧。它的核心亮点在于：
- **高度解耦和模块化：** 不同的功能 Hook（`BaseHook`）各自为战，互不干扰，通过 `XposedInit` 统一分发和生命周期管理。
- **动态扫描适配（Dynamic Pattern Match）：** 使用 C++ 加速 Dex 文件解析，根据字符串特征或指令调用链而非硬编码包名类名（如 ProGuard / R8 映射表）。这不仅保证了兼容各大 B 站版本（稳定版、粉版、HD版），还大大减少了每次更新 B 站客户端后模块维护的代码成本，这在 Xposed 社区里属于非常高阶的设计。
- **性能与协议并重：** 利用 Protobuf 进行序列化，同时采用 Kotlin 协程处理异步流程，在解析大型 JSON 和发起拦截网络请求时表现出非常高的执行效率。

### 5.2 二次开发建议与坑点提示

如果您打算在现有基础上针对最新版客户端增加新的 Hook 功能，请遵循以下开发步骤并注意相关陷阱：

#### 1. 新功能的切入点分析
   - 如果功能涉及**界面表现或隐藏某个组件**：首选使用传统的 UI 审查工具（如 Android Studio 的 Layout Inspector，或者开发者模式的“显示布局边界”）找到目标视图 ID 或其引用的特定字符串常量。
   - 如果功能涉及**数据篡改**：建议在抓包工具（如 Charles 或 HttpCanary）中找到该数据的请求路径，像 `BangumiSeasonHook` 那样通过篡改 `RxGeneralResponse` 的方式处理接口请求或 Protobuf 数据流。
   - **重要：** 必须结合 `Jadx-gui` 分析目标方法的代码特征，以便你定义 `DexHelper` 的搜索策略。

#### 2. 在 `BiliBiliPackage` 注册新的混淆签名映射
   绝不要将任何新 B 站代码类名（尤其是非 `android` / `androidx` 包的类）写死。
   - 在 `app/src/main/proto/me/iacn/biliroaming/configs.proto` 中增加你需要的 Class/Method/Field 的结构定义（例如 `MyNewFeature myNewFeature = 100;`）。
   - 在 `BiliBiliPackage.kt` 的 `initHookInfo` 中，使用 `DexHelper`（例如 `findMethodUsingString` 或 `findMethodInvoked`）精确定位到你想 Hook 的类。
   - 如果你发现 B 站最新版去除了某个字符串常量，这会导致相关的 `DexHelper` 索引挂掉。你需要及时找出新的唯一特征替代。

#### 3. 编写新的 Hook 类
   - 在 `me.iacn.biliroaming.hook` 目录下新建 `YourNewFeatureHook.kt`，继承 `BaseHook`。
   - 在 `startHook` 里实现你的业务拦截逻辑。对于回调中复杂或耗时的逻辑，应放在 Kotlin 协程的 `Dispatchers.IO` 中执行。
   - 在 `XposedInit` 中的 `Instrumentation.callApplicationOnCreate` 生命周期阶段注册它。

#### 4. 权限与坑点
   - **权限问题：** 新版的 Android 强化了权限控制（如 Android 11+ 对外部存储的限制 `MANAGE_EXTERNAL_STORAGE`），如果需要把文件保存到外部设备，需要像 `CoverHook` 等模块一样采用合规的 ContentResolver 机制（甚至弹窗申请权限）。Xposed Hook 获取的网络权限默认取决于目标宿主（B 站客户端），但在 Android 7.0+ 系统上还需要绕过 B 站的网络库证书校验或直接注入自己的 `OkHttpClient` 配置。
   - **Xposed 本身的异常处理：** 对 Xposed `param.result` 等对象的覆盖和篡改，非常容易由于类型转换异常而造成整个 B 站崩溃，因此建议使用项目提供的 `runCatchingOrNull` 包装易出问题的代码块。
   - **CompactDex 的问题：** B 站应用体积越来越大，某些厂商 ROM 或 Android 系统更新引入的 ART/CompactDex 优化可能导致 `DexHelper` 中的 C++ 逻辑由于内存映射失败而崩溃。虽然源码中已处理（`dex::Reader::IsCompact` 检测并回退寻找 Zip 原始包），但若出现扫描全为 null 的情况，很可能和被加固或特殊机型 ART 的 OAT 预编译有关，测试时建议使用标准 LSPosed 框架及较新的 Android 版本。