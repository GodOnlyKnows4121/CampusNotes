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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w320dp-h568dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatSessionsUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=compose.activity.application as CampusApplication
    private lateinit var vm:CampusViewModel
    private lateinit var restoration:StateRestorationTester
    private fun await(condition:()->Boolean){compose.waitUntil(10_000){
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }}
    @After fun close(){val application=app;compose.activityRule.scenario.close();application.database.close()}
    private fun open(fontScale:Float=1f){
        vm=ViewModelProvider(compose.activity,ViewModelProvider.AndroidViewModelFactory(app))[CampusViewModel::class.java]
        restoration=StateRestorationTester(compose)
        restoration.setContent{val data by vm.data.collectAsState();val density=LocalDensity.current
            MaterialTheme{CompositionLocalProvider(LocalCampus provides vm,LocalContent provides data,LocalDensity provides Density(density.density,fontScale)){AiScreen()}}
        }
        await{vm.aiReady.value&&vm.settingsReady.value&&vm.sessions.value.isNotEmpty()}
        compose.waitForIdle()
    }
    private fun idle(){await{!vm.busy.value};compose.waitForIdle()}
    @Test fun independentDraftsSurviveSwitchRenameDeleteAndStateRestore(){
        open();val first=vm.activeChat.value
        compose.onNodeWithTag("ai_draft").performTextInput("数学草稿")
        compose.onNodeWithTag("chat_sessions").performClick()
        compose.onNodeWithTag("new_chat_session").performClick()
        await{vm.sessions.value.size==2&&!vm.busy.value}
        val second=vm.activeChat.value
        compose.onNodeWithTag("select_session_$second").performScrollTo().performClick();idle()
        compose.onNodeWithTag("ai_draft").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString(""))).performTextInput("英语草稿")
        compose.onNodeWithTag("chat_sessions").performClick()
        compose.onNodeWithTag("rename_session_$first").performScrollTo().performClick()
        compose.onNodeWithTag("edit_0").performTextReplacement("数学会话")
        compose.onNodeWithTag("editor_save").performClick()
        await{vm.sessions.value.any{it.id==first&&it.title=="数学会话"}&&!vm.busy.value}
        compose.onNodeWithTag("select_session_$first").performScrollTo().performClick();idle()
        compose.onNodeWithTag("ai_draft").assertTextContains("数学草稿")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ai_draft").assertTextContains("数学草稿")
        compose.onNodeWithTag("ai_agent").performClick();compose.onNodeWithTag("ai_chat").performClick()
        compose.onNodeWithTag("ai_draft").assertTextContains("数学草稿")
        await{runBlocking{app.database.sessions().session(second)?.draft=="英语草稿"}}
        compose.onNodeWithTag("chat_sessions").performClick()
        compose.onNodeWithTag("delete_session_$first").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        await{vm.sessions.value.size==1&&!vm.busy.value}
        compose.onNodeWithTag("select_session_$second").performScrollTo().performClick();idle()
        compose.onNodeWithTag("ai_draft").assertTextContains("英语草稿")
        assertTrue(runBlocking{app.database.dao().chat().isEmpty()})
    }
    @Test fun profileEditorAndCapabilityMenuSaveOnlyLocalMetadata(){
        runBlocking{app.settings.save(ProviderKind.DEEPSEEK,"https://api.deepseek.com","deepseek-flash","",false)}
        open()
        compose.onNodeWithTag("chat_strength").performClick()
        compose.onNodeWithTag("choose_strength_deep").performClick()
        await{vm.sessions.value.single().reasoning=="deep"&&!vm.busy.value}
        compose.onNodeWithTag("chat_model").performClick();compose.onNodeWithText("管理语言模型").performClick()
        compose.onNodeWithTag("new_chat_profile").performClick()
        compose.onNodeWithTag("profile_title").performTextInput("备用语言模型")
        compose.onNodeWithTag("profile_endpoint").performScrollTo().performTextInput("https://chat.example.invalid/v1")
        compose.onNodeWithTag("profile_model").performScrollTo().performTextInput("test-model")
        compose.onNodeWithTag("save_chat_profile").performClick()
        await{vm.settings.value.chatProfiles.size==2&&!vm.busy.value}
        compose.onNodeWithText("返回").performClick()
        val profile=vm.settings.value.chatProfiles.single{it.id!=LEGACY_CHAT}
        compose.onNodeWithTag("chat_model").performClick();compose.onNodeWithTag("choose_profile_${profile.id}").performClick()
        await{vm.sessions.value.single().profileId==profile.id&&!vm.busy.value}
        assertEquals("default",vm.sessions.value.single().reasoning)
        compose.onNodeWithTag("chat_strength").performClick()
        compose.onNodeWithTag("choose_strength_default").assertIsDisplayed()
        compose.onNodeWithTag("choose_strength_deep").assertDoesNotExist()
        compose.onNodeWithTag("choose_strength_default").performClick();idle()
        assertFalse(profile.hasKey);assertTrue(runBlocking{app.database.dao().chat().isEmpty()})
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chat_model").assertTextContains("test-model",substring=true)
    }
    @Test @Config(qualifiers="w640dp-h360dp-land")
    fun largeFontLandscapeKeepsSessionModelAndSendControlsVisible(){
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f);open(1.5f)
        compose.onNodeWithTag("chat_sessions").assertIsDisplayed()
        compose.onNodeWithTag("ai_draft").assertIsDisplayed().performTextInput("大字体草稿")
        compose.onNodeWithTag("chat_strength").assertIsDisplayed()
        compose.onNodeWithTag("chat_model").assertIsDisplayed()
        compose.onNodeWithTag("ai_send").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ai_draft").assertTextContains("大字体草稿")
        compose.runOnIdle{android.view.inspector.WindowInspector.getGlobalWindowViews().forEachIndexed{index,view->if(view.width>0&&view.height>0){
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap));val file=java.io.File("build/reports/screenshots/sessions-landscape-large-$index.png");file.parentFile?.mkdirs()
            file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}}
    }
}
