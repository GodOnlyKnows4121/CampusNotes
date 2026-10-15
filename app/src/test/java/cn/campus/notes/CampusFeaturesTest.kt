package cn.campus.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.campus.notes.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CampusFeaturesTest {
    private lateinit var db:CampusDatabase
    private lateinit var repo:LocalRepository
    @Before fun open(){val context=ApplicationProvider.getApplicationContext<Context>();db=Room.inMemoryDatabaseBuilder(context,CampusDatabase::class.java).build();repo=LocalRepository(context,db)}
    @After fun close(){db.close()}
    @Test fun semesterDatesOddEvenAndCrossPeriodConflicts()=runBlocking {
        val term=Semester("term","秋季","2026-08-31");repo.saveSemester(term)
        assertEquals(7,term.week(LocalDate.parse("2026-10-15")))
        val a=LocalCourse("a","高数",0,0,"A203","教师")
        repo.saveScheduledCourse(a,CoursePlacement("a","term",2,4,"odd"))
        repo.saveScheduledCourse(a.copy(id="b"),CoursePlacement("b","term",3,5,"even"))
        try{repo.saveScheduledCourse(a.copy(id="bad"),CoursePlacement("bad","term",4,6,"all"));fail()}catch(_:IllegalArgumentException){}
        repo.saveScheduledCourse(a.copy(id="specific"),CoursePlacement("specific","term",6,8,"custom","2,4"))
        assertFalse(db.features().allPlacements().first{it.courseId=="specific"}.occurs(a,3))
    }
    @Test fun invalidSemesterAndEmptyWeekPatternAreRejected()=runBlocking {
        try{repo.saveSemester(Semester(name="错误",startDate="2026-09-01"));fail()}catch(_:IllegalArgumentException){}
        try{repo.saveSemester(Semester(name="重叠",startDate="2026-08-31",periods="08:00-09:00\n08:50-09:35"));fail()}catch(_:IllegalArgumentException){}
        val c=LocalCourse("c","单周课",0,0,"","",2,2)
        try{repo.saveScheduledCourse(c,CoursePlacement("c",pattern="odd"));fail()}catch(_:IllegalArgumentException){}
        assertTrue(db.dao().courses().isEmpty())
    }
    @Test fun subtreeTrashAndRestoreRequireLiveParentAndKeepFiles()=runBlocking {
        repo.saveNode(LibraryNode("g",kind=NodeKind.GROUP,title="组"));repo.saveNode(LibraryNode("s","g",NodeKind.SUBJECT,"学科"));repo.saveNode(LibraryNode("t","s",NodeKind.TOPIC,"主题"))
        repo.importStream("t","原件.txt","text/plain",ByteArrayInputStream("原文".toByteArray()))
        repo.saveNote(Note("n","关联笔记","关联正文"));repo.assignRecord("n",null,"t")
        val file=db.dao().nodes().first{it.localName!=null}
        repo.deleteNode("t");val child=db.features().allTrash().single();repo.deleteNode("g")
        assertTrue(db.dao().notes().isEmpty())
        try{repo.restoreTrash(child.id);fail()}catch(_:IllegalArgumentException){}
        assertEquals(0,repo.cleanOrphans());assertEquals("原文",repo.document(file.localName!!).readText())
        repo.restoreTrash(db.features().allTrash().first{it.id!=child.id}.id);repo.restoreTrash(child.id)
        assertEquals(4,db.dao().nodes().size);assertTrue(db.features().allTrash().isEmpty())
        assertEquals("关联正文",db.dao().notes().single().body)
    }
    @Test fun noteCourseTodoAndInboxSurviveTrashRestoration()=runBlocking {
        repo.saveNote(Note("n","笔记","内容"));repo.deleteNote("n");repo.restoreTrash(db.features().allTrash().single().id)
        assertEquals("内容",db.dao().notes().single().body)
        val c=LocalCourse("c","课程",1,1,"A","老师");repo.saveScheduledCourse(c,CoursePlacement.legacy(c).copy(pattern="even"));repo.deleteCourse("c");repo.restoreTrash(db.features().allTrash().single().id)
        assertEquals("even",db.features().allPlacements().single().pattern)
        repo.saveTodo(CampusTodo("todo","作业",courseId="c"));repo.deleteTodo("todo");repo.restoreTrash(db.features().allTrash().single().id)
        assertEquals("c",db.features().allTodos().single().courseId)
        assertEquals(repo.inboxTopic(),repo.inboxTopic());assertEquals(3,db.dao().nodes().size)
    }
    @Test fun assigningOriginalIsAtomicAndCanReturnToInbox()=runBlocking {
        val inbox=repo.inboxTopic();repo.importStream(inbox,"原件.txt","text/plain",ByteArrayInputStream("原文".toByteArray()))
        val original=db.dao().nodes().first{it.localName!=null}
        repo.saveNode(LibraryNode("g",kind=NodeKind.GROUP,title="组"));repo.saveNode(LibraryNode("s","g",NodeKind.SUBJECT,"学科"));repo.saveNode(LibraryNode("t","s",NodeKind.TOPIC,"主题"))
        repo.assignRecord(original.id,null,"t");assertEquals("t",db.dao().nodes().first{it.id==original.id}.parentId)
        try{repo.assignRecord(original.id,"missing-course",null);fail()}catch(_:IllegalArgumentException){}
        assertEquals("t",db.dao().nodes().first{it.id==original.id}.parentId)
        repo.assignRecord(original.id,null,null);assertEquals(inbox,db.dao().nodes().first{it.id==original.id}.parentId)
    }
    @Test fun databaseTwoMigratesWithOldMessagesAndRulesIntact()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val file=context.getDatabasePath("upgrade-v2.db");file.parentFile!!.mkdirs()
        val entities=org.json.JSONObject(java.io.File("schemas/cn.campus.notes.data.CampusDatabase/2.json").readText()).getJSONObject("database").getJSONArray("entities")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use{raw->
            for(i in 0 until entities.length()){val e=entities.getJSONObject(i);raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")));val indices=e.optJSONArray("indices")?:org.json.JSONArray();for(j in 0 until indices.length())raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))}
            raw.execSQL("INSERT INTO notes VALUES ('old','旧笔记','保留正文',1)")
            raw.execSQL("INSERT INTO chat_messages (role,content,status,detail,createdAt) VALUES ('assistant','旧回答','complete','',1)")
            raw.execSQL("INSERT INTO nodes VALUES ('g',NULL,'group','旧组','旧规则',NULL,'text/plain',0,0)");raw.version=2
        }
        val upgraded=Room.databaseBuilder(context,CampusDatabase::class.java,file.name).addMigrations(CampusDatabase.MIGRATION_2_3).build()
        try{assertEquals("保留正文",upgraded.dao().notes().single().body);assertEquals("旧回答",upgraded.dao().chat().single().content);assertEquals("旧规则",upgraded.dao().nodes().single().rule);assertTrue(upgraded.features().allTrash().isEmpty())}finally{upgraded.close()}
    }
}
