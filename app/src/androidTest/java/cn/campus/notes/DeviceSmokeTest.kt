package cn.campus.notes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import cn.campus.notes.data.*
import cn.campus.notes.ui.CampusViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.After

/** Reuses personal data without modifying it. Examples are loaded only into a completely empty app. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var data: LocalData
    @Before fun readOrSeedEmptyDatabase() {
        instrumentation.uiAutomation.executeShellCommand("am start -W -n cn.campus.notes/.MainActivity").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        instrumentation.runOnMainSync {
            activity = resumedActivity() ?: error("Application did not reach the foreground")
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val repo = (activity.application as CampusApplication).repository
        data = runBlocking { repo.data.first() }
        if (data.notes.isEmpty() && data.courses.isEmpty() && data.nodes.isEmpty()) {
            runBlocking { repo.loadExamples() }
            data = runBlocking { repo.data.first() }
        }
        val vm = ViewModelProvider(activity)[CampusViewModel::class.java]
        compose.waitUntil(15_000) { vm.ready.value && vm.settingsReady.value && vm.data.value == data }
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
    }
    private fun resumedActivity(): MainActivity? = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
    @After fun finishTestWindow() { instrumentation.runOnMainSync { if (::activity.isInitialized) activity.finish() } }

    @Test fun launchNavigationDraftAndActivityRecreationWork() {
        for (tab in listOf("SCHEDULE", "LIBRARY", "MORE", "HOME", "AI")) {
            compose.onNodeWithTag("nav_$tab").performClick()
            compose.onNodeWithTag("${tab.lowercase()}_screen").assertIsDisplayed()
        }
        compose.onNodeWithTag("ai_draft").performTextInput("课堂问题")
        val previous = activity
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(15_000) {
            var next: MainActivity? = null
            instrumentation.runOnMainSync { next = resumedActivity() }
            next?.takeIf { it !== previous }?.also { activity = it } != null
        }
        compose.onNodeWithTag("ai_draft").assertTextContains("课堂问题")
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test fun existingCourseOpensAnchoredPopup() {
        assumeTrue("No course available; existing personal data is left unchanged", data.courses.isNotEmpty())
        val course = data.courses.minBy { it.firstWeek }
        compose.onNodeWithTag("nav_SCHEDULE").performClick()
        repeat(course.firstWeek - 1) { compose.onNodeWithText("下一周").performClick() }
        compose.onNodeWithTag("course_${course.id}").performScrollTo().performClick()
        compose.onNodeWithTag("course_details").assertIsDisplayed()
        compose.onNodeWithText("收起详情").performScrollTo().performClick()
        compose.onNodeWithTag("course_details").assertDoesNotExist()
    }

    @Test fun existingLibraryHierarchyAndBackWork() {
        val topic = data.nodes.firstOrNull { it.kind == NodeKind.TOPIC }
        assumeTrue("No topic available; existing personal data is left unchanged", topic != null)
        val subject = data.node(topic!!.parentId)!!
        val group = data.node(subject.parentId)!!
        val index = data.children(subject.id).indexOfFirst { it.id == topic.id }
        compose.onNodeWithTag("nav_LIBRARY").performClick()
        compose.onNodeWithTag("group_${group.id}").performScrollTo().performClick()
        compose.onNodeWithTag("subject_${subject.id}").performScrollTo().performClick()
        compose.onNodeWithTag("topic_$index").performScrollTo().performClick()
        compose.onNodeWithTag("originals_tab").assertIsSelected()
        compose.onNodeWithTag("outputs_tab").performClick()
        compose.onNodeWithTag("outputs_tab").assertIsSelected()
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("topic_$index").assertExists()
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("subject_${subject.id}").assertExists()
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
    }
}
