package cn.campus.notes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.platform.LocalDensity
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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as CampusApplication
    private lateinit var restoration: StateRestorationTester
    @After fun close() { val application=app; runBlocking { application.contentProcessor.shutdown() }; compose.activityRule.scenario.close(); application.database.close() }
    private fun open(source: LibraryNode, fontScale: Float = 1f) {
        val vm = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory(app))[CampusViewModel::class.java]
        restoration=StateRestorationTester(compose)
        restoration.setContent { val data by vm.data.collectAsState();val density=LocalDensity.current
            MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale),LocalCampus provides vm, LocalContent provides data) { LocalRecordDialog(source.id) {} } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(source.title).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun extractedDocumentIsEditableAndRequiresConfirmation() {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("word/document.xml")); it.write("<d><p><t>原始解析文字</t></p></d>".toByteArray()); it.closeEntry()
        } }.toByteArray()
        val source = runBlocking {
            app.repository.importStream(app.repository.inboxTopic(),"核对测试.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",ByteArrayInputStream(bytes))
            app.database.dao().nodes().last { it.kind == NodeKind.ORIGINAL }
        }
        open(source)
        compose.onNodeWithTag("extract_text").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { app.database.content().forSource(source.id).firstOrNull()?.status == "review" } }
        assertTrue(runBlocking { app.database.content().confirmed().isEmpty() })
        compose.onNodeWithText("查看 / 核对文字").performScrollTo().performClick()
        compose.onNodeWithTag("content_text").performScrollTo().performTextReplacement("人工修正后的重点")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("content_text").assertTextContains("人工修正后的重点")
        screenshot("content-review-small")
        compose.onNodeWithTag("confirm_content").performClick()
        compose.waitUntil(10_000) { runBlocking { app.database.content().forSource(source.id).firstOrNull()?.status == "confirmed" } }
        assertTrue(runBlocking { app.repository.organizeSnapshot(source.parentId!!).text.contains("人工修正后的重点") })
        assertArrayEquals(bytes,app.repository.document(source.localName!!).readBytes())
    }
    @Test fun importedImageDoesNotRecognizeAutomaticallyAndMissingConfigCannotUpload() {
        val source = runBlocking {
            app.repository.importStream(app.repository.inboxTopic(),"测试照片.jpg","image/jpeg",ByteArrayInputStream(byteArrayOf(1,2,3)))
            app.database.dao().nodes().last { it.kind == NodeKind.ORIGINAL }
        }
        open(source)
        assertTrue(runBlocking { app.database.content().forSource(source.id).isEmpty() })
        compose.onNodeWithText("主动图片识别 OCR").performScrollTo().performClick()
        compose.onNodeWithText("发送并处理").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking { app.database.content().forSource(source.id).isEmpty() })
    }
    @Test @Config(qualifiers = "w640dp-h360dp-land")
    fun reviewActionsRemainVisibleWithLargeFontInLandscape() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        val source=runBlocking {
            app.repository.importStream(app.repository.inboxTopic(),"长文字.txt","text/plain",ByteArrayInputStream("原件".toByteArray()))
            val node=app.database.dao().nodes().last{it.kind==NodeKind.ORIGINAL}
            app.database.content().save(ContentResult(sourceId=node.id,sourceStamp=app.repository.sourceStamp(node),kind="parse",status="review",text="等待核对的课程文字\n".repeat(100)))
            node
        }
        open(source,1.5f)
        compose.onNodeWithText("查看 / 核对文字").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_content").assertIsDisplayed()
        compose.onNodeWithTag("content_review_back").assertIsDisplayed()
        screenshot("content-review-landscape-large-font")
    }
    private fun screenshot(name: String) { compose.runOnIdle {
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEachIndexed { index, view -> if(view.width>0&&view.height>0) {
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file=java.io.File("build/reports/screenshots/$name-$index.png");file.parentFile?.mkdirs()
            file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        } }
    } }
}
