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
        compose.onNodeWithText("开始整理 · 尚未实现").assertIsNotEnabled()
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("library_screen").assertIsDisplayed()
        screenshot("library-small")
        compose.onNodeWithTag("nav_MORE").performClick()
        compose.onNodeWithTag("more_screen").assertIsDisplayed()
        screenshot("more-small")
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
        compose.onNodeWithText("今日课程").assertIsDisplayed()
        compose.onNodeWithText("最近资料").assertIsDisplayed()
        compose.onNodeWithTag("nav_MORE").assertIsDisplayed()
        screenshot("home-landscape")
    }
}
