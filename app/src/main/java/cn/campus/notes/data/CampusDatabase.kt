package cn.campus.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

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

data class LocalData(val notes: List<Note> = emptyList(), val courses: List<LocalCourse> = emptyList(),
    val nodes: List<LibraryNode> = emptyList()) {
    fun children(id: String?) = nodes.filter { it.parentId == id }
    fun node(id: String?) = nodes.find { it.id == id }
}

@Dao
interface CampusDao {
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

@Database(entities = [Note::class, LocalCourse::class, LibraryNode::class], version = 1, exportSchema = true)
abstract class CampusDatabase : RoomDatabase() { abstract fun dao(): CampusDao }
