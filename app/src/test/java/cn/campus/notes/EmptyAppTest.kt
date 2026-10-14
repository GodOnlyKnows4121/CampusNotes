package cn.campus.notes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EmptyAppTest {
    @After fun closeDatabase() {
        val app = compose.activity.application as CampusApplication
        compose.activityRule.scenario.close()
        app.database.close()
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun emptyFirstLaunchCanBuildLibraryHierarchyInLandscape() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithText("＋ 大组").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEachIndexed { index, view ->
                if (view.width > 0 && view.height > 0) {
                    val bitmap = android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    val file = java.io.File("build/reports/screenshots/library-editor-landscape-$index.png")
                    file.parentFile?.mkdirs()
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
        }
        compose.onNodeWithTag("edit_0").performTextInput("我的学期")
        save()
        compose.onNodeWithText("新增学科").performScrollTo().performClick()
        compose.onNodeWithTag("edit_0").performTextInput("数学")
        save()
        compose.onNodeWithText("数学").performScrollTo().performClick()
        compose.onNodeWithText("新增课堂主题").performScrollTo().performClick()
        compose.onNodeWithTag("edit_0").performTextInput("微积分")
        save()
        compose.onNodeWithTag("topic_0").performScrollTo().performClick()
        compose.onNodeWithTag("import_document").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂无原件").assertExists()
    }
    private fun save() {
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
        val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[cn.campus.notes.ui.CampusViewModel::class.java]
        compose.waitUntil(10_000) { !vm.busy.value && vm.message.value == null }
        compose.waitForIdle()
    }
}
