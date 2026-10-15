package cn.campus.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiPersistenceTest {
    private lateinit var db: CampusDatabase
    private lateinit var repo: LocalRepository
    private val fakeCredentials: suspend () -> ProviderCredentials = { ProviderCredentials("https://example.com", "test", "unit-test-token") }
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        repo = LocalRepository(context, db)
    }
    @After fun close() { db.close() }
    private fun fake(block: () -> Flow<String>) = object : AiProvider { override fun streamReply(messages: List<PromptMessage>, json: Boolean) = block() }
    private suspend fun tree() {
        repo.saveNode(LibraryNode("g", kind = NodeKind.GROUP, title = "数学", rule = "提取定义"))
        repo.saveNode(LibraryNode("s", "g", NodeKind.SUBJECT, "高数"))
        repo.saveNode(LibraryNode("t", "s", NodeKind.TOPIC, "极限"))
        ByteArrayInputStream("极限定义原文".toByteArray()).use { repo.importStream("t", "课堂.txt", "text/plain", it) }
    }
    @Test fun completedChatPersistsAndFailedAnswerIsExcludedFromContext() = runBlocking {
        val service = ChatService(db, fakeCredentials) { fake { flowOf("真实", "回答") } }
        service.send("第一问")
        assertEquals("真实回答", db.dao().chat().last().content)
        assertEquals("complete", db.dao().chat().last().status)
        val failing = ChatService(db, fakeCredentials) { fake { flow { emit("部分"); throw AiFailure("断流") } } }
        try { failing.send("失败问题"); fail() } catch (_: AiFailure) { }
        assertEquals("部分", db.dao().chat().last().content)
        assertEquals("error", db.dao().chat().last().status)
        val context = ChatService.context(db.dao().chat(), "下一问")
        assertEquals(listOf("第一问", "真实回答", "下一问"), context.drop(1).map { it.content })
        db.dao().interruptChat()
        assertEquals("error", db.dao().chat().last().status)
    }
    @Test fun missingConfigurationDoesNotWriteMessagesAndCancellationRetainsPartial() = runBlocking {
        val missing = ChatService(db, { throw IllegalArgumentException("未配置") })
        try { missing.send("问题"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().chat().isEmpty())
        val seen = CompletableDeferred<Unit>()
        val service = ChatService(db, fakeCredentials) { fake { flow { emit("已收到部分文字"); seen.complete(Unit); awaitCancellation() } } }
        val job = launch { service.send("取消问题") }
        withTimeout(5000) { seen.await() }
        job.cancelAndJoin()
        assertEquals("cancelled", db.dao().chat().last().status)
        assertEquals("已收到部分文字", db.dao().chat().last().content)
        assertEquals(2, ChatService.context(db.dao().chat(), "新问题").size)
    }
    @Test fun agentPreviewNeedsPermissionThenWritesSeparateOutputOnlyOnce() = runBlocking {
        tree()
        val original = db.dao().nodes().single { it.kind == NodeKind.ORIGINAL }
        val sourceBytes = repo.document(original.localName!!).readBytes()
        val service = OrganizeService(repo, fakeCredentials) { fake { flowOf("{\"title\":\"极限定义\",\"body\":\"整理结果\"}") } }
        val task = service.plan("t")
        assertEquals("preview", task.status)
        assertFalse(db.dao().nodes().any { it.kind == NodeKind.OUTPUT })
        try { repo.executeOrganize(task.id, sandboxEnabled = false, confirmed = false); fail() } catch (_: IllegalArgumentException) { }
        repo.executeOrganize(task.id, sandboxEnabled = false, confirmed = true)
        assertEquals("complete", db.dao().task(task.id)!!.status)
        val result = db.dao().nodes().single { it.kind == NodeKind.OUTPUT }
        assertTrue(repo.document(result.localName!!).readText().contains("整理结果"))
        assertArrayEquals(sourceBytes, repo.document(original.localName!!).readBytes())
        try { repo.executeOrganize(task.id, true, true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, db.dao().nodes().count { it.kind == NodeKind.OUTPUT })
    }
    @Test fun changedRulesAndUnsupportedModelActionsCannotWrite() = runBlocking {
        tree()
        val service = OrganizeService(repo, fakeCredentials) { fake { flowOf("{\"title\":\"摘要\",\"body\":\"结果\"}") } }
        val task = service.plan("t")
        repo.saveNode(db.dao().nodes().single { it.id == "g" }.copy(rule = "新的规则"))
        try { repo.executeOrganize(task.id, true, false); fail() } catch (_: IllegalArgumentException) { }
        val unsafe = OrganizeService(repo, fakeCredentials) { fake { flowOf("{\"title\":\"摘要\",\"body\":\"结果\",\"path\":\"/sdcard/x\"}") } }
        try { unsafe.plan("t"); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(db.dao().nodes().any { it.kind == NodeKind.OUTPUT })
        assertEquals(1, db.dao().nodes().count { it.kind == NodeKind.ORIGINAL })
    }
    @Test fun oldDatabaseMigratesWithoutLosingExistingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("old-campus.db"); file.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/cn.campus.notes.data.CampusDatabase/1.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
            for (i in 0 until schema.length()) {
                val entity = schema.getJSONObject(i)
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) raw.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            raw.execSQL("INSERT INTO notes VALUES ('old-note', '原有记录', '升级保留', 1)")
            raw.execSQL("INSERT INTO nodes VALUES ('old-group', NULL, 'group', '原有大组', '旧规则', NULL, 'text/plain', 0, 0)")
            raw.version = 1
        }
        val upgraded = Room.databaseBuilder(context, CampusDatabase::class.java, file.name).addMigrations(CampusDatabase.MIGRATION_1_2, CampusDatabase.MIGRATION_2_3).build()
        try {
            assertEquals("升级保留", upgraded.dao().notes().single().body)
            assertEquals("旧规则", upgraded.dao().nodes().single().rule)
            assertTrue(upgraded.dao().chat().isEmpty())
            upgraded.dao().insert(ChatMessage(role = "user", content = "新表可写"))
            assertEquals("新表可写", upgraded.dao().chat().single().content)
        } finally { upgraded.close() }
    }
}
