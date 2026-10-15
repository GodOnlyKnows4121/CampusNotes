package cn.campus.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

fun newId(): String = UUID.randomUUID().toString()

@Entity(tableName = "notes")
data class Note(@PrimaryKey val id: String = newId(), val title: String, val body: String,
    val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "courses")
data class LocalCourse(@PrimaryKey val id: String = newId(), val title: String,
    val day: Int, val slot: Int, val room: String, val teacher: String,
    val firstWeek: Int = 1, val lastWeek: Int = 16, val example: Boolean = false)

object NodeKind {
    const val GROUP = "group"; const val SUBJECT = "subject"; const val TOPIC = "topic"
    const val ORIGINAL = "original"; const val OUTPUT = "output"
}

@Entity(tableName = "nodes", foreignKeys = [ForeignKey(entity = LibraryNode::class,
    parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("parentId")])
data class LibraryNode(@PrimaryKey val id: String = newId(), val parentId: String? = null,
    val kind: String, val title: String, val rule: String = "", val localName: String? = null,
    val mime: String = "application/octet-stream", val bytes: Long = 0, val example: Boolean = false)

@Entity(tableName = "chat_messages", indices = [Index("sessionId")])
data class ChatMessage(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, val content: String, val status: String = "complete", val detail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'legacy'") val sessionId: String = LEGACY_CHAT,
    @ColumnInfo(defaultValue = "''") val modelName: String = "",
    @ColumnInfo(defaultValue = "'legacy'") val profileId: String = LEGACY_CHAT,
    @ColumnInfo(defaultValue = "'default'") val reasoning: String = "default",
    @ColumnInfo(defaultValue = "''") val endpoint: String = "",
    @ColumnInfo(defaultValue = "''") val referenceText:String="",
    @ColumnInfo(defaultValue = "NULL") val workflowId:String?=null)

@Entity(tableName = "organize_tasks")
data class OrganizeTask(@PrimaryKey val id: String = newId(), val topicId: String,
    val topicTitle: String, val rule: String, val sources: String, val sourceStamp: String,
    val title: String = "", val body: String = "", val status: String = "planning",
    val detail: String = "", val outputId: String? = null, val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val modelName: String = "",
    @ColumnInfo(defaultValue = "''") val endpoint: String = "",
    @ColumnInfo(defaultValue = "'legacy'") val profileId: String = LEGACY_CHAT,
    @ColumnInfo(defaultValue = "'default'") val reasoning: String = "default")

data class LocalData(val notes: List<Note> = emptyList(), val courses: List<LocalCourse> = emptyList(),
    val nodes: List<LibraryNode> = emptyList()) {
    fun children(id: String?) = nodes.filter { it.parentId == id }
    fun node(id: String?) = nodes.find { it.id == id }
}

@Dao
interface CampusDao {
    @Query("SELECT * FROM chat_messages ORDER BY id") fun observeChat(): Flow<List<ChatMessage>>
    @Query("SELECT * FROM chat_messages ORDER BY id") suspend fun chat(): List<ChatMessage>
    @Insert suspend fun insert(message: ChatMessage): Long
    @Upsert suspend fun save(message: ChatMessage)
    @Query("DELETE FROM chat_messages") suspend fun clearChat()
    @Query("UPDATE chat_messages SET status = 'interrupted', detail = '应用退出导致中断，部分回复不进入后续对话上下文' WHERE role = 'assistant' AND status = 'streaming'") suspend fun interruptChat()
    @Query("SELECT * FROM organize_tasks ORDER BY createdAt DESC, id DESC") fun observeTasks(): Flow<List<OrganizeTask>>
    @Query("SELECT * FROM organize_tasks WHERE id = :id") suspend fun task(id: String): OrganizeTask?
    @Upsert suspend fun save(task: OrganizeTask)
    @Query("UPDATE organize_tasks SET status = 'interrupted', detail = '应用退出导致计划中断，未执行写入' WHERE status = 'planning'") suspend fun interruptPlans()
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC, id") fun observeNotes(): Flow<List<Note>>
    @Query("SELECT * FROM courses ORDER BY day, slot, id") fun observeCourses(): Flow<List<LocalCourse>>
    @Query("SELECT * FROM nodes ORDER BY rowid") fun observeNodes(): Flow<List<LibraryNode>>
    @Query("SELECT * FROM courses") suspend fun courses(): List<LocalCourse>
    @Query("SELECT * FROM nodes") suspend fun nodes(): List<LibraryNode>
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") suspend fun notes(): List<Note>
    @Upsert suspend fun save(note: Note)
    @Upsert suspend fun save(course: LocalCourse)
    @Upsert suspend fun save(node: LibraryNode)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: String)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun deleteCourse(id: String)
    @Query("DELETE FROM nodes WHERE id = :id") suspend fun deleteNode(id: String)
}

@Database(entities = [Note::class, LocalCourse::class, LibraryNode::class, ChatMessage::class, OrganizeTask::class,
    Semester::class, CoursePlacement::class, CampusTodo::class, RecordLink::class, TrashEntry::class, ContentResult::class, ChatSession::class,ChatAttachment::class,AiWorkflow::class], version = 6, exportSchema = true)
abstract class CampusDatabase : RoomDatabase() {
    abstract fun dao(): CampusDao
    abstract fun features(): FeatureDao
    abstract fun content(): ContentDao
    abstract fun sessions(): ChatSessionDao
    abstract fun workflows():WorkflowDao
    companion object {
        val MIGRATION_5_6=object:Migration(5,6){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_attachments (sessionId TEXT NOT NULL,sourceId TEXT NOT NULL,addedAt INTEGER NOT NULL,PRIMARY KEY(sessionId,sourceId),FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,FOREIGN KEY(sourceId) REFERENCES nodes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_attachments_sourceId ON chat_attachments(sourceId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ai_workflows (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,approval TEXT NOT NULL,status TEXT NOT NULL,plan TEXT NOT NULL,results TEXT NOT NULL,inputs TEXT NOT NULL,log TEXT NOT NULL,error TEXT NOT NULL,organizeTaskId TEXT,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,FOREIGN KEY(sessionId) REFERENCES chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_workflows_sessionId ON ai_workflows(sessionId)")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN referenceText TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN workflowId TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE content_results ADD COLUMN diagnostics TEXT NOT NULL DEFAULT ''")
        }}
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, profileId TEXT NOT NULL, reasoning TEXT NOT NULL, draft TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("INSERT INTO chat_sessions SELECT 'legacy','历史会话','legacy','default','',COALESCE(MIN(createdAt),0),COALESCE(MAX(createdAt),0) FROM chat_messages")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sessionId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN modelName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN profileId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN endpoint TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
                db.execSQL("ALTER TABLE organize_tasks ADD COLUMN modelName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE organize_tasks ADD COLUMN endpoint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE organize_tasks ADD COLUMN profileId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE organize_tasks ADD COLUMN reasoning TEXT NOT NULL DEFAULT 'default'")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS content_results (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, sourceStamp TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, text TEXT NOT NULL, detail TEXT NOT NULL, provider TEXT NOT NULL, outputNoteId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_content_results_sourceId ON content_results (sourceId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS semesters (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, startDate TEXT NOT NULL, weeks INTEGER NOT NULL, periods TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS course_placements (courseId TEXT NOT NULL PRIMARY KEY, semesterId TEXT, startPeriod INTEGER NOT NULL, endPeriod INTEGER NOT NULL, pattern TEXT NOT NULL, customWeeks TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_todos (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, dueAt INTEGER, courseId TEXT, done INTEGER NOT NULL, remind INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS record_links (recordId TEXT NOT NULL PRIMARY KEY, courseId TEXT, topicId TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS trash_entries (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, payload TEXT NOT NULL, deletedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS organize_tasks (id TEXT PRIMARY KEY NOT NULL, topicId TEXT NOT NULL, topicTitle TEXT NOT NULL, rule TEXT NOT NULL, sources TEXT NOT NULL, sourceStamp TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, outputId TEXT, createdAt INTEGER NOT NULL)")
            }
        }
    }
}
