# 边写边译 LiveTranslate

简体中文 | [English](README.en.md)

在任意 App 的输入框里边打字边翻译，一键把原文替换成译文的 Android 工具。用外语聊天、发帖、回邮件时，不用再在翻译 App 和聊天窗口之间来回切换。

![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20NC%201.0.0-orange)](LICENSE.md)

## 功能

- **输入框实时翻译**：通过无障碍服务读取当前输入框里光标所在的段落，停顿片刻（默认 400 ms，可调）后自动翻译，译文显示在输入框上方。
- **一键替换**：只替换被翻译的那一段，其余文字保持不变；替换前会核对原文和输入框是否发生变化，无法安全替换时改为复制译文。
- **两种触发方式**：「边打边译」，或「长按输入框再翻译」（只监听长按，不影响粘贴、全选等系统菜单）。
- **两种显示方式**：贴在输入框上方，或可拖动的悬浮窗；字号、不透明度可调。面板是无障碍悬浮层，**不需要悬浮窗权限**，也不会抢走键盘焦点。
- **多个翻译源**：哔哩翻译、豆包翻译（含火山、微软通道）、微软翻译、谷歌翻译，以及任意 OpenAI 兼容接口；在面板上一点即可切换。
- **翻译风格**（AI 翻译源）：日常口语、网络缩写、忠实原文、正式礼貌，也可以用一句话自定义风格。
- **短时上下文**（可选，AI 翻译源）：替换成功后 5 秒内开始写的下一段，会附带最近 3 条（最多 500 字）原文作为参考，帮助补全省略的主语；只保存在内存中。
- **单词模式**：把译文拆成单词，点一下即可朗读并查看中文释义。
- **隐私脱敏**（默认开启）：本地名单（人名、公司、地址等）以及邮箱、身份证号、6 位以上数字，发送前换成随机代号，译文回来后在本机换回；名单项可以设置「译文中显示为」，例如「张三」显示为「Zhang San」。
- **朗读**：使用系统 TTS，可选引擎和语速。
- **生效范围**：所有应用，或只在选中的应用里生效。
- **后台保活**：通过 Shizuku 或 adb 授权一次后，服务被系统杀掉也能自动恢复；另有常驻通知、电池优化白名单、厂商自启动设置入口，以及快捷设置磁贴（一键暂停 / 恢复）。
- **支持语言**：自动检测、简体中文、繁體中文、英语、日语、韩语、法语、德语、西班牙语、俄语。

## 隐私

- **不读取密码框。**
- **隐私脱敏**（设置 → 隐私脱敏）：名单内容和内置规则命中的内容，发送前替换成每次请求随机生成的代号（如 `ZQX7`），翻译源只能看到代号；译文回来后在本机换回。具体机制：
  - 短时上下文、自定义风格和单词释义同样经过脱敏；
  - 边打边译时，光标前还没打完的名单词和数字也会先遮住；
  - 译文里的代号缺失或异常时，「替换」按钮会被禁用；
  - 网络层在发送前再检查一遍，请求里如果还有名单内容就直接拦下。
  - 局限：只遮字面内容，关系等语义信息仍会发出；被遮住的部分可能译得不够准确。
  - 朗读和复制使用还原后的译文，在线朗读引擎和输入法云剪贴板不在保护范围内。
- 要翻译的文字只发送给你选择的翻译源。「单词」释义在当前翻译源为自定义 AI 时使用自定义 AI，否则使用哔哩翻译。
- 不包含任何统计、广告或崩溃上报 SDK。
- 自定义 AI 的 API Key 和脱敏名单用 Android Keystore（AES-GCM）加密后保存在本机；应用关闭了系统备份（`allowBackup=false`）。
- 短时上下文和词义缓存只在内存中，进程结束即清空。
- 为了能连接本机或局域网内的模型服务，应用允许明文 HTTP；公网接口请使用 HTTPS。
- 代码中有一个仅供开发调试的节点诊断广播，发送方必须持有 `android.permission.DUMP`（只有 adb shell 有），普通 App 无法触发。

## 系统要求

- Android 11（API 30）及以上
- 可选：[Shizuku](https://github.com/RikkaApps/Shizuku)，用于一键开启无障碍服务和后台加固

## 快速开始

1. 在 [Releases](../../releases) 下载 APK 安装，或按[从源码构建](#从源码构建)自行编译。
2. 打开「边写边译」，点首页的「去开启」，在系统无障碍设置里打开「边写边译 · 输入框实时翻译」。
   - 已安装并启动 Shizuku 时，按钮会变成「用 Shizuku 一键开启」，自动完成授权。
   - 无障碍开关是灰色、打不开：这是 Android 13 起对非应用商店安装 App 的限制。打开「应用信息」→ 右上角菜单 →「允许受限制的设置」，再回来重试。
3. 到任意 App 的输入框打字，输入框上方会出现译文面板：
   - 「替换」把这一段换成译文；「复制」「朗读」「单词」按需使用；
   - 点面板左上角的翻译源名称切换翻译源，点语言标签交换翻译方向。
4. 建议在「设置 → 防止被杀后台」里按从上到下的顺序完成各项，避免服务被系统杀掉。

没有 Shizuku 时，也可以在电脑上执行一次 adb 命令，开启「被杀后自动恢复」：

```bash
adb shell pm grant com.livetranslate android.permission.WRITE_SECURE_SETTINGS
```

## 翻译源

| 翻译源 | 需要什么 | 说明 |
| --- | --- | --- |
| 哔哩翻译（默认） | 无需登录 | 流式输出；可选模型 `Index-Translate-35B-A3B` / `index-mt-9b` / `index-mt-2b`；支持翻译风格和短时上下文 |
| 豆包翻译 / 豆包·火山翻译 / 豆包·微软翻译 | 豆包账号 | 可在应用内网页登录，也可以粘贴电脑浏览器里 doubao.com 的 Cookie |
| 微软翻译 | 无需登录 | |
| 谷歌翻译 | 无需登录 | 需要能访问 Google |
| 谷歌翻译2 | 构建时配置 API Key | 需要能访问 Google；构建时未配置 Key 则不可用，见[从源码构建](#从源码构建) |
| 自定义 AI | 接口地址、模型、API Key | 兼容 OpenAI 的 Chat Completions / Responses 接口，流式输出；接口地址填到 `/v1` 即可，会自动补全路径；本地模型可以不填 Key；支持翻译风格和短时上下文 |

> 除「自定义 AI」外，内置翻译源调用的都是各服务网页端或浏览器插件使用的非公开接口，随时可能变更或失效。请合理使用，并遵守相应服务的用户协议。

## 从源码构建

环境：

- JDK 17
- Android SDK（compileSdk 35）
- Gradle 8.13（由 Gradle Wrapper 自动下载）

在项目根目录创建或编辑 `local.properties`。这个文件已在 `.gitignore` 中忽略，**不要提交到仓库**：

```properties
sdk.dir=/path/to/Android/Sdk

# 可选：「谷歌翻译2」使用的 API Key（Chrome 网页翻译组件内置的 Key）。
# 不填则该翻译源不可用，其它翻译源不受影响。
GOOGLE_TRANSLATE_API_KEY=
```

也可以用同名环境变量 `GOOGLE_TRANSLATE_API_KEY` 提供，方便 CI 使用。

构建与测试：

```bash
./gradlew assembleDebug      # 调试包；Windows 下用 gradlew.bat
./gradlew test               # 单元测试
./gradlew assembleRelease    # 开启混淆和资源压缩的发布包
```

APK 输出在 `app/build/outputs/apk/` 下。

> 发布包目前使用 debug 签名，方便直接侧载安装。如果要长期分发，请换成自己的签名配置，并把 keystore 和密码放在仓库之外。

## 项目结构

```text
app/src/main/java/com/livetranslate/
├── a11y/        无障碍服务：监听输入框、计算翻译范围、安全替换、开机恢复
├── data/        设置存储、API Key 加密、翻译风格
├── keepalive/   常驻通知、看门狗任务、快捷设置磁贴、厂商自启动页面
├── net/         OkHttp 封装、SSE 解析、豆包 Cookie 同步、脱敏兜底拦截器
├── overlay/     翻译面板（Jetpack Compose）和悬浮层窗口
├── privacy/     隐私脱敏：匹配规则、随机代号、流式还原与校验
├── shizuku/     通过 Shizuku 以 shell 身份执行授权命令
├── translate/   各翻译源、翻译流水线（防抖 / 取消 / 缓存）、短时上下文、词义查询
├── tts/         朗读
└── ui/          首页、设置页、应用选择、豆包登录
app/src/test/    单元测试
.agents/notes/   设计决策记录
```

## 许可证

本项目以 [PolyForm Noncommercial License 1.0.0](LICENSE.md) 授权，**禁止商业使用**：

- 允许：个人学习、研究、兴趣项目，以及慈善、教育、公益等非营利机构，在非商业用途下使用、修改和再分发；
- 禁止：任何商业用途，例如出售本软件或其修改版、把它集成进商业产品或收费服务；
- 再分发时必须附带许可证全文（或其链接）以及 `Required Notice:` 版权声明。

以上只是摘要，具体条款以 [LICENSE.md](LICENSE.md) 英文原文为准。如需商业使用，请先联系作者获得授权。

本项目使用的第三方库（AndroidX、Kotlin Coroutines、OkHttp、Shizuku API 等）和 Gradle Wrapper 文件遵循各自的许可证。

## 免责声明

- 本项目与哔哩哔哩、豆包（字节跳动）、微软、谷歌、DeepSeek 等服务提供方没有任何关联，相关名称和商标归各自所有者所有。
- 译文由第三方服务生成，不保证准确，重要内容发送前请自行核对。
- 输入的文字会发送给所选的第三方翻译服务，请不要在涉及隐私或机密信息的场景中使用。
- 本软件按「原样」提供，不提供任何形式的担保。

## 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [OkHttp](https://github.com/square/okhttp)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
