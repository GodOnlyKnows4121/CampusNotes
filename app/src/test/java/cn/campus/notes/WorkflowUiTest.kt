package cn.campus.notes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import cn.campus.notes.data.*
import cn.campus.notes.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h568dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WorkflowUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=compose.activity.application as CampusApplication
    private lateinit var vm:CampusViewModel
    private lateinit var restore:StateRestorationTester
    private fun await(condition:()->Boolean){compose.waitUntil(10000){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();condition()}}
    @After fun close(){val a=app;runBlocking{a.contentProcessor.shutdown()};compose.activityRule.scenario.close();a.database.close()}
    private fun open(fontScale:Float=1f){
        vm=ViewModelProvider(compose.activity,ViewModelProvider.AndroidViewModelFactory(app))[CampusViewModel::class.java]
        restore=StateRestorationTester(compose)
        restore.setContent{val data by vm.data.collectAsState();val d=LocalDensity.current
            MaterialTheme{CompositionLocalProvider(LocalCampus provides vm,LocalContent provides data,LocalDensity provides Density(d.density,fontScale)){AiScreen()}}
        }
        await{vm.aiReady.value&&vm.settingsReady.value&&vm.sessions.value.isNotEmpty()}
    }
    private fun source()=runBlocking{
        app.repository.importStream(app.repository.inboxTopic(),"联动核对.txt","text/plain",ByteArrayInputStream("课堂合成文字".toByteArray()))
        app.database.dao().nodes().last{it.kind==NodeKind.ORIGINAL}
    }
    private fun approval(node:LibraryNode):WorkflowApproval=runBlocking{
        val m=WorkflowModel(LEGACY_CHAT,"https://example.invalid/v1","synthetic-model","test-revision")
        WorkflowApproval(sessionId=vm.activeChat.value,mode="chat",prompt="根据资料回答",chat=m,reasoning="default",asr=m,ocr=m,
            sources=listOf(WorkflowSource(node.id,node.title,node.mime,node.bytes,node.parentId,app.repository.sourceStamp(node),"parse")))
    }
    @Test fun attachmentDraftIsPersistentScopedAndNeverCallsModels(){
        val n=source();open();await{vm.data.value.nodes.any{it.id==n.id}}
        val first=vm.activeChat.value
        compose.onNodeWithTag("ai_attach").performClick()
        compose.onNodeWithTag("attach_${n.id}").performScrollTo().performClick()
        await{vm.attachments.value.any{it.sessionId==first&&it.sourceId==n.id}&&!vm.busy.value}
        compose.onNodeWithText("完成（1/10）").performClick()
        compose.onNodeWithTag("attached_${n.id}").assertIsDisplayed()
        compose.onNodeWithTag("ai_agent").performClick();compose.onNodeWithTag("ai_chat").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("attached_${n.id}").assertIsDisplayed()
        compose.onNodeWithTag("chat_sessions").performClick();compose.onNodeWithTag("new_chat_session").performClick()
        await{vm.sessions.value.size==2&&!vm.busy.value}
        val second=vm.activeChat.value
        compose.onNodeWithTag("select_session_$second").performScrollTo().performClick()
        await{vm.activeChat.value==second&&!vm.busy.value}
        compose.onNodeWithTag("attached_${n.id}").assertDoesNotExist()
        compose.onNodeWithTag("chat_sessions").performClick();compose.onNodeWithTag("select_session_$first").performScrollTo().performClick()
        await{vm.activeChat.value==first&&!vm.busy.value}
        compose.onNodeWithTag("attached_${n.id}").performClick()
        await{vm.attachments.value.isEmpty()&&!vm.busy.value}
        runBlocking{assertTrue(app.database.workflows().all().isEmpty());assertTrue(app.database.dao().chat().isEmpty());assertTrue(app.database.content().forSource(n.id).isEmpty())}
    }
    @Test fun cancellingScopePreviewDoesNotStartRequest(){
        val n=source();open();compose.runOnIdle{vm.workflowApproval.value=approval(n)}
        compose.onNodeWithText("本次联动范围").assertIsDisplayed()
        compose.onNodeWithTag("approve_workflow").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertNull(vm.workflowApproval.value)
        runBlocking{assertTrue(app.database.workflows().all().isEmpty());assertTrue(app.database.content().forSource(n.id).isEmpty())}
    }
    @Test fun workflowCannotContinueUntilEditedTextIsConfirmed(){
        val n=source();open();val a=approval(n)
        val result=ContentResult(sourceId=n.id,sourceStamp=a.sources.single().stamp,kind="parse",status="review",text="等待核对")
        runBlocking{app.database.content().save(result);app.database.workflows().insert(AiWorkflow(a.id,a.sessionId,a.json().toString(),status="review",results=WorkflowResult.encode(listOf(WorkflowResult(n.id,"parse",result.id)))))}
        await{vm.workflows.value.any{it.id==a.id}&&vm.contentResults.value.any{it.id==result.id}}
        compose.onNodeWithTag("workflow_${a.id}").performScrollTo()
        compose.onNodeWithText("核对文字并继续").performClick()
        compose.onNodeWithTag("resume_workflow").assertIsNotEnabled()
        screenshot("workflow-review-small")
        compose.onNodeWithTag("workflow_review_${result.id}").performScrollTo().performClick()
        compose.onNodeWithTag("content_text").performScrollTo().performTextReplacement("人工核对后的文字")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("content_text").assertTextContains("人工核对后的文字")
        compose.onNodeWithTag("confirm_content").performClick()
        await{vm.contentResults.value.any{it.id==result.id&&it.status=="confirmed"}&&!vm.busy.value}
        compose.onNodeWithTag("resume_workflow").assertIsEnabled()
        runBlocking{assertEquals("review",app.database.workflows().get(a.id)!!.status);assertTrue(app.database.dao().chat().isEmpty())}
        assertEquals("课堂合成文字",app.repository.document(n.localName!!).readText())
    }
    @Test @Config(qualifiers="w640dp-h360dp-land") fun previewActionsFitLargeFontLandscape(){
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        val n=source();open(1.5f);compose.runOnIdle{vm.workflowApproval.value=approval(n)}
        compose.onNodeWithTag("approve_workflow").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        screenshot("workflow-preview-landscape-large")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("ai_send").assertIsDisplayed()
    }
    private fun screenshot(name:String){compose.runOnIdle{
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEachIndexed{index,view->if(view.width>0&&view.height>0){
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap));val file=java.io.File("build/reports/screenshots/$name-$index.png");file.parentFile?.mkdirs()
            file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
    }}
}
