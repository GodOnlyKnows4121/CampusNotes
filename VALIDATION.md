# 阶段二本地版与真机验证记录

日期：2026-10-14（北京时间）。版本：`0.2.0-local`，versionCode 2。

## 已验证

- `assembleDebug`、`testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest` 最终全部成功；对应日志为 `work/phase2-verified-final.log`。
- 17 项 JVM/Robolectric 测试全部通过，失败、错误和跳过均为 0：8 项界面测试、1 项真实启动 Activity/重建测试、5 项本地存储测试、3 项写入策略测试。
- 界面覆盖五栏切换、课程原地浮层、独立大组规则、主题/原件/成果层级、切页和保存状态恢复、Activity 重建与返回路径。
- 实际通过 UI 新增文字记录、编辑大组规则和保存模型地址/名称，并重新读取核对。空库首次启动可创建大组→学科→主题，在横屏中滚动到导入入口。
- 本地数据覆盖记录更新/删除、数据库关闭后重新打开、各大组规则独立保存、课程周次冲突检查、周末课程和不重叠周次。
- 文件核心逻辑覆盖复制、重命名、级联删除、未关联副本清理、保留来源文件、复制途中失败回滚、错误层级拒绝以及本地路径校验。测试通过输入流验证复制逻辑，不能代替真机文件选择器验收。
- 设置覆盖 DeepSeek/ASR/OCR 独立保存、沙箱偏好持久化和 HTTPS 地址验证；拒绝包含密码或使用 HTTP 的地址。界面配置测试未输入密钥。
- 已检查 Android 视图渲染截图：五页、小屏 320×480 dp/150% 字体首页、640×360 dp 横屏首页和横屏编辑窗口。首页无纵向滚动；其他长内容可滚动。普通字号的 320 dp 周课表七天同屏。
- 编辑窗口根据实际窗口尺寸安排字段和操作区，字段可滚动。ViewModel 使用当前 Application 的显式工厂；测试结束关闭 Activity 和数据库，完整回归结果保持一致。

## 构建、签名与静态检查

- 应用包名 `cn.campus.notes`，min SDK 26，target/compile SDK 36。
- 主应用和设备测试 APK 的 `apksigner verify` 均成功；APK v2 签名有效。
- Lint：0 个错误、10 个警告。8 个为依赖新版本提示，1 个为使用 kapt 而非 KSP 的性能提示，1 个为基础启动图标 monochrome 提示。API 33 图标分支已提供 monochrome。未关闭检查或隐藏提示，固定本轮验证过的依赖组合。
- Room schema 已生成并保存在 `app/schemas/cn.campus.notes.data.CampusDatabase/1.json`。
- Manifest 不申请联网、录音、相机或广泛存储权限；FileProvider 仅暴露 App 私有 documents 子目录，并通过临时读取授权打开文件。系统备份与设备迁移备份已关闭。
- kapt 增量编译在未重新处理 Room 注解的任务中可能提示部分处理参数未被识别；已确认 schema 和生成代码存在，编译、测试和 Lint 均成功。

## 真机验证（2026-10-14）

- 已在 Android 16 / API 36 手机验证。新版 APK 使用 `adb install -r` 更新安装，保留现有应用数据。
- 4 项 Android 设备测试全部通过，报告为 `OK (4 tests)`：五栏切换与对话草稿重建、课程格子原地浮层、资料层级与返回路径、Keystore 加密解密及服务绑定检查。
- 设备测试直接读取现有课程和主题；只有记录、课程和资料全部为空时才载入标注的示例，不清理个人数据。没有相应课程或主题时对应用例显式跳过；本次 4 项均实际通过，无跳过。
- Android Keystore 测试使用测试字符串，验证密文、重新创建加密对象后的解密，以及错误服务类型无法解密；不读取或更改用户已保存的 API 密钥。
- 最终应用成功冷启动，本次 `am start -W` 返回 TotalTime 370 ms、WaitTime 374 ms。这是一次启动观察值，不是性能基准。
- 已检查 1200×2608 像素的真机首页截图：标题、三张卡片、快速记录、更多工具和五栏导航完整可见，状态栏和底部手势栏留有空间。最终应用停留在手机首页。
- 初次设备熄屏锁定，用户解锁后继续。此设备的 `startActivitySync` 测试启动入口仍等待，测试改用系统 `am start` 和空 Compose 测试规则启动后通过；仅测试窗口临时保持亮屏。没有修改手机全局调试安全设置。
- 设备证据：`artifacts/device/tests.log`、`artifacts/device/keystore.log`、`artifacts/device/home.png`。应用安装包未变，设备测试包已更新。

## 交付文件

以下安装包、日志和截图为本机验证产物，未提交到公开源码仓库。

| 文件 | 大小/用途 |
| --- | --- |
| `artifacts/campus-notes-0.2.0-debug.apk` | 12,846,131 字节，新版安装包 |
| `artifacts/campus-notes-debug.apk` | 同一新版安装包的固定名称副本 |
| `artifacts/campus-notes-device-tests.apk` | 1,087,848 字节，已执行的设备测试包 |
| `artifacts/campus-notes-0.1.0-debug.apk` | 保留的阶段一安装包 |
| `artifacts/SHA256SUMS.txt` | 新版应用与设备测试包校验值 |
| `artifacts/reports` | 本轮 JUnit XML 与 Lint 报告 |
| `artifacts/screenshots` | 本轮 Android 测试渲染截图 |

新版应用 SHA-256：`dac62d61be9264ba6aed6b991c931943266b94141447770d87cf6d6b1b9b5949`。

## 未验证与未接入

- 系统文件选择器实际授权、不同文档提供方读取、打开外部查看应用、软键盘，以及大字体和横屏在真机上的表现尚未完成专项验收。小屏/大字体/横屏的桌面 Android 渲染测试已通过；真机系统栏和正常字号首页已检查。
- Keystore 加密与解密已在真机验证。配置界面输入实际 API 密钥并保存、重新启动后的完整流程尚未执行；现有桌面配置测试只保存地址和模型名。
- DeepSeek 调用、流式回复、ASR、OCR、AI 检索、整理执行器、操作预览和执行日志尚未接入。保存配置不等于模型服务已可用。
- 沙箱开关只保存未来整理授权偏好。当前文件操作限于手动导入的 App 内副本；外部敏感修改执行器尚未实现。
- 学期开始日期、自动课表导入、单双周、非双节时段、复习、备份/恢复和云同步尚未实现。卸载应用会删除本地数据；AI 草稿仍是临时页面状态。

## 本机复现

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest -I work/test-network.init.gradle --offline
```

该命令使用本机已经下载的依赖与经校验的 Robolectric 离线运行包。SDK、代理参数与临时缓存都在本机忽略配置中；新机器按 README 从标准仓库获取依赖，无须使用此本机初始化脚本。

可使用 `.\gradlew.bat :app:connectedDebugAndroidTest` 执行设备测试。此机器缺少 Gradle UTP 主机组件的离线缓存，本轮使用以下等价的设备执行入口；测试 APK 已编译和安装：

```powershell
adb shell am instrument -w -r cn.campus.notes.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用现有资料或为空库载入示例，不自动清理真实内容。手机需点亮并解锁；只有测试窗口临时设置保持亮屏。
