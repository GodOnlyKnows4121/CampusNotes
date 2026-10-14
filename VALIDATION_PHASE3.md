# 阶段三首轮：对话与文本整理代理

日期：2026-10-14（北京时间）。版本：`0.3.0-ai`，versionCode 3。

## 本轮实现

- 用户配置的 DeepSeek Chat Completions HTTPS/SSE 流式对话、停止、超时、鉴权/余额/限流/服务器错误提示；不自动重试、不跟随跳转、保留证书验证。
- 本机对话历史、完整问答上下文、回答另存记录和确认清空。取消、断流、长度截断和退出中断均标注真实状态，部分回复不进入后续上下文。
- 资料大组独立规则驱动的 UTF-8 文本整理：选择主题、展示来源、主动发送文本和规则、校验 JSON 计划、预览确认或已授权的沙箱自动执行。
- 只新建独立 Markdown 成果，记录来源、规则、任务 ID 和执行状态；保留原件。执行前校验规则与原件指纹；拒绝非法字段、模型指定路径、过时计划和重复执行。
- Room 数据库 1→2 迁移，增加对话和任务表，不使用破坏性重建。Manifest 增加 INTERNET，无新增麦克风、相机或广泛存储权限。

## 桌面构建与测试

- `assembleDebug`、`testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest` 全部成功；最终本机日志为 `work/phase3-final-build.log`。
- 33 项 JVM/Robolectric 测试全部通过，0 失败、0 错误、0 跳过。
- 8 项协议测试覆盖 SSE 保活/CRLF、中文、畸形/超长/不完整响应、拒绝危险计划、地址校验、真实本地 HTTPS 的 HTTP/1.1 分块与 HTTP/2 DATA 帧、请求格式、鉴权/限流/跳转不泄露服务端正文、不自动重试、取消后关闭连接。
- 5 项 AI 本地数据测试覆盖完整/失败/取消回复持久化及上下文隔离、缺少配置不写入对话、计划预览、权限确认、独立成果与原件保留、一次执行、规则改变与非法操作拒绝，以及真实旧 schema 升级后保留已有数据。
- 原有功能回归及新增界面测试覆盖五栏、课程浮层、资料层级、独立规则、模型设置、草稿切页与模式切换、Activity 状态恢复、缺少密钥的真实错误、通过界面确认产生真实成果和保留原件、横屏对话输入/发送，以及小屏和大字体固定首页。
- 已检查桌面视图渲染截图中的横屏对话输入/发送布局。Dialog 预览通过语义界面测试验证，Activity decorView 截图只包含其背景窗口，不作为 Dialog 视觉截图。
- Lint 0 错误、12 警告：10 个依赖更新提示、1 个 kapt 性能提示、1 个基础启动图标 monochrome 提示。未隐藏检查；API 33 图标分支已有 monochrome。
- 主 APK 和设备测试 APK 的签名验证成功，主 APK 的 v2 签名有效。

## 真机验证

- Android 16 / API 36 手机通过 `adb install -r` 升级安装，未清除应用数据或更改已有模型密钥。
- 6 项设备测试全部通过，报告 `OK (6 tests)`，无跳过。覆盖五栏、Activity 重建与草稿、现有课程浮层、现有资料层级与返回、对话/代理切换和执行边界，以及 Keystore 加密解密与服务绑定。
- 新增 AI 本地设备测试使用独立内存数据库和合成提供方，实际执行记录持久化、无确认时拒绝写入、确认后创建成果及原件保留。只清理测试创建的 UUID 文件，不触碰个人记录或其他文件，也不调用模型服务。
- 冷启动成功，单次观察 TotalTime 366 ms、WaitTime 368 ms；不是性能基准。
- 本机证据：`artifacts/device/phase3-tests.log`、`artifacts/device/phase3-home.png`。设备测试执行期间仅测试窗口保持亮屏，不修改手机全局调试设置。

## 本机交付产物

安装包、报告、设备日志和截图均位于 Git 忽略目录，不上传公开源码仓库。

- `artifacts/campus-notes-0.3.0-debug.apk`：13,351,161 字节，SHA-256 `696e727ce3a0825d6e20a2c5f79d793e6e539e1d46a33218e9297033e711d2ca`。
- `artifacts/campus-notes-debug.apk`：同一新版包的固定名称副本；旧 0.1.0、0.2.0 版本保留。
- `artifacts/campus-notes-device-tests.apk`、`artifacts/SHA256SUMS-0.3.0.txt`、`artifacts/reports/phase3`：对应测试包、校验值与报告。

## 验证边界与后续

- 未使用真实 DeepSeek 密钥进行在线调用；本地 HTTPS 模拟服务验证了真实网络传输、SSE、错误及取消，但不能证明用户账户余额、模型权限或服务当前可用性。真机的合成提供方测试不代表真实 AI 回复。
- ASR/OCR 服务尚待选择，当前只有独立配置和契约，无识别与结果保存流程。图片、音频、PDF 整理和 AI 检索尚未实现，页面明确提示。
- 整理执行器仅新建 App 私有成果。外部敏感修改、任意脚本、删除/覆盖原件等能力没有执行入口；不声称已实现完整通用代理。
- 真机横屏、大字体、软键盘、系统文件选择器和外部查看应用仍未完成专项验收；横屏/小屏/大字体桌面渲染及输入/发送界面测试已通过。

## 复现

标准环境：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

本机离线运行使用忽略的 `work/test-network.init.gradle`。缺少 Gradle UTP 离线组件时，安装已编译的两个 APK 后使用等价设备入口：

```powershell
adb shell am instrument -w -r cn.campus.notes.test/androidx.test.runner.AndroidJUnitRunner
```

具体功能范围与配置流程见 README，后续迭代验证和上传约定见 AGENTS.md。
