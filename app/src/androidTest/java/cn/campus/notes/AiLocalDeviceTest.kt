package cn.campus.notes

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/** Isolated DB and UUID file copies; no model calls or changes to personal records/settings. */
@RunWith(AndroidJUnit4::class)
class AiLocalDeviceTest {
    @Test fun chatAndConfirmedSandboxOutputWorkOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        val repo = LocalRepository(context, db)
        val credentials: suspend () -> ProviderCredentials = { ProviderCredentials("https://example.invalid", "test", "unit-test-token") }
        val fake: (ProviderCredentials) -> AiProvider = { object : AiProvider {
            override fun streamReply(messages: List<PromptMessage>, json: Boolean) =
                if (json) flowOf("{\"title\":\"设备测试成果\",\"body\":\"合成测试内容\"}") else flowOf("合成", "回复")
        } }
        try {
            ChatService(db, credentials, fake).send("合成测试问题")
            assertEquals("合成回复", db.dao().chat().last().content)
            assertEquals("complete", db.dao().chat().last().status)
            repo.saveNode(LibraryNode("g", kind = NodeKind.GROUP, title = "测试大组", rule = "提取定义"))
            repo.saveNode(LibraryNode("s", "g", NodeKind.SUBJECT, "测试学科"))
            repo.saveNode(LibraryNode("t", "s", NodeKind.TOPIC, "测试主题"))
            ByteArrayInputStream("测试原件".toByteArray()).use { repo.importStream("t", "测试.txt", "text/plain", it) }
            val original = db.dao().nodes().single { it.kind == NodeKind.ORIGINAL }
            val task = OrganizeService(repo, credentials, fake).plan("t")
            try { repo.executeOrganize(task.id, false, false); fail("Missing confirmation must be rejected") }
            catch (_: IllegalArgumentException) { }
            repo.executeOrganize(task.id, false, true)
            assertEquals("complete", db.dao().task(task.id)!!.status)
            assertEquals("测试原件", repo.document(original.localName!!).readText())
            assertEquals(1, db.dao().nodes().count { it.kind == NodeKind.OUTPUT })
        } finally {
            // Remove only files created in this isolated fixture, never run global orphan cleanup.
            db.dao().nodes().mapNotNull { it.localName }.forEach { repo.document(it).delete() }
            db.close()
        }
    }
}
