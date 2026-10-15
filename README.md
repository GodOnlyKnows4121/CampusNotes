# 校园记录与 AI 助手

Android 0.4.1，包名 `cn.campus.notes`，最低 Android 8.0（API 26）。本轮在原有 DeepSeek 能力上完成深色五页与本地结构迭代，并修复真机状态栏图标对比度。完整目标见 [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md)，实际交付与待办见 [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)。

## 已实现的本地功能

| 页面 | 当前可操作内容 |
| --- | --- |
| 首页 V2.1.10 | 固定一屏、校园卡片、四种入口、最近记录、本地搜索、独立校园页与作业待办；录音/拍照待接入 |
| 日程 | 多学期、自定义节次、跨节与单双/指定周、冲突校验；格子原地放大，月历选教学周 |
| AI | DeepSeek 流式对话、停止、错误提示、本机对话历史、回答另存记录；文本整理计划、预览确认、独立成果与执行日志 |
| 资料库 V1.5 | 两列文件夹、多大组独立规则/模式、主题/原件/成果；系统多文件导入、打开、重命名、回收站、收件箱与课程关联 |
| 更多 V1.0 | 独立模型设置、Keystore 密钥、沙箱授权、本地占用与回收站恢复、未关联清理；复习与备份待接入 |

首次启动为空库。在“更多”可主动载入示例课表与资料结构，示例带标记，不产生虚构文件。编辑示例条目后保存为个人条目。只有数据库或文件操作成功才提示已保存。

在日程设置真实学期开始日（第一周周一）、总周数及每节课时间，可按日期推算教学周。旧课程留在未设置学期的范围，保留原来 08:00/10:00/14:00/16:00/19:00 的时段含义；不以原型中的示例日期替用户设置学期。课程编辑支持跨节和 all/odd/even/custom 周次规则。

## 数据与文件边界

- Room 保存文字记录、课程、资料层级与新增学期/安排/待办/关联/回收站，schema 位于 `app/schemas`。大组规则独立保存。原件和成果使用不同节点类型，文本代理可新建成果。
- DataStore 保存地址、模型名、沙箱偏好及密钥密文。密钥使用 Android Keystore 的 AES-256-GCM 加密，并绑定服务类型；不回显，不进入日志或 Activity 保存状态。输入中的新密钥旋转后清空，已保存密钥保留。
- 文件通过系统 `OpenDocument` 选择，仅复制用户授权的内容到 App 私有 `files/documents`。单文件上限 100 MB；失败会回滚未完成副本。导入后不依赖外部 URI 的长期授权。
- 重命名改变本地显示名称；删除大组/学科/主题会在确认后将其下级树移入回收站并保留副本。恢复先校验父级与冲突，永久删除才移除不再引用的本地副本。外部源文件不变。
- 打开文件时使用范围限定的 FileProvider，仅向用户选定的查看应用临时授予读取权限。没有对应查看应用时显示错误。
- 本地清理保留资料库和回收站全部引用，只移除未关联残留。备份恢复与云同步尚未实现；卸载应用会丢失本地内容，系统自动备份已关闭。
- 代理仅在所选主题的 AI 成果区新建一个 Markdown 文件，路径由应用生成 UUID。模型只能返回标题与正文；不提供删除、覆盖、移动、外部修改或任意代码执行能力。未授权沙箱自动整理时须先预览并确认；已授权时有效计划自动执行。原件和外部源文件均保留。
- 对话与任务日志新增 Room 表，提供数据库 1→2 非破坏迁移。中断、取消、断流或错误的回答保留并标注；只有完整问答进入后续上下文（最近最多 20 轮，合计 50000 字预算）。清空对话需确认，不删除另存的记录，也不代替服务商端的数据删除。

页面选项、资料层级、编辑草稿使用 Compose 保存状态，支持切页和 Activity 重建；对话/代理切换保留草稿。AI 草稿是临时页面状态，已发送的对话持久保存。生成中的任务在应用退出后标注中断，不自动重新发起付费请求。

## 使用 DeepSeek 与整理代理

1. 在“更多 → 模型配置”保存 DeepSeek API 地址、模型名与密钥。地址接受 HTTPS 基址（如 `https://api.deepseek.com`）或完整 `/chat/completions` 地址；预填模型为 `deepseek-flash`，以服务商实际可用名称为准。保存设置不调用网络。
2. 在“AI → 对话”输入并主动发送。请求发送问题和最近完整对话到配置的服务；不会自动读取资料库。支持停止，取消不保证服务商退回已产生的用量。
3. 在资料库导入 UTF-8 文本原件（TXT / Markdown / CSV / JSON），在“AI → 代理”选择主题并查看所属大组规则与原件列表。点击“发送原件并生成整理计划”会上传该主题文本和规则；最多 10 份，每份 64 KB，合计 48000 字。不读取图片、录音、PDF 或其他二进制文件。
4. 未开启沙箱自动整理时，查看操作预览并确认写入；开启时自动执行有效计划。成果与原件分开存放，执行前再次校验原件和规则。来源改变、非法计划或重复执行均被拒绝；任务日志显示真实状态。

HTTPS 请求保留证书验证，不跟随跳转，不自动重试；401/403、余额不足、限流、服务器错误、超时和不完整流均给出提示。服务端原始错误正文不回显，以免泄露密钥或敏感内容。只有正常结束的文字回复标记成功。

本轮已用本地 HTTPS 模拟服务验证传输协议；尚未输入真实 DeepSeek 密钥做在线调用验收。ASR 是语音转文字，OCR 是图片转文字；两者独立配置，当前只有设置和接口契约，尚无识别入口。AI 检索栏也仍标注尚未接入。

原对话助手内容只能取得引用标记，因此页面版本表示本次需求约束，不声称精确复刻旧原型。

## 构建与运行

固定版本：Gradle 8.14.3、AGP 8.13.0、Kotlin/Compose compiler/kapt 2.2.20、Compose BOM 2025.09.01、Room 2.8.4、DataStore 1.2.1、OkHttp 4.12.0。compile/target SDK 36，Java 编译目标 17，本机 JDK 21。

1. 使用 Android Studio 打开此目录，安装 SDK Platform 36、Build Tools 35.0.0。
2. 生成本机 `local.properties`；不要提交此文件。本机 SDK 在 `work/android-sdk`。
3. 在项目目录运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

APK 默认在 `app/build/outputs/apk/debug/app-debug.apk`，本机交付副本在 `artifacts`。安装包、手机截图、验证日志、本机 SDK 和配置均不提交到公开源码仓库。

连接设备后用 Android Studio Run，或在干净测试设备上运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

设备冒烟测试只使用现有资料，不向空库自动插入示例。当前学期没有课程或资料库没有主题时，对应用例显式跳过。Keystore 测试验证测试字符串和已配置密钥可解密，不回显密钥或修改配置；AI 本地设备测试使用独立数据库、合成回复与单独清理的 UUID 文件，不调用模型。测试直接启动主应用并等待前台生命周期，避开部分厂商系统对测试包启动页的拦截。0.4.0/0.4.1 真机升级与本轮验证见 `VALIDATION_UI_LOCAL.md`，历史记录保留在 `VALIDATION.md` 和 `VALIDATION_PHASE3.md`。

## 代码与后续

`data` 负责 Room、设置、密钥和本地副本；`ui` 负责五页、编辑和 ViewModel；`domain` 包含示例和写入策略；`integration` 包含 DeepSeek HTTPS/SSE、对话与整理服务，以及待接入的独立 ASR/OCR 契约。

后续阶段三迭代将确定并接入 ASR/OCR 服务，实现识别结果确认与保存，随后接入 AI 检索并扩展整理类型。沙箱外敏感修改执行器尚未提供，未来接入时须逐次预览确认。Manifest 仅增加联网权限，没有麦克风、相机或广泛存储权限。每轮验证后提交并推送 GitHub，具体约定见 `AGENTS.md`。

参考官方文档：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[DeepSeek 错误码](https://api-docs.deepseek.com/quick_start/error_codes/)、[Room](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore](https://developer.android.com/jetpack/androidx/releases/datastore)、[系统文档选择器](https://developer.android.com/training/data-storage/shared/documents-files)、[Android 密码学](https://developer.android.com/privacy-and-security/cryptography)。
