# 0.6.2 OCR 等待策略与真实复测

日期：2026-10-15。versionCode 9 / `0.6.2-ocr-timeout`，schema 仍为 5，包名、签名、模型和密钥不变。

## 改动与依据

- 原识别适配复用对话的 60 秒读取 / 180 秒总时限。官方视觉指南说明非流式服务端超时与客户端超时不同，完整结果可能需要较长等待；OCR 改用独立的 300 秒读取 / 330 秒总时限，连接时限继续为 15 秒。对话及 ASR 的原有时限不变。
- 上传确认和任务状态说明最长约 5.5 分钟的单次请求等待，可随时取消；仍不自动重试、跟随跳转或降低模型。时间配置改进不构成上次超时根因已被证实的依据。
- 明确拒绝 `x-dashscope-partialresponse: true` 的未完成响应，即使 JSON 中写有 `finish_reason=stop`，也不标记为可确认的完整识别。
- 显式授权的 OCR 设备测试只记录阶段与耗时，不记录 URL、IP、请求头、密钥、正文或服务器错误原文；普通回归不会运行在线用例。

## 本机验证

- `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 通过；87 项 JVM/Robolectric/Compose 测试全部通过，0 失败/错误/跳过；lint 0 errors / 9 warnings。
- 新增独立超时策略、拒绝服务端未完成结果，以及较长 OCR 等待仍可在两秒测试窗口内取消且不重试的本地 TLS 测试。保留既有协议、限额、密钥与界面回归。
- APK 22,626,738 字节，SHA-256 `cd82dae2c23b62338dbc51f2ac552b548d9d1286bf79c0a3abf52bec3c238c37`；本机副本 `artifacts/campus-notes-0.6.2-debug.apk`，不上传公开源码。
- 最终 APK 已在 Android 16 手机保留数据升级安装。12 项不联网真机测试完整通过，0 失败、0 跳过，耗时 11.139 秒；自动锁屏导致的一次前台超时未计入通过结果，解锁后重跑完整测试。
- 临时保持 USB 接入时亮屏，测试后通过相同电源服务恢复原关闭设置；不修改解锁密码或扩大系统权限。旧业务字段、全部原件、设置及密钥密文摘要与本轮升级前一致。

## 真实 OCR 复测

- 用户继续整改后，同一 `qwen3.5-ocr`、同一官方兼容地址、现有密钥及相同 Canvas 合成图片，只发送一次 OCR 请求；未调用 ASR、未上传个人资料、未使用麦克风或相机。
- 单次测试 `OK (1 test)`，耗时 1.299 秒；完成连接约 401 ms、完成上传约 428 ms、HTTP 200 响应头约 1234 ms、完整响应约 1244 ms。断言识别到固定测试标题与 A203 教室号，通过。
- 本次响应早于原来的超时上限，所以只能确认当前配置和该样本在线可用，不能把本次成功解释为“加长时限证明修复了上次根因”。0.6.1 的超时失败记录保留，不改写历史结果。
- ASR 的此前真实短样本成功结果见 `VALIDATION_RECOGNITION.md`；本次没有重复调用。两个识别服务均有真实短样本通过证据，不代表长课堂录音、中文手写、复杂扫描件或自动联动已验收。

参考：[官方视觉非流式超时与取消建议](https://help.aliyun.com/zh/model-studio/vision)、[Qwen OCR 接口](https://platform.qianwenai.com/docs/api-reference/specialized-models/qwen-ocr/openai)。
