package cn.campus.notes.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName="chat_attachments",primaryKeys=["sessionId","sourceId"],
    foreignKeys=[ForeignKey(entity=ChatSession::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=LibraryNode::class,parentColumns=["id"],childColumns=["sourceId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sourceId")])
data class ChatAttachment(val sessionId:String,val sourceId:String,val addedAt:Long=System.currentTimeMillis())

@Entity(tableName="ai_workflows",foreignKeys=[ForeignKey(entity=ChatSession::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sessionId")])
data class AiWorkflow(@PrimaryKey val id:String,val sessionId:String,val approval:String,
    val status:String="planning",val plan:String="",val results:String="[]",val inputs:String="[]",
    val log:String="",val error:String="",val organizeTaskId:String?=null,
    val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis())

@Dao interface WorkflowDao {
    @Query("SELECT * FROM ai_workflows ORDER BY createdAt DESC,id") fun observe():Flow<List<AiWorkflow>>
    @Query("SELECT * FROM ai_workflows ORDER BY createdAt DESC,id") suspend fun all():List<AiWorkflow>
    @Query("SELECT * FROM ai_workflows WHERE id=:id") suspend fun get(id:String):AiWorkflow?
    @Insert suspend fun insert(value:AiWorkflow)
    @Update suspend fun update(value:AiWorkflow):Int
    @Query("UPDATE ai_workflows SET status='interrupted', error='应用退出，任务已中断；不会自动重新调用服务' WHERE status IN ('planning','processing','answering')") suspend fun interrupt()
    @Query("SELECT * FROM chat_attachments ORDER BY addedAt,sourceId") fun attachments():Flow<List<ChatAttachment>>
    @Query("SELECT * FROM chat_attachments WHERE sessionId=:session ORDER BY addedAt,sourceId") suspend fun selected(session:String):List<ChatAttachment>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun attach(value:ChatAttachment)
    @Query("DELETE FROM chat_attachments WHERE sessionId=:session AND sourceId=:source") suspend fun detach(session:String,source:String)
    @Query("UPDATE chat_messages SET referenceText='',status=CASE WHEN role='assistant' AND status='streaming' THEN 'interrupted' ELSE status END,detail=CASE WHEN role='assistant' AND status='streaming' THEN '来源已永久删除，生成中断' ELSE detail END WHERE workflowId=:id") suspend fun clearMessageReferences(id:String)
}

data class WorkflowModel(val id:String,val endpoint:String,val model:String,val revision:String) {
    fun json()=JSONObject().put("id",id).put("endpoint",endpoint).put("model",model).put("revision",revision)
    companion object{fun read(j:JSONObject)=WorkflowModel(j.getString("id"),j.getString("endpoint"),j.getString("model"),j.getString("revision"))}
}
data class WorkflowSource(val id:String,val title:String,val mime:String,val bytes:Long,val parentId:String?,val stamp:String,
    val tool:String,val confirmedId:String?=null,val confirmedVersion:Long?=null) {
    fun json()=JSONObject().put("id",id).put("title",title).put("mime",mime).put("bytes",bytes).put("parentId",parentId?:JSONObject.NULL)
        .put("stamp",stamp).put("tool",tool).put("confirmedId",confirmedId?:JSONObject.NULL).put("confirmedVersion",confirmedVersion?:JSONObject.NULL)
    fun descriptor()=JSONObject().put("sourceId",id).put("title",title).put("mime",mime).put("tool",tool).put("hasConfirmedText",confirmedId!=null)
    companion object{fun read(j:JSONObject)=WorkflowSource(j.getString("id"),j.getString("title"),j.getString("mime"),j.getLong("bytes"),j.nullable("parentId"),j.getString("stamp"),j.getString("tool"),j.nullable("confirmedId"),if(j.isNull("confirmedVersion"))null else j.getLong("confirmedVersion"))}
}
data class WorkflowApproval(val id:String=newId(),val sessionId:String,val mode:String,val prompt:String,
    val chat:WorkflowModel,val reasoning:String,val asr:WorkflowModel,val ocr:WorkflowModel,val sources:List<WorkflowSource>,
    val topicId:String?=null,val topicVersion:String="",val rule:String="",val sandboxAuto:Boolean=false) {
    fun json()=JSONObject().put("id",id).put("sessionId",sessionId).put("mode",mode).put("prompt",prompt).put("chat",chat.json()).put("reasoning",reasoning)
        .put("asr",asr.json()).put("ocr",ocr.json()).put("sources",JSONArray().apply{sources.forEach{put(it.json())}})
        .put("topicId",topicId?:JSONObject.NULL).put("topicVersion",topicVersion).put("rule",rule).put("sandboxAuto",sandboxAuto)
    companion object {
        fun read(value:String):WorkflowApproval {val j=JSONObject(value);val sources=j.getJSONArray("sources");require(sources.length() in 1..10)
            return WorkflowApproval(j.getString("id"),j.getString("sessionId"),j.getString("mode"),j.getString("prompt"),WorkflowModel.read(j.getJSONObject("chat")),j.getString("reasoning"),WorkflowModel.read(j.getJSONObject("asr")),WorkflowModel.read(j.getJSONObject("ocr")),(0 until sources.length()).map{WorkflowSource.read(sources.getJSONObject(it))},j.nullable("topicId"),j.getString("topicVersion"),j.getString("rule"),j.getBoolean("sandboxAuto"))}
    }
}
data class WorkflowResult(val sourceId:String,val tool:String,val resultId:String?) {
    fun json()=JSONObject().put("sourceId",sourceId).put("tool",tool).put("resultId",resultId?:JSONObject.NULL)
    companion object {
        fun read(value:String):List<WorkflowResult>{val j=JSONArray(value);return (0 until j.length()).map{val e=j.getJSONObject(it);WorkflowResult(e.getString("sourceId"),e.getString("tool"),e.nullable("resultId"))}}
        fun encode(values:List<WorkflowResult>)=JSONArray().apply{values.forEach{put(it.json())}}.toString()
    }
}
