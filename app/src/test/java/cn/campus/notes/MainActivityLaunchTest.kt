package cn.campus.notes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Exercises the real launcher Activity under Robolectric, not an on-device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityLaunchTest {
    @After fun closeDatabase() {
        val app = compose.activity.application as CampusApplication
        compose.activityRule.scenario.close()
        app.database.close()
    }
    @Before fun seed() { runBlocking { (compose.activity.application as CampusApplication).repository.loadExamples() } }
    private fun awaitHome() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().isNotEmpty() } }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launcherBackAndActivityRecreationWork() {
        awaitHome()
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("subject_math").performClick()
        compose.onNodeWithTag("topic_0").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("originals_tab").assertIsSelected()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("topic_0").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("subject_math").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitHome()
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
    }
}
