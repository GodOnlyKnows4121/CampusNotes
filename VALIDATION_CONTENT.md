# 0.5.0 采集与内容处理验证

日期：2026-10-15。对应完整需求第二阶段，后续 AI 会话、附件对话、引用检索、复习、提醒与备份不在本次交付内。

## 构建与数据

- 包名、签名不变；versionCode 6 / versionName `0.5.0-content`；最低 API 26、target/compile 36。
- Room schema 4，仅用 `MIGRATION_3_4` 增加 `content_results` 与来源索引；完整保留 1→2→3→4 迁移链及各版 schema。
- 新表保存处理状态、来源摘要、草稿/确认文字、非密钥配置快照和另存记录引用。原件始终独立保存；密钥仍由 Android Keystore 加密。
- 本机构建使用固定 PdfBox-Android 2.0.27.0 及其依赖，新增原生 MediaRecorder/MediaExtractor/MediaMuxer 流程。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 全部通过：65 项 JVM/Robolectric/Compose 测试，无失败；lint 0 errors、9 warnings。新增的实际系统资源 1.5 倍字体也已检查并保留本地截图。
- 最终真机回归一次运行 `OK (10 tests)`，无失败、无跳过，耗时 8.536 秒；覆盖合成 PDF 的实际 Android 解析。中途一次锁屏导致的超时未计入通过结果，解锁后完成最终重跑。
- debug APK：22,966,030 字节；SHA-256 `8fc774acf7e79387d63ac0788ac7aeed9ae69fd92332b8a54580528ef6300224`。本机副本为 `artifacts/campus-notes-0.5.0-debug.apk`，安装包不推送公开源码。

## 本机验证内容

- 原有五页、课表、回收站、HTTPS 对话与整理回归。
- DOCX 段落/表格/公式文字、PPTX 数字页序、实际生成的可读 PDF，以及 UTF-8/带 BOM 的 UTF-16 文字提取。
- 不支持的旧 DOC/PPT、空白/扫描 PDF、损坏文件、外部实体、解压及文字大小上限拒绝；原件不变。
- 处理→待核对→修改→确认→另存来源记录；二进制资料未确认时拒绝整理，来源改变后拒绝沿用旧结果。
- 重新确认选择有效文字版本并改变整理快照；重复任务合并、取消、启动恢复为中断状态、回收站恢复及永久删除竞态。
- 独立 HTTPS 模拟 ASR/OCR 请求，检查 `input_audio` / `image_url`、模型和鉴权；拒绝不完整、空白、错误响应，取消传输，不跟随跳转、不重试、不回显服务端原始错误正文。
- 320dp 核对页、确认前编辑、草稿恢复及横屏 1.5 倍字体的操作入口；首页仍不纵向滚动。

## 真机验证范围

- Android 16 手机保留数据从 0.4.1 升级到 0.5.0，实际数据库 3→4 迁移并冷启动。
- 最终安装包测试完成后再次比较，原有全部表行、原件及加密配置摘要仍与本轮升级前一致；已有密钥仍可解密。隔离测试没有向个人库插入示例或新记录。
- 升级前后比较所有原有业务表、原件和加密 DataStore 的摘要；不向公开仓库写入个人内容、密钥、摘要或设备标识。
- 本地解析及确认使用隔离数据库与合成文字；只清理测试自身产生的 UUID 副本。
- AAC 编码器生成 4 秒静音样本，按 1 秒测试边界分段并逐段读取：验证样本总数、顺序与源文件摘要；不使用麦克风或真实环境音。
- 五栏切换、课程详情、资料层级、AI 草稿/重建恢复、已配置密钥可解密；不会验证或改写真实服务配置。

## 明确未验收与边界

- 用户选择本轮暂不测试真实采集：未申请麦克风权限，未真实录音、锁屏长录音或拍照。前台服务和权限流程已实现，硬件及后台持续性仍需后续验证，不能用合成 AAC 测试替代。
- 本轮未调用真实 DeepSeek、ASR 或 OCR。保存的模型别名与地址不视为能力已核验；HTTPS 模拟通过不表示用户配置可在线使用。
- 在线图像上限 7 MB；AAC/M4A 自动分段最多 128 段，每段最多 3 分钟/约 5 MB；其他音频需为 3 分钟/7 MB 以内。处理文字上限 60000 字，失败时保留已有未完成部分，不假装整体成功。
- 本机文字提取上限 20 MB；PDF 200 页，OOXML 200 个正文/页部件，并有解压限制。旧 DOC/PPT、扫描 PDF、复杂图表与精确排版还原不支持。扫描页可导出图片后主动 OCR。
- 识别任务在进程内继续，进程退出后标为中断，不自动重新上传。课程/作业提醒、识别完成系统通知、备份恢复与分类存储统计仍属于第四阶段。
- lint 中新增的 BouncyCastle 依赖内部 TrustAll 工具告警不在应用 HTTP 调用路径上；网络仍使用默认证书验证，本地测试只为测试证书配置私有信任，不改变生产 TLS。

参考：[Android 录音](https://developer.android.com/media/platform/mediarecorder)、[前台服务类型](https://developer.android.com/about/versions/14/changes/fgs-types-required)、[Qwen ASR 兼容协议](https://help.aliyun.com/en/model-studio/qwen-asr-api-reference)、[Qwen OCR 兼容协议](https://help.aliyun.com/en/model-studio/qwen-vl-ocr-api-reference)、[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)。服务当前要求仍以供应商为准，客户端不会自动修改用户已有配置。
