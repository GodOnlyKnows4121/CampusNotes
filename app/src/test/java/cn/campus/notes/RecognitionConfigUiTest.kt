package cn.campus.notes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import cn.campus.notes.data.*
import cn.campus.notes.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class RecognitionConfigUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=compose.activity.application as CampusApplication
    private lateinit var vm:CampusViewModel
    @After fun close(){val application=app;runBlocking{application.contentProcessor.shutdown()};compose.activityRule.scenario.close();application.database.close()}
    private fun await(condition:()->Boolean){compose.waitUntil(10_000){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();condition()}}
    private fun open(content:@Composable ()->Unit){
        vm=ViewModelProvider(compose.activity,ViewModelProvider.AndroidViewModelFactory(app))[CampusViewModel::class.java]
        compose.setContent{val data by vm.data.collectAsState();MaterialTheme{CompositionLocalProvider(LocalCampus provides vm,LocalContent provides data){content()}}}
        await{vm.settingsReady.value&&vm.ready.value};compose.waitForIdle()
    }
    @Test fun officialExampleChangesDraftOnlyUntilExplicitSave(){
        val old="https://maas.qianwenaiapi.com/apps/anthropic"
        runBlocking{app.settings.save(ProviderKind.ASR,old,"qwen","",false)}
        open{ModelSettingsDialog{}}
        compose.onNodeWithTag("provider_ASR").performClick()
        compose.onNodeWithTag("recognition_config_status").performScrollTo().assertTextContains("Anthropic",substring=true)
        compose.onNodeWithTag("recognition_example").performScrollTo().performClick()
        assertEquals(old,runBlocking{app.settings.data.first().providers[ProviderKind.ASR]!!.endpoint})
        compose.onNodeWithTag("model_name").performScrollTo().assertTextContains("qwen3-asr-flash")
        compose.onNodeWithTag("recognition_config_status").performScrollTo().assertTextContains("未联网",substring=true)
        compose.onNodeWithText("保存配置").performClick()
        await{!vm.busy.value&&vm.settings.value.providers[ProviderKind.ASR]?.model=="qwen3-asr-flash"}
        assertFalse(runBlocking{app.settings.data.first().providers[ProviderKind.ASR]!!.hasKey})
    }
    @Test fun invalidProtocolDisablesUploadEvenWithSavedKeyFlag(){
        val source=runBlocking{
            app.repository.importStream(app.repository.inboxTopic(),"协议测试.png","image/png",ByteArrayInputStream(byteArrayOf(1,2,3)))
            app.database.dao().nodes().single{it.kind==NodeKind.ORIGINAL}
        }
        open{LocalRecordDialog(source.id){}}
        compose.runOnIdle{vm.settings.value=vm.settings.value.copy(providers=vm.settings.value.providers+(ProviderKind.OCR to ProviderConfig("https://maas.qianwenaiapi.com/apps/anthropic","qwen",true)))}
        compose.onNodeWithText("主动图片识别 OCR").performScrollTo().performClick()
        compose.onNodeWithText("发送并处理").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking{app.database.content().forSource(source.id).isEmpty()})
    }
}
