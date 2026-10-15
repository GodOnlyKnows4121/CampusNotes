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

/** Reuses personal data without modifying it; never inserts examples into the user's database. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var data: LocalData
    private lateinit var originalSession: String
    private val ownedSessions = mutableListOf<String>()
    private val ownedSources = mutableListOf<LibraryNode>()
    @Before fun readExistingDatabase() {
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n cn.campus.notes/.MainActivity"
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        compose.waitUntil(15_000) {
            var resumed = false
            instrumentation.runOnMainSync { resumed = resumedActivity() != null }
            resumed
        }
        instrumentation.runOnMainSync {
            activity = resumedActivity() ?: error("Application did not reach the foreground")
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val repo = (activity.application as CampusApplication).repository
        data = runBlocking { repo.data.first() }
        val vm = ViewModelProvider(activity)[CampusViewModel::class.java]
        compose.waitUntil(15_000) { vm.ready.value && vm.settingsReady.value && vm.aiReady.value && vm.sessions.value.isNotEmpty() && vm.data.value == data }
        // Drafts are persistent now: never type into or clear the user's current session.
        originalSession = runBlocking { (activity.application as CampusApplication).settings.data.first().activeChatSession }
        val fixture = runBlocking { vm.sessionStore.create(vm.currentSession.value?.profileId ?: LEGACY_CHAT) }
        ownedSessions += fixture.id
        runBlocking { vm.settingsStore.activeChatSession(fixture.id) }
        instrumentation.runOnMainSync { vm.activeChat.value = fixture.id }
        compose.waitUntil(15_000) { vm.sessions.value.any { it.id == fixture.id } }
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
    }
    private fun resumedActivity(): MainActivity? = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
    @After fun finishTestWindow() {
        if (!::activity.isInitialized) return
        val app = activity.application as CampusApplication
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        runBlocking {
            ownedSessions.forEach { id -> app.database.sessions().clear(id); app.database.sessions().delete(id) }
            ownedSources.forEach { node ->
                app.database.content().deleteSources(listOf(node.id));app.database.dao().deleteNode(node.id)
                node.localName?.let { app.repository.document(it).delete() }
            }
            if (::originalSession.isInitialized) app.settings.activeChatSession(originalSession)
        }
    }

    @Test fun independentSessionDraftAndThinkingMenuWorkWithoutModelCalls() {
        val vm = ViewModelProvider(activity)[CampusViewModel::class.java]
        val first = vm.activeChat.value
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_draft").performTextInput("隔离会话草稿")
        compose.onNodeWithTag("chat_sessions").performClick()
        val count = vm.sessions.value.size
        compose.onNodeWithTag("new_chat_session").performClick()
        compose.waitUntil(15_000) { vm.sessions.value.size == count + 1 && !vm.busy.value }
        val second = vm.activeChat.value
        ownedSessions += second
        compose.onNodeWithTag("select_session_$second").performScrollTo().performClick()
        compose.onNodeWithTag("ai_draft").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
            .performTextInput("第二会话草稿")
        val profile=vm.settings.value.chatProfiles.find{it.id==vm.sessions.value.first{it.id==second}.profileId}
        val level=cn.campus.notes.integration.ModelCapabilities.levels(profile?.endpoint.orEmpty(),profile?.model.orEmpty()).last()
        compose.onNodeWithTag("chat_strength").performClick()
        compose.onNodeWithTag("choose_strength_${level.id}").performClick()
        compose.waitUntil(15_000){vm.sessions.value.any{it.id==second&&it.reasoning==level.id}&&!vm.busy.value}
        compose.onNodeWithTag("chat_sessions").performClick()
        compose.onNodeWithTag("select_session_$first").performScrollTo().performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("隔离会话草稿")
        compose.onNodeWithTag("chat_sessions").performClick()
        compose.onNodeWithTag("select_session_$second").performScrollTo().performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("第二会话草稿")
        val app=activity.application as CampusApplication
        org.junit.Assert.assertTrue(runBlocking{app.database.sessions().messages(first).isEmpty()&&app.database.sessions().messages(second).isEmpty()})
    }

    @Test fun syntheticAttachmentAndReviewGateWorkWithoutModelCalls() {
        val app=activity.application as CampusApplication
        val vm=ViewModelProvider(activity)[CampusViewModel::class.java]
        val source=runBlocking {
            val node=LibraryNode(parentId=app.repository.inboxTopic(),kind=NodeKind.ORIGINAL,title="联动界面测试.txt",mime="text/plain",localName=newId(),bytes=12)
            ownedSources+=node
            app.repository.document(node.localName!!).writeText("Synthetic UI")
            app.repository.saveNode(node);node
        }
        compose.waitUntil(15_000){vm.data.value.nodes.any{it.id==source.id}}
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_attach").performClick()
        compose.onNodeWithTag("attach_${source.id}").performScrollTo().performClick()
        compose.waitUntil(15_000){vm.attachments.value.any{it.sourceId==source.id}&&!vm.busy.value}
        compose.onNodeWithText("完成（1/10）").performClick()
        compose.onNodeWithTag("attached_${source.id}").assertIsDisplayed()
        compose.onNodeWithTag("nav_HOME").performClick();compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("attached_${source.id}").assertIsDisplayed().performClick()
        compose.waitUntil(15_000){vm.attachments.value.none{it.sourceId==source.id}&&!vm.busy.value}
        val model=WorkflowModel(LEGACY_CHAT,"https://example.invalid/v1","synthetic-model","fixture")
        val approval=WorkflowApproval(sessionId=vm.activeChat.value,mode="chat",prompt="合成资料核对测试",chat=model,reasoning="default",asr=model,ocr=model,
            sources=listOf(WorkflowSource(source.id,source.title,source.mime,source.bytes,source.parentId,app.repository.sourceStamp(source),"parse")))
        val result=ContentResult(sourceId=source.id,sourceStamp=approval.sources.single().stamp,kind="parse",status="review",text="Synthetic UI")
        runBlocking{
            app.database.content().save(result)
            app.database.workflows().insert(AiWorkflow(approval.id,approval.sessionId,approval.json().toString(),status="review",results=WorkflowResult.encode(listOf(WorkflowResult(source.id,"parse",result.id)))))
        }
        compose.waitUntil(15_000){vm.workflows.value.any{it.id==approval.id}&&vm.contentResults.value.any{it.id==result.id}}
        compose.onNodeWithText("核对文字并继续").performScrollTo().performClick()
        compose.onNodeWithTag("resume_workflow").assertIsNotEnabled()
        compose.onNodeWithTag("workflow_review_${result.id}").performScrollTo().performClick()
        compose.onNodeWithTag("content_text").performScrollTo().performTextReplacement("Synthetic UI confirmed")
        compose.onNodeWithTag("confirm_content").performClick()
        compose.waitUntil(15_000){vm.contentResults.value.any{it.id==result.id&&it.status=="confirmed"}&&!vm.busy.value}
        compose.onNodeWithTag("resume_workflow").assertIsEnabled()
        compose.onNodeWithText("取消待核对任务").performScrollTo().performClick()
        compose.waitUntil(15_000){vm.workflows.value.any{it.id==approval.id&&it.status=="cancelled"}&&!vm.busy.value}
        org.junit.Assert.assertTrue(runBlocking{app.database.sessions().messages(approval.sessionId).isEmpty()})
        org.junit.Assert.assertEquals("Synthetic UI",app.repository.document(source.localName!!).readText())
    }

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
        compose.onNodeWithTag("ai_draft").performTextClearance()
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test fun existingCourseOpensAnchoredPopup() {
        assumeTrue("No course available; existing personal data is left unchanged", data.courses.isNotEmpty())
        val app = activity.application as CampusApplication
        val (activeSemester, placements) = runBlocking {
            app.settings.data.first().activeSemester to app.database.features().allPlacements()
        }
        val course = data.courses.filter { candidate ->
            (placements.find { it.courseId == candidate.id }?.semesterId) == activeSemester
        }.minByOrNull { it.firstWeek }
        assumeTrue("No course in the active semester; saved semester is left unchanged", course != null)
        val selected = course!!
        instrumentation.runOnMainSync {
            ViewModelProvider(activity)[CampusViewModel::class.java].scheduleFocus.value = selected.id
        }
        compose.onNodeWithTag("nav_SCHEDULE").performClick()
        compose.onNodeWithText("收起详情").performScrollTo().performClick()
        compose.onNodeWithTag("course_${selected.id}").performClick()
        compose.onNodeWithTag("course_details").assertIsDisplayed()
        compose.onNodeWithTag("course_details").performClick()
        compose.onNodeWithTag("course_details").assertIsDisplayed()
        compose.onNodeWithText("收起详情").performScrollTo().performClick()
        compose.onNodeWithTag("course_details").assertDoesNotExist()
    }

    @Test fun aiModesKeepDraftAndAgentBoundaryIsVisible() {
        compose.onNodeWithTag("nav_AI").performClick()
        compose.onNodeWithTag("ai_draft").performTextInput("真机临时草稿")
        compose.onNodeWithTag("ai_agent").performClick()
        compose.onNodeWithTag("agent_generate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("执行边界：", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai_chat").performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("真机临时草稿")
        compose.onNodeWithTag("ai_send").assertIsDisplayed()
        compose.onNodeWithTag("ai_draft").performTextClearance()
    }

    @Test fun existingLibraryHierarchyAndBackWork() {
        val topic = data.nodes.firstOrNull { it.kind == NodeKind.TOPIC && it.id != "inbox-topic" }
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
