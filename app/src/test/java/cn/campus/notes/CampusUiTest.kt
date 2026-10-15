package cn.campus.notes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.Density
import cn.campus.notes.ui.CampusApp
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CampusUiTest {
    @After fun closeDatabase() {
        val app = compose.activity.application as CampusApplication
        compose.activityRule.scenario.close()
        app.database.close()
    }
    @Before fun seed() { runBlocking { (compose.activity.application as CampusApplication).repository.loadExamples() } }
    private fun awaitHome() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty() } }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        val file = File("build/reports/screenshots/$name.png")
        file.parentFile?.mkdirs()
        // Draw the Android view directly: PixelCopy's forced window redraw times out in Robolectric.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun fiveDestinationsAndCoursePopupWork() {
        compose.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
        screenshot("home-small")
        compose.onNodeWithTag("nav_SCHEDULE").performClick()
        compose.onNodeWithTag("schedule_screen").assertIsDisplayed()
        screenshot("schedule-small")
        compose.onNodeWithTag("course_math").performClick()
        compose.onNodeWithTag("course_details").assertIsDisplayed()
        compose.onNodeWithText("教师：示例教师").assertIsDisplayed()
        compose.onNodeWithText("收起详情").performClick()
        compose.onNodeWithTag("course_details").assertDoesNotExist()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_screen").assertIsDisplayed()
        screenshot("ai-small")
        compose.onNodeWithTag("ai_agent").performClick()
        compose.onNodeWithTag("agent_generate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("library_screen").assertIsDisplayed()
        screenshot("library-small")
        compose.onNodeWithTag("nav_MORE").performClick()
        compose.onNodeWithTag("more_screen").assertIsDisplayed()
        screenshot("more-small")
    }

    @Test fun newlySavedOriginalAppearsAheadOfOlderTextNotes() {
        val app=compose.activity.application as CampusApplication
        runBlocking {
            app.database.dao().notes().forEach{app.database.dao().save(it.copy(updatedAt=1))}
            app.repository.importStream(app.repository.inboxTopic(),"最新采集原件.txt","text/plain",java.io.ByteArrayInputStream("合成内容".toByteArray()))
        }
        compose.setContent { CampusApp() };awaitHome()
        compose.waitUntil(10_000){compose.onAllNodesWithText("最新采集原件.txt").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("最新采集原件.txt").assertIsDisplayed().performClick()
        compose.onNodeWithTag("extract_text").performScrollTo().assertIsDisplayed()
    }

    @Test fun groupRulesAndLibraryHierarchyAreIndependent() {
        compose.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("group_work").performClick()
        compose.onNodeWithTag("group_rule").performClick()
        compose.onNodeWithText("按项目 → 任务整理，保留来源和版本，不覆盖原件。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("subject_project").performClick()
        compose.onNodeWithTag("topic_0").performClick()
        compose.onNodeWithTag("originals_tab").assertIsSelected()
        compose.onNodeWithTag("outputs_tab").performClick()
        compose.onNodeWithTag("empty_outputs").assertIsDisplayed()
        compose.onNodeWithTag("group_study").performClick()
        compose.onNodeWithTag("subject_math").assertIsDisplayed()
        compose.onNodeWithTag("subject_project").assertDoesNotExist()
    }

    @Test fun enlargedCourseKeepsInsideTapsAndClosesOnBlank() {
        compose.setContent { CampusApp() };awaitHome()
        compose.onNodeWithTag("nav_SCHEDULE").performClick()
        compose.onNodeWithTag("course_math").performClick()
        compose.onNodeWithTag("course_details").performClick()
        compose.onNodeWithTag("course_details").assertIsDisplayed()
        screenshot("course-expanded")
        compose.onNodeWithTag("course_blank").performTouchInput { click(androidx.compose.ui.geometry.Offset(width-3f,height-3f)) }
        compose.onNodeWithTag("course_details").assertDoesNotExist()
    }

    @Test fun realSemesterCalendarSelectsTeachingWeek() {
        val app=compose.activity.application as CampusApplication
        val now=java.time.LocalDate.now()
        val start=now.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(6)
        val term=cn.campus.notes.data.Semester("term","秋季学期",start.toString())
        val target=now.withDayOfMonth(1)
        runBlocking { app.repository.saveSemester(term);app.settings.activeSemester("term") }
        compose.setContent { CampusApp() };awaitHome()
        compose.onNodeWithTag("nav_SCHEDULE").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("秋季学期").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("week_calendar").performClick()
        compose.onNodeWithTag("semester_calendar").assertIsDisplayed()
        compose.onNodeWithTag("date_$target").performClick()
        compose.onNodeWithText("第 ${term.week(target)} 周 ⌄").assertIsDisplayed()
        compose.onNodeWithTag("semester_calendar").assertDoesNotExist()
    }

    @Test fun campusTodoCanBeCreatedCompletedAndMovedToTrash() {
        val app=compose.activity.application as CampusApplication
        compose.setContent { CampusApp() };awaitHome()
        compose.onNodeWithText("进入校园 →").performClick()
        compose.onNodeWithText("新增").performScrollTo().performClick()
        compose.onNodeWithTag("todo_title").performTextInput("复习极限")
        compose.onNodeWithTag("todo_body").performTextInput("完成课后练习")
        compose.onNodeWithTag("todo_save").performClick()
        compose.waitUntil(10_000){runBlocking{app.database.features().allTodos().size==1}}
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.database.features().allTodos().single().done}}
        compose.onNodeWithText("移入回收站").performScrollTo().performClick()
        compose.waitUntil(10_000){runBlocking{app.database.features().allTodos().isEmpty()}}
        runBlocking{org.junit.Assert.assertEquals("复习极限",app.database.features().allTrash().single().title)}
    }

    @Test fun tabSwitchAndSavedStateRestoreKeepDraftAndSelection() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_draft").performTextInput("复习导数")
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("group_work").performClick()
        compose.onNodeWithTag("subject_project").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("group_work").assertIsSelected()
        compose.onNodeWithTag("topic_0").assertIsDisplayed()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("复习导数")
    }

    @Test fun recordEditorAndGroupRuleWriteRealLocalData() {
        compose.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("new_note").performClick()
        compose.onNodeWithTag("edit_0").performTextInput("今天的课堂记录")
        compose.onNodeWithTag("edit_1").performTextInput("记住导数定义")
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("记住导数定义").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("group_rule").performClick()
        compose.onNodeWithTag("edit_1").performTextReplacement("按章节整理，保留原件")
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("group_rule").performClick()
        compose.onNodeWithTag("edit_1").assertTextContains("按章节整理，保留原件")
        compose.onNodeWithText("取消").performClick()
        val repo = (compose.activity.application as CampusApplication).repository
        compose.runOnIdle {
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[cn.campus.notes.ui.CampusViewModel::class.java]
            org.junit.Assert.assertSame(repo, vm.repository)
        }
        runBlocking {
            org.junit.Assert.assertEquals("记住导数定义", repo.db.dao().notes().single().body)
            org.junit.Assert.assertEquals("按章节整理，保留原件", repo.db.dao().nodes().first { it.id == "study" }.rule)
            org.junit.Assert.assertEquals("按项目 → 任务整理，保留来源和版本，不覆盖原件。", repo.db.dao().nodes().first { it.id == "work" }.rule)
        }
    }

    @Test fun modelSettingsSaveAndReopenFromMore() {
        compose.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("nav_MORE").performClick()
        compose.onNodeWithTag("more_models").performClick()
        compose.onNodeWithTag("model_endpoint").performTextReplacement("https://example.com/v1")
        compose.onNodeWithTag("model_name").performScrollTo().performTextReplacement("test-chat")
        compose.onNodeWithText("保存配置").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("model_endpoint").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("more_models").performClick()
        compose.onNodeWithTag("model_endpoint").assertTextContains("https://example.com/v1")
        compose.onNodeWithTag("model_name").assertTextContains("test-chat")
    }

    @Test fun switchingAiModesKeepsDraftAndMissingKeyShowsRealError() {
        compose.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_draft").performTextInput("待发送的问题")
        compose.onNodeWithTag("ai_agent").performClick()
        compose.onNodeWithTag("ai_chat").performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("待发送的问题")
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[cn.campus.notes.ui.CampusViewModel::class.java]
        compose.waitUntil(10_000) { vm.aiReady.value }
        compose.onNodeWithTag("ai_send").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("操作未完成").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("待发送的问题")
        runBlocking { org.junit.Assert.assertTrue((compose.activity.application as CampusApplication).database.dao().chat().isEmpty()) }
    }

    @Test fun agentPreviewConfirmationCreatesRealOutputAndPreservesOriginal() {
        val app = compose.activity.application as CampusApplication
        val task = runBlocking {
            java.io.ByteArrayInputStream("课堂文本".toByteArray()).use { app.repository.importStream("math-0", "课堂.txt", "text/plain", it) }
            val source = app.repository.organizeSnapshot("math-0")
            cn.campus.notes.data.OrganizeTask(topicId = source.topicId, topicTitle = source.topicTitle, rule = source.rule,
                sources = source.sources, sourceStamp = source.stamp, title = "测试预览", body = "测试整理结果", status = "preview")
                .also { app.database.dao().save(it) }
        }
        compose.setContent { CampusApp() }; awaitHome()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_agent").performClick()
        compose.onNodeWithText("查看操作预览").performScrollTo().performClick()
        compose.onNodeWithText("测试整理结果").assertIsDisplayed()
        screenshot("agent-preview")
        compose.onNodeWithText("确认写入沙箱").performClick()
        compose.waitUntil(10_000) { runBlocking { app.database.dao().task(task.id)?.status == "complete" } }
        runBlocking {
            org.junit.Assert.assertEquals(1, app.database.dao().nodes().count { it.kind == cn.campus.notes.data.NodeKind.OUTPUT })
            val original = app.database.dao().nodes().single { it.kind == cn.campus.notes.data.NodeKind.ORIGINAL }
            org.junit.Assert.assertEquals("课堂文本", app.repository.document(original.localName!!).readText())
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun landscapeChatKeepsDraftAndSendVisible() {
        compose.setContent { CampusApp() }; awaitHome()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_draft").assertIsDisplayed()
        compose.onNodeWithTag("ai_send").assertIsDisplayed()
        screenshot("ai-landscape")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h480dp-port")
    fun smallScreenWithLargeFontKeepsHomeActionsVisibleWithoutScroll() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) { CampusApp() }
        }
        awaitHome()
        compose.onNodeWithTag("capture").assertIsDisplayed()
        compose.onNodeWithTag("nav_MORE").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("home_screen")), useUnmergedTree = true).assertCountEquals(0)
        screenshot("home-large-font")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun landscapeKeepsFixedHomeActionsVisible() {
        compose.setContent { CampusApp() }
        awaitHome()
        compose.onNodeWithTag("capture").assertIsDisplayed()
        compose.onNodeWithText("我的校园").assertIsDisplayed()
        compose.onNodeWithText("最近记录").assertIsDisplayed()
        compose.onNodeWithTag("nav_MORE").assertIsDisplayed()
        screenshot("home-landscape")
    }
}
