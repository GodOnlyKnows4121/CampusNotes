package cn.campus.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentProcessingTest {
    private lateinit var context: Context
    private lateinit var db: CampusDatabase
    private lateinit var repo: LocalRepository
    private lateinit var parser: DocumentParser
    private val processors = mutableListOf<ContentProcessor>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        repo = LocalRepository(context, db); parser = DocumentParser(context)
    }
    @After fun close() { runBlocking { processors.forEach { it.shutdown() } }; db.close() }
    private fun processor(extract: ((File, LibraryNode) -> String)? = null): ContentProcessor =
        (if (extract == null) ContentProcessor(context, repo, SettingsStore(context)) else ContentProcessor(context, repo, SettingsStore(context), extract)).also { processors += it }
    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() } }
    }.toByteArray()
    private suspend fun source(title: String, bytes: ByteArray, mime: String = "application/octet-stream"): LibraryNode {
        repo.importStream(repo.inboxTopic(), title, mime, ByteArrayInputStream(bytes))
        return db.dao().nodes().last { it.kind == NodeKind.ORIGINAL }
    }
    private suspend fun finalResult(id: String): ContentResult = withTimeout(10_000) {
        while (true) {
            val result = db.content().forSource(id).firstOrNull()
            if (result != null && result.status !in setOf("waiting", "running")) return@withTimeout result
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Must reject unsupported content") } catch (_: IllegalArgumentException) { } }

    @Test fun localTextAndUtf16DecodeWithoutChangingOriginal() = runBlocking {
        for ((name, bytes) in listOf("a.txt" to "校园文字".toByteArray(), "b.md" to (byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "复习提纲".toByteArray(Charsets.UTF_16LE)))) {
            val node = source(name, bytes)
            assertTrue(parser.extract(repo.document(node.localName!!), node).isNotBlank())
            assertArrayEquals(bytes, repo.document(node.localName!!).readBytes())
        }
        val binary = source("bad.txt", byteArrayOf(0, 1, 2))
        rejected { parser.extract(repo.document(binary.localName!!), binary) }
    }
    @Test fun docxExtractsParagraphsTablesAndFormulaText() = runBlocking {
        val bytes = zip("word/document.xml" to """<w:document xmlns:w="urn:w" xmlns:m="urn:m"><w:p><w:r><w:t>函数与极限</w:t><w:tab/><w:t>重点</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>例题</w:t></w:r></w:p></w:tc></w:tr></w:tbl><m:t>x+1</m:t></w:document>""")
        val node = source("课堂.docx", bytes)
        val text = parser.extract(repo.document(node.localName!!), node)
        assertTrue(text.contains("函数与极限\t重点")); assertTrue(text.contains("例题")); assertTrue(text.contains("x+1"))
    }
    @Test fun pptxUsesNumericSlideOrder() = runBlocking {
        fun slide(text: String) = """<p:sld xmlns:p="urn:p" xmlns:a="urn:a"><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:sld>"""
        val node = source("课程.pptx", zip("ppt/slides/slide10.xml" to slide("第十张"), "ppt/slides/slide2.xml" to slide("第二张")))
        val text = parser.extract(repo.document(node.localName!!), node)
        assertTrue(text.indexOf("第二张") < text.indexOf("第十张"))
    }
    @Test fun actualPdfTextAndScannedPageHaveDistinctOutcomes() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream().also { out -> PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { stream -> stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(50f,700f); stream.showText("Campus PDF text"); stream.endText() }
            doc.save(out)
        } }.toByteArray()
        val node = source("readable.pdf", bytes)
        assertTrue(parser.extract(repo.document(node.localName!!), node).contains("Campus PDF text"))
        val empty = ByteArrayOutputStream().also { out -> PDDocument().use { it.addPage(PDPage()); it.save(out) } }.toByteArray()
        val scanned = source("scanned.pdf", empty)
        rejected { parser.extract(repo.document(scanned.localName!!), scanned) }
    }
    @Test fun externalEntitiesDecompressionAndLegacyFormatsAreRejected() = runBlocking {
        val evil = source("bad.docx", zip("word/document.xml" to """<!DOCTYPE d [<!ENTITY secret SYSTEM "file:///private">]><d><t>&secret;</t></d>"""))
        rejected { parser.extract(repo.document(evil.localName!!), evil) }
        val bomb = source("large.docx", zip("word/document.xml" to "x".repeat(2_000_001)))
        rejected { parser.extract(repo.document(bomb.localName!!), bomb) }
        for (ext in listOf("doc", "ppt", "bin")) {
            val old = source("legacy.$ext", "legacy".toByteArray())
            rejected { parser.extract(repo.document(old.localName!!), old) }
        }
    }
    @Test fun parseReviewConfirmAndSourceBoundNoteAreSeparate() = runBlocking {
        val bytes = zip("word/document.xml" to "<d><p><t>待核对正文</t></p></d>")
        val node = source("课堂.docx", bytes)
        val service = processor(); service.start(node.id,"parse")
        val result = finalResult(node.id)
        assertEquals("review", result.status)
        assertTrue(db.content().confirmed().isEmpty())
        try { repo.organizeSnapshot(node.parentId!!); fail() } catch (_: IllegalArgumentException) { }
        service.confirm(result.id, "修正并确认的正文")
        assertTrue(repo.organizeSnapshot(node.parentId!!).text.contains("修正并确认的正文"))
        service.saveAsNote(result.id)
        val saved = db.content().result(result.id)!!
        assertEquals("修正并确认的正文", db.dao().notes().single { it.id == saved.outputNoteId }.body)
        try { service.saveAsNote(result.id); fail() } catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, repo.document(node.localName!!).readBytes())
        repo.deleteNode(node.id)
        assertEquals("confirmed", db.content().result(result.id)!!.status)
        repo.restoreTrash(db.features().allTrash().single().id)
        assertTrue(repo.organizeSnapshot(node.parentId!!).text.contains("修正并确认的正文"))
    }
    @Test fun sourceChangesInvalidateConfirmationAndOrganizePlan() = runBlocking {
        val node = source("record.txt", "原正文".toByteArray(),"text/plain")
        val service = processor(); service.start(node.id, "parse"); val result = finalResult(node.id)
        service.confirm(result.id, result.text)
        repo.document(node.localName!!).writeText("原件已变化")
        try { service.confirm(result.id,"改写"); fail() } catch (_: IllegalArgumentException) { }
        try { repo.organizeSnapshot(node.parentId!!); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun latestUserConfirmationSelectsEffectiveTextAndInvalidatesSnapshot() = runBlocking {
        val node=source("a.docx","fixture".toByteArray())
        val stamp=repo.sourceStamp(node)
        val older=ContentResult(sourceId=node.id,sourceStamp=stamp,kind="parse",status="confirmed",text="旧版",updatedAt=1)
        val newer=older.copy(id=newId(),text="新版",updatedAt=2)
        db.content().save(older);db.content().save(newer)
        val before=repo.organizeSnapshot(node.parentId!!)
        assertTrue(before.text.contains("新版"))
        processor().confirm(older.id,"重新核对的版本")
        val after=repo.organizeSnapshot(node.parentId!!)
        assertTrue(after.text.contains("重新核对的版本"));assertNotEquals(before.stamp,after.stamp)
    }
    @Test fun errorsPreserveOriginalAndRetryCreatesNewAttempt() = runBlocking {
        val node = source("bad.pdf", "not a PDF".toByteArray())
        val service = processor(); service.start(node.id,"parse")
        assertEquals("failed", finalResult(node.id).status)
        service.start(node.id,"parse")
        withTimeout(5000) { while(db.content().forSource(node.id).size < 2) delay(20) }
        assertEquals("failed", finalResult(node.id).status)
        assertEquals("not a PDF", repo.document(node.localName!!).readText())
    }
    @Test fun restartInterruptsPersistedWorkWithoutUsingPartialText() = runBlocking {
        val node = source("a.txt","原件".toByteArray())
        val old = ContentResult(sourceId = node.id,kind="parse",status="running",text="部分结果")
        db.content().save(old); processor()
        withTimeout(5000) { while(db.content().result(old.id)?.status != "interrupted") delay(20) }
        assertTrue(db.content().confirmed().isEmpty())
        assertTrue(repo.document(node.localName!!).isFile)
    }
    @Test fun cancellationAndDuplicateStartNeverConfirmPartialWork() = runBlocking {
        val node=source("a.txt","原件".toByteArray())
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val service=processor { _,_ -> entered.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));"部分文字" }
        val job=service.start(node.id,"parse")
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5,java.util.concurrent.TimeUnit.SECONDS) })
            assertSame(job,service.start(node.id,"parse"))
            service.cancel(node.id);release.countDown();job.join()
            assertEquals("cancelled",db.content().forSource(node.id).single().status)
            assertTrue(db.content().confirmed().isEmpty());assertTrue(repo.document(node.localName!!).isFile)
        } finally { release.countDown() }
    }
    @Test fun permanentDeletionRemovesRecognitionAndLateWorkerCannotRecreateIt() = runBlocking {
        val node=source("a.txt","原件".toByteArray())
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val service=processor { _,_ -> entered.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));"迟到的文字" }
        val job=service.start(node.id,"parse")
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5,java.util.concurrent.TimeUnit.SECONDS) })
            repo.deleteNode(node.id);repo.purgeTrash(db.features().allTrash().single().id)
            release.countDown();job.join()
            assertTrue(db.content().forSource(node.id).isEmpty());assertFalse(repo.document(node.localName!!).exists())
        } finally { release.countDown() }
    }
    @Test fun capturedFilesMoveAtomicallyToInboxAndCleanupKeepsPending() = runBlocking {
        val name = "${newId()}.jpg"; val pending = File(repo.captures,name); pending.writeText("camera bytes")
        repo.cleanOrphans(); assertTrue(pending.isFile)
        val id = repo.saveCapture(name,"photo.jpg","image/jpeg")
        assertFalse(pending.exists())
        val node = db.dao().nodes().single { it.id == id }
        assertEquals("inbox-topic", node.parentId)
        assertEquals("camera bytes", repo.document(node.localName!!).readText())
        try { repo.saveCapture("../bad.jpg","bad","image/jpeg"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun versionThreeMigrationPreservesSchedulesLinksAndTrash() = runBlocking {
        val file = context.getDatabasePath("content-upgrade-v3.db"); file.parentFile!!.mkdirs()
        val entities = org.json.JSONObject(File("schemas/cn.campus.notes.data.CampusDatabase/3.json").readText()).getJSONObject("database").getJSONArray("entities")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { raw ->
            for(i in 0 until entities.length()) { val e=entities.getJSONObject(i)
                raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val indices=e.optJSONArray("indices")?:org.json.JSONArray()
                for(j in 0 until indices.length()) raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
            raw.execSQL("INSERT INTO campus_todos VALUES ('todo','保留待办','正文',NULL,NULL,0,0)")
            raw.execSQL("INSERT INTO record_links VALUES ('record','course','topic')")
            raw.execSQL("INSERT INTO trash_entries VALUES ('trash','note','保留回收站','{}',1)")
            raw.version = 3
        }
        val upgraded = Room.databaseBuilder(context,CampusDatabase::class.java,file.name).addMigrations(CampusDatabase.MIGRATION_3_4,CampusDatabase.MIGRATION_4_5, CampusDatabase.MIGRATION_5_6).build()
        try {
            assertEquals("保留待办", upgraded.features().allTodos().single().title)
            assertEquals("course", upgraded.features().allLinks().single().courseId)
            assertEquals("保留回收站", upgraded.features().allTrash().single().title)
            assertTrue(upgraded.content().confirmed().isEmpty())
        } finally { upgraded.close() }
    }
}
