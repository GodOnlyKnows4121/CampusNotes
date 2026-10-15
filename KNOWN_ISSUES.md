# 待处理问题

## QWEN-405：千问服务请求返回 HTTP 405

- 记录日期：2026-10-15；用户明确更正为 **405**，不是此前提到的 404。
- 状态：按用户要求暂存，手机不在身边，稍后取得实际配置与错误记录后排查。
- 已知：0.5.0 的主动 ASR/OCR 适配发送 POST 到配置所对应的 Chat Completions 地址；仅完成本地 HTTPS 模拟协议验证，未验收用户服务在线可用性。
- 未知：405 出现在 ASR、OCR 还是对话；当前地址、模型名、最终请求路径及响应 Allow 头均未取得。不能据此断言密钥、模型或请求方法有误。
- 后续：核对出错入口和当前非密钥配置，对照服务文档确认协议/路径，用合成资料和明确的上传确认复现，再进行最小修复与回归。保留全部资料及已有密钥，不输出密钥或原始敏感响应。
- 当前不自动改写千问地址、模型名或识别协议，不把增加错误提示视为已修复连接。

### 2026-10-15 USB 核查补充

- 已从设备只读核对非密钥配置与结构化错误状态，没有解密或输出密钥，也没有发起在线调用。
- 发现 ASR 当前使用 Anthropic Messages 路由，而客户端 ASR 适配固定使用 Chat Completions。这是已确认的配置/客户端协议不匹配；拼接出的 `/apps/anthropic/chat/completions` 不是该协议提供的 Messages 路径。不能通过简单改 POST/GET 修复。
- 官方 ASR 兼容接入要求 `/compatible-mode/v1/chat/completions` 与明确支持音频的模型（如官方文档中的 `qwen3-asr-flash`）；OCR 同样需要明确具备图像输入能力的模型。通用名称不视为能力已验证，也不推断该账户有对应权限。
- 设备现存识别任务保存的是 HTTP 404，未找到 405；这不否定用户观察到的 405，但本次未复现，因此不标记为已修复。既有地址、模型和密钥保持原值，待明确选择服务及确认合成资料上传后验证连通性。
- 文档依据：[Anthropic Messages 路径](https://platform.qianwenai.com/docs/api-reference/chat/anthropic)、[Qwen ASR 兼容路径与模型](https://platform.qianwenai.com/docs/api-reference/speech-recognition/qwen-asr/api-reference)。

## AI-ORCHESTRATION：DeepSeek 尚不能自动调度 ASR/OCR

- 状态：规划需求，尚未实现；按用户要求先搁置自动调度，继续其他已计划功能。
- 当前可用流程是用户主动识别 → 核对文字 → 文字对话/整理，DeepSeek 不具备自动发起另外两项服务的工具执行器。
- 后续在代理工作流实现，并先确认识别服务可用；上传范围、可能计费、结果确认和沙箱权限仍须遵守原需求。
- 独立会话、多语言模型配置及思考档位不代表三模型联动完成。
