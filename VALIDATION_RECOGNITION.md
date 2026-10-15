# 0.6.1 识别接入整改

日期：2026-10-15。仅整改手动 ASR/OCR 接入，自动模型调度、附件对话与复习不在本次范围。

## 客户端修复

- versionCode 8 / versionName `0.6.1-recognition`；schema 仍为 5，包名与签名不变，不清空数据库或原件。
- 设置页和上传确认页检查当前接口协议；Anthropic Messages、Responses、独立音频转写及 DashScope 服务路径不能直接作为现有 Chat Completions 地址使用。
- 在任务入口和请求适配器再次检查，配置不匹配时在传输前失败；不能仅依赖按钮状态。千问官方域名检查兼容路径、完整识别模型名及明显的 ASR/OCR 类别错误；未知自定义兼容服务保留其自定义模型名。
- 提供千问官方示例：ASR `qwen3-asr-flash`，OCR `qwen3.5-ocr`，基址 `https://maas.qianwenaiapi.com/compatible-mode/v1`。点击示例只修改编辑草稿，保存才生效，均不联网，不自动迁移已有配置。
- 上传预览显示实际 POST 地址和本次模型；无效配置禁止确认发送，长说明可滚动。HTTP 405 提示检查路径/协议，不自动切换 GET、跟随重定向或重试。

## 本机结果

- 84 项 JVM/Robolectric/Compose 测试全通过，无失败或跳过；lint 0 errors / 9 warnings。`assembleDebug` 与 `assembleDebugAndroidTest` 通过。
- 新增模拟 TLS 验证错误协议路径零请求、官方模型/路径本地检查，以及 405 不重试、不回显服务器敏感正文。
- 新增界面验证：官方示例未保存时不改变持久设置；保存不联网；即使存在“已保存密钥”标记，无效协议仍禁止上传。
- debug APK：23,108,737 字节；SHA-256 `2a4c2649b702a898e0d4c199f47700469f3e7654a4cf5d6837eadff451c96154`；本机副本 `artifacts/campus-notes-0.6.1-debug.apk`，不提交安装包。
- 最终 APK 已保留数据升级安装到 Android 16 手机；12 项离线设备测试完整通过，0 失败、0 跳过，耗时 11.987 秒，明确排除在线测试类。覆盖五页、会话/草稿/状态恢复、本地内容及密钥；不把这 12 项与下述在线测试混算。

## 联网验收约束

- 用户在本轮明确授权：按上述官方配置修正 ASR/OCR，保留密钥，各进行一次可能计费的合成资料验连，不上传个人资料。
- 在线设备测试默认跳过，只有显式设置 `recognitionOnline=approved` 才执行；普通回归需选择离线测试类，不能意外重发计费请求。
- OCR 测试图由手机 Canvas 绘制固定英文文字与教室号；ASR 使用本机离线语音合成的英文短句，并在手机验证约 208 KB WAV 的 SHA-256 后才上传。未使用麦克风或系统相机。
- 密钥由手机 Keystore 解密并在手机发起请求；主机不取得明文。测试用相等布尔断言检查密钥保留，不把密钥作为可输出的断言期望值。
- 在线验收不等于所有格式、长音频与复杂扫描件的质量验收；DeepSeek 自动调用识别服务仍未实现。

## 实际在线结果

- 两项显式授权的真实在线测试共运行 279.433 秒：ASR 1 项通过，OCR 1 项失败。没有把失败隐藏、跳过或计入离线回归的通过数。
- `qwen3-asr-flash` 从合成英文短句中识别出校园记录及保留原件的关键内容；只代表此短样本可用，不替代课堂长录音、中文与噪声识别验收。
- `qwen3.5-ocr` 对合成文字图片的请求超时，未取得可核对的完整结果；没有重试或自动切换其他模型。没有收到 404/405，不据此认定所有旧错误均已解决。
- 手机现在保留上述已授权的官方配置；旧业务数据、全部原件和全部密钥密文摘要一致，DeepSeek 设置不变。仅 ASR/OCR 地址与模型元数据按授权调整。
- 在线失败后修正识别专用超时提示，明确“未取得完整结果，原件保留”，不借用对话的“已保留部分回复”提示。该提示改动通过本地超时测试，未因此重复发起在线请求。

参考：[千问 ASR](https://platform.qianwenai.com/docs/api-reference/speech-recognition/qwen-asr/api-reference)、[千问 OCR](https://platform.qianwenai.com/docs/api-reference/specialized-models/qwen-ocr/openai)、[Anthropic 路由](https://platform.qianwenai.com/docs/api-reference/chat/anthropic)。
