package cn.campus.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

const val LEGACY_CHAT = "legacy"
@Entity(tableName = "chat_sessions")
data class ChatSession(@PrimaryKey val id: String = newId(), val title: String = "新的对话",
    val profileId: String = LEGACY_CHAT, val reasoning: String = "default", val draft: String = "",
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC, id") fun observe(): Flow<List<ChatSession>>
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC, id") suspend fun all(): List<ChatSession>
    @Query("SELECT * FROM chat_sessions WHERE id = :id") suspend fun session(id: String): ChatSession?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(session: ChatSession)
    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :now WHERE id = :id") suspend fun rename(id: String, title: String, now: Long)
    @Query("UPDATE chat_sessions SET draft = :draft WHERE id = :id") suspend fun draft(id: String, draft: String)
    @Query("UPDATE chat_sessions SET profileId = :profile, reasoning = :reasoning WHERE id = :id") suspend fun selection(id: String, profile: String, reasoning: String)
    @Query("UPDATE chat_sessions SET updatedAt = :now WHERE id = :id") suspend fun touch(id: String, now: Long)
    @Query("SELECT * FROM chat_messages WHERE sessionId = :id ORDER BY id") suspend fun messages(id: String): List<ChatMessage>
    @Query("SELECT * FROM chat_messages WHERE sessionId = :id ORDER BY id") fun observeMessages(id: String): Flow<List<ChatMessage>>
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE id = :id AND sessionId = :sessionId)") suspend fun hasMessage(id:Long,sessionId:String):Boolean
    @Query("DELETE FROM chat_messages WHERE sessionId = :id") suspend fun clear(id: String)
    @Query("DELETE FROM chat_sessions WHERE id = :id") suspend fun delete(id: String)
}

class ChatSessionStore(private val db: CampusDatabase) {
    suspend fun initialize() = db.withTransaction {
        if(db.sessions().all().isEmpty()) db.sessions().insert(ChatSession(LEGACY_CHAT, "默认会话"))
    }
    suspend fun create(profileId: String = LEGACY_CHAT): ChatSession {
        val session = ChatSession(profileId = profileId)
        db.sessions().insert(session); return session
    }
    suspend fun rename(id: String, title: String) {
        require(title.isNotBlank() && title.length <= 60) { "会话名称须为 1–60 字" }
        require(db.sessions().session(id) != null) { "会话已不存在" }
        db.sessions().rename(id,title.trim(),System.currentTimeMillis())
    }
    suspend fun delete(id: String): String = db.withTransaction {
        require(db.sessions().messages(id).none { it.status == "streaming" }) { "请先停止该会话的生成再删除" }
        require(db.workflows().all().none{it.sessionId==id&&it.status in setOf("planning","processing","answering")}){"请先停止该会话的联动任务"}
        db.sessions().clear(id); db.sessions().delete(id)
        if(db.sessions().all().isEmpty()) create().id else db.sessions().all().first().id
    }
    suspend fun clear(id: String) = db.withTransaction {
        require(db.sessions().messages(id).none { it.status == "streaming" }) { "请先停止该会话的生成再清空" }
        db.sessions().clear(id)
    }
}
