# 校园记录与 AI 助手

Android 本地版 0.2.0，包名 `cn.campus.notes`，最低 Android 8.0（API 26）。本轮继续阶段二；AI 调用和自动整理留在阶段三。

## 已实现的本地功能

| 页面 | 当前可操作内容 |
| --- | --- |
| 首页 V2.1.10 | 固定一屏、真实本地资料摘要；快速记录入口支持文字记录新增、查看、编辑、确认删除 |
| 日程 | 大学七天周课表；课程新增、编辑、删除；星期/双节时段/1–30 周范围；重叠周次冲突检查；格子旁浮层详情 |
| AI | 对话/代理入口与临时草稿；发送、检索和整理明确显示尚未接入 |
| 资料库 V1.5 | 多大组及独立整理规则持久化；学科→课堂主题→原件/成果；结构增删改；系统文件选择器导入、重命名、外部应用打开和确认删除 |
| 更多 V1.0 | DeepSeek/ASR/OCR 分别配置；加密保存密钥；保存沙箱整理偏好；文件副本占用、未关联副本清理；复习仍未实现 |

首次启动为空库。在“更多”可主动载入示例课表与资料结构，示例带标记，不产生虚构文件。编辑示例条目后保存为个人条目。只有数据库或文件操作成功才提示已保存。

首页课程按当前星期汇总；尚未配置学期开始日期，因此不推算当前教学周。日程页手动切换周次；每个时段固定两节课，时刻为 08:00/10:00/14:00/16:00/19:00。长短课、单双周和自动课表导入待后续扩展。

## 数据与文件边界

- Room 保存文字记录、课程与资料层级，schema 位于 `app/schemas`。大组规则为每组独立字段。原件和成果使用不同节点类型；当前不生成 AI 成果。
- DataStore 保存地址、模型名、沙箱偏好及密钥密文。密钥使用 Android Keystore 的 AES-256-GCM 加密，并绑定服务类型；不回显，不进入日志或 Activity 保存状态。输入中的新密钥旋转后清空，已保存密钥保留。
- 文件通过系统 `OpenDocument` 选择，仅复制用户授权的内容到 App 私有 `files/documents`。单文件上限 100 MB；失败会回滚未完成副本。导入后不依赖外部 URI 的长期授权。
- 重命名改变本地显示名称；删除大组/学科/主题会在确认后级联删除其下级及本地文件副本。不会重命名、覆盖或删除外部源文件。
- 打开文件时使用范围限定的 FileProvider，仅向用户选定的查看应用临时授予读取权限。没有对应查看应用时显示错误。
- 本地存储清理仅移除未关联的导入残留，不清空数据库或已关联副本。备份、恢复、云同步尚未实现；卸载应用会丢失本地内容。系统自动备份已关闭。
- 代理执行器尚未实现。“允许沙箱内整理”只保存将来的授权偏好，不能跳过外部敏感修改确认。当前手动导入/删除只操作 App 内的副本。

页面选项、资料层级、编辑草稿使用 Compose 保存状态，支持切页和 Activity 重建。AI 草稿是临时页面状态，不承诺清除任务后的长期保存。

原对话助手内容只能取得引用标记，因此页面版本表示本次需求约束，不声称精确复刻旧原型。

## 构建与运行

固定版本：Gradle 8.14.3、AGP 8.13.0、Kotlin/Compose compiler/kapt 2.2.20、Compose BOM 2025.09.01、Room 2.8.4、DataStore 1.2.1。compile/target SDK 36，Java 编译目标 17，本机 JDK 21。

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

设备冒烟测试使用现有资料，只有空数据库才载入标注示例，不清理个人资料。课程或主题缺失时对应用例显式跳过。Keystore 测试只使用测试字符串。2026-10-14 已在 Android 16 手机上通过全部 4 项设备测试，状态与剩余验收项见 `VALIDATION.md`。

## 代码与后续

`data` 负责 Room、设置、密钥和本地副本；`ui` 负责五页、编辑和 ViewModel；`domain` 包含示例和写入策略；`integration` 是尚未实现的独立 AI/ASR/OCR 接口。

阶段三将接入用户配置的 DeepSeek 流式回复、取消、错误处理，分别实现 ASR/OCR 及结果确认，再实现整理预览、权限确认、执行日志和原件保留。当前 Manifest 没有网络、麦克风、相机或广泛存储权限。

参考官方文档：[Room](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore](https://developer.android.com/jetpack/androidx/releases/datastore)、[系统文档选择器](https://developer.android.com/training/data-storage/shared/documents-files)、[Android 密码学](https://developer.android.com/privacy-and-security/cryptography)。
