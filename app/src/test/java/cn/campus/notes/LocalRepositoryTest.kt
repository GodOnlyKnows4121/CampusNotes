package cn.campus.notes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import cn.campus.notes.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: CampusDatabase
    private lateinit var repo: LocalRepository
    private fun open() { db = Room.databaseBuilder(context, CampusDatabase::class.java, "persistence-test.db").build(); repo = LocalRepository(context,db) }
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); open() }
    @After fun close() { db.close() }
    private suspend fun tree() {
        repo.saveNode(LibraryNode("g",kind=NodeKind.GROUP,title="大组",rule="规则一"))
        repo.saveNode(LibraryNode("s","g",NodeKind.SUBJECT,"学科"))
        repo.saveNode(LibraryNode("t","s",NodeKind.TOPIC,"主题"))
    }
    private suspend fun fails(block: suspend () -> Unit) { try { block(); fail("Expected failure") } catch (_: IllegalArgumentException) { } }
    @Test fun notesAndIndependentRulesSurviveDatabaseReopen() = runBlocking {
        tree()
        repo.saveNode(LibraryNode("g2",kind=NodeKind.GROUP,title="第二大组",rule="规则二"))
        repo.saveNote(Note("n","笔记","第一版"))
        repo.saveNote(Note("n","新标题","第二版"))
        repo.saveNode(db.dao().nodes().first { it.id == "g" }.copy(rule="修改后的规则"))
        db.close(); open()
        assertEquals("第二版",db.dao().notes().single().body)
        assertEquals("规则二",db.dao().nodes().first { it.id == "g2" }.rule)
        assertEquals("修改后的规则",db.dao().nodes().first { it.id == "g" }.rule)
        repo.deleteNote("n"); assertTrue(db.dao().notes().isEmpty())
    }
    @Test fun courseConflictsValidateWeekRangesAndAllowWeekend() = runBlocking {
        val course = LocalCourse("c","课程",6,4,"教室","教师",1,8)
        repo.saveCourse(course)
        fails { repo.saveCourse(course.copy(id="conflict",firstWeek=8,lastWeek=10)) }
        repo.saveCourse(course.copy(id="later",firstWeek=9,lastWeek=16))
        fails { repo.saveCourse(course.copy(day=7)) }
        fails { repo.saveCourse(course.copy(firstWeek=9,lastWeek=1)) }
        repo.saveCourse(course.copy(title="更新"))
        assertEquals(2,db.dao().courses().size)
        repo.deleteCourse("c"); assertEquals("later",db.dao().courses().single().id)
    }
    @Test fun importRenameCascadeAndCleanupPreserveUnrelatedFiles() = runBlocking {
        tree()
        val source = File(context.cacheDir,"source.txt").apply { writeText("课堂原文") }
        source.inputStream().use { repo.importStream("t","原件.txt","text/plain",it) }
        val entry = db.dao().nodes().single { it.kind == NodeKind.ORIGINAL }
        assertEquals("课堂原文",repo.document(entry.localName!!).readText())
        repo.saveNode(entry.copy(title="重命名.txt"))
        val orphan = File(repo.documents,"orphan.part").apply { writeText("interrupted") }
        assertEquals(1,repo.cleanOrphans()); assertFalse(orphan.exists())
        assertTrue(repo.document(entry.localName).exists())
        repo.deleteNode("g")
        assertTrue(db.dao().nodes().isEmpty()); assertTrue(repo.document(entry.localName).exists())
        assertEquals(0,repo.cleanOrphans())
        val trash=db.features().allTrash().single()
        repo.restoreTrash(trash.id)
        assertEquals(4,db.dao().nodes().size)
        repo.deleteNode("g")
        repo.purgeTrash(db.features().allTrash().single().id)
        assertFalse(repo.document(entry.localName).exists())
        assertEquals("课堂原文",source.readText())
    }
    @Test fun failedImportsRollbackAndCannotAttachToWrongHierarchy() = runBlocking {
        tree()
        fails { repo.importStream("g","wrong.txt","text/plain",ByteArrayInputStream(byteArrayOf(1))) }
        try {
            repo.importStream("t","broken.txt","text/plain",object: InputStream() { override fun read(): Int = throw IOException("disconnected") })
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(repo.documents.listFiles().orEmpty().isEmpty())
        assertEquals(3,db.dao().nodes().size)
        fails { repo.saveNode(LibraryNode("bad","t",NodeKind.SUBJECT,"bad")) }
        fails { repo.document("../../outside") }
    }
    @Test fun modelSettingsPersistSeparatelyAndRejectUnsafeEndpoints() = runBlocking {
        val settings = SettingsStore(context)
        settings.save(ProviderKind.DEEPSEEK,"https://example.com/v1","chat","",false)
        settings.save(ProviderKind.ASR,"https://speech.example.com","speech","",false)
        settings.sandbox(true)
        val saved = SettingsStore(context).data.first()
        assertEquals("chat",saved.providers[ProviderKind.DEEPSEEK]?.model)
        assertEquals("speech",saved.providers[ProviderKind.ASR]?.model)
        assertEquals("",saved.providers[ProviderKind.OCR]?.model)
        assertTrue(saved.sandboxEnabled)
        fails { settings.save(ProviderKind.DEEPSEEK,"http://insecure.example","bad","",false) }
        fails { SettingsStore.validateEndpoint("https://user:password@example.com") }
        assertEquals("chat",settings.data.first().providers[ProviderKind.DEEPSEEK]?.model)
    }
}
