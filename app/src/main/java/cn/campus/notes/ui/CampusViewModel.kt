package cn.campus.notes.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.campus.notes.CampusApplication
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CampusViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as CampusApplication).repository
    val settingsStore = (application as CampusApplication).settings
    val contentProcessor = (application as CampusApplication).contentProcessor
    val contentResults = repository.db.content().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val data = MutableStateFlow(LocalData())
    val settings = MutableStateFlow(LocalSettings())
    val ready = MutableStateFlow(false)
    val settingsReady = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val storageBytes = MutableStateFlow(0L)
    val sessionStore = ChatSessionStore(repository.db)
    val sessions = repository.db.sessions().observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val activeChat = MutableStateFlow(LEGACY_CHAT)
    val currentSession = combine(sessions,activeChat){all,id->all.find{it.id==id}}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val chat = activeChat.flatMapLatest{repository.db.sessions().observeMessages(it)}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chatDrafts = MutableStateFlow<Map<String,String>>(emptyMap())
    private val draftWrites = Channel<Pair<String,String>>(Channel.UNLIMITED)
    val runningSession = MutableStateFlow<String?>(null)
    val tasks = repository.db.dao().observeTasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workflows=repository.db.workflows().observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val attachments=repository.db.workflows().attachments().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val workflowApproval=MutableStateFlow<WorkflowApproval?>(null)
    val runningWorkflow=MutableStateFlow<String?>(null)
    val workflowService=WorkflowService(repository,settingsStore,contentProcessor)
    val aiReady = MutableStateFlow(false)
    val aiRunning = MutableStateFlow<String?>(null)
    val liveReply = MutableStateFlow("")
    val scheduleFocus = MutableStateFlow<String?>(null)
    val semesters = repository.db.features().semesters().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val placements = repository.db.features().placements().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val todos = repository.db.features().todos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val links = repository.db.features().links().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val trash = repository.db.features().trash().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var aiJob: Job? = null
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.db.dao().interruptChat(); repository.db.dao().interruptPlans();repository.db.workflows().interrupt(); sessionStore.initialize()
                val preferred=settingsStore.data.first().activeChatSession
                activeChat.value=repository.db.sessions().session(preferred)?.id?:repository.db.sessions().all().first().id
                aiReady.value = true
            } catch (_: Exception) { error.value = "对话与任务记录读取失败，请重新启动应用" }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for((id,draft) in draftWrites) {
                try { aiReady.filter{it}.first();repository.db.sessions().draft(id,draft) }
                catch(e:Exception){if(e is CancellationException)throw e;error.value="草稿保存失败，请检查本地存储"}
            }
        }
        viewModelScope.launch {
            try { repository.data.collect { data.value = it; ready.value = true } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "本地数据库读取失败，请重新启动应用" }
        }
        viewModelScope.launch {
            try { settingsStore.data.collect { settings.value = it; settingsReady.value = true } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "设置读取失败，请检查本地存储后重新启动" }
        }
    }
    private fun request(label: String, block: suspend () -> Unit) {
        if (!aiReady.value || aiRunning.value != null) return
        aiRunning.value = label
        aiJob = viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = ChatService.safeMessage(e)
            } finally { liveReply.value = ""; aiRunning.value = null; runningSession.value=null;runningWorkflow.value=null }
        }
    }
    fun updateDraft(id:String,text:String) {
        if(text.length>8000)return
        chatDrafts.value=chatDrafts.value+(id to text);draftWrites.trySend(id to text)
    }
    fun sendChat(question: String, accepted: () -> Unit = {}) {
        if(!aiReady.value||aiRunning.value!=null)return
        val selected=sessions.value.find{it.id==activeChat.value}?:return
        val profile=settings.value.chatProfiles.find{it.id==selected.profileId}
        runningSession.value=selected.id
        request("对话") {
            require(profile!=null){"所选模型配置已移除，请重新选择模型"}
            val frozen=settingsStore.chatCredentials(profile.id)
            require(frozen.endpoint==profile.endpoint&&frozen.model==profile.model){"模型配置已改变，请确认后重新发送"}
            val service=ChatService(repository.db,{frozen}){DeepSeekProvider(it,reasoning=selected.reasoning)}
            service.send(question,{liveReply.value=it},{withContext(Dispatchers.Main){
                val draft=chatDrafts.value[selected.id]?:selected.draft
                if(draft==question)updateDraft(selected.id,"")
                accepted()
            }},selected.id,selected.profileId,selected.reasoning)
        }
    }
    fun cancelAi() { aiJob?.cancel() }
    fun clearChat() { val id=activeChat.value;request("清空对话") { sessionStore.clear(id) } }
    fun setAttachment(session:String,source:String,selected:Boolean)=act {
        if(selected){
            require(repository.db.dao().nodes().any{it.id==source&&it.kind==NodeKind.ORIGINAL}){"原件已不存在"}
            val existing=repository.db.workflows().selected(session)
            require(existing.any{it.sourceId==source}||existing.size<10){"每次最多附加10份原件"}
            repository.db.workflows().attach(ChatAttachment(session,source))
        }else repository.db.workflows().detach(session,source)
    }
    fun importAttachments(session:String,uris:List<android.net.Uri>){var summary="";act(onDone={message.value=summary}) {
        val distinct=uris.distinct();require(distinct.size+repository.db.workflows().selected(session).size<=10){"本次导入和已有附件合计最多10份"}
        val topic=repository.inboxTopic();var saved=0;var failed=0
        distinct.forEach{uri->try{val id=repository.importDocumentReference(topic,uri);repository.db.workflows().attach(ChatAttachment(session,id));saved++}
            catch(e:Exception){if(e is CancellationException)throw e;failed++}}
        summary="已保存并附加${saved}份原件，未调用AI"+if(failed>0)"；${failed}份导入失败，可重选失败文件" else ""
    }}
    fun prepareWorkflow(mode:String,prompt:String,ids:List<String>,profile:String?=null,reasoning:String?=null,topic:String?=null){
        if(aiRunning.value!=null)return
        val session=sessions.value.find{it.id==activeChat.value}?:return
        act {workflowApproval.value=workflowService.prepare(session.id,mode,prompt,ids,profile?:session.profileId,reasoning?:session.reasoning,topic)}
    }
    fun startWorkflow(approval:WorkflowApproval)=request("联动识别") {
        runningSession.value=approval.sessionId;runningWorkflow.value=approval.id
        val result=workflowService.start(approval,accepted={
            withContext(Dispatchers.Main){if((chatDrafts.value[approval.sessionId]?:sessions.value.find{it.id==approval.sessionId}?.draft)==approval.prompt)updateDraft(approval.sessionId,"")}
            if(approval.mode=="chat")approval.sources.forEach{repository.db.workflows().detach(approval.sessionId,it.id)}
        },update={liveReply.value=it})
        message.value=if(result.status=="review")"识别/解析已完成，请逐份核对确认后继续" else "联动任务已完成，请查看实际结果及日志"
    }
    fun resumeWorkflow(id:String)=request("继续联动") {
        runningWorkflow.value=id;runningSession.value=repository.db.workflows().get(id)?.sessionId
        workflowService.resume(id){liveReply.value=it};message.value="已依据确认资料完成模型请求，请核对结果"
        storageBytes.value=repository.storageBytes()
    }
    fun cancelPendingWorkflow(id:String)=act {workflowService.cancelPending(id)}
    fun selectSession(id:String)=act { require(repository.db.sessions().session(id)!=null){"会话已不存在"};settingsStore.activeChatSession(id);activeChat.value=id }
    fun newSession()=act { val next=sessionStore.create(currentSession.value?.profileId?:LEGACY_CHAT);settingsStore.activeChatSession(next.id);activeChat.value=next.id }
    fun deleteSession(id:String)=act("会话已删除") { val next=sessionStore.delete(id);chatDrafts.value=chatDrafts.value-id;if(activeChat.value==id){settingsStore.activeChatSession(next);activeChat.value=next} }
    fun selectChatModel(profile:String,reasoning:String)=act {
        val current=repository.db.sessions().session(activeChat.value)?:error("会话尚未就绪")
        val model=settingsStore.data.first().chatProfiles.find{it.id==profile}?:error("模型配置已移除")
        require(ThinkingLevel.from(reasoning) in ModelCapabilities.levels(model.endpoint,model.model)){"此模型仅支持默认思考设置"}
        repository.db.sessions().selection(current.id,profile,reasoning)
    }
    suspend fun normalizeChatProfile(id:String) {
        val profile=settingsStore.data.first().chatProfiles.find{it.id==id}?:return
        val supported=ModelCapabilities.levels(profile.endpoint,profile.model).map{it.id}
        repository.db.sessions().all().filter{it.profileId==id&&it.reasoning !in supported}.forEach{repository.db.sessions().selection(it.id,id,"default")}
    }
    fun planOrganize(topicId: String, profileId:String=LEGACY_CHAT,reasoning:String="default") {
        val profile=settings.value.chatProfiles.find{it.id==profileId};val auto=settings.value.sandboxEnabled
        request("生成整理计划") {
            require(profile!=null){"模型配置已移除，请重新选择"}
            val frozen=settingsStore.chatCredentials(profileId)
            require(frozen.endpoint==profile.endpoint&&frozen.model==profile.model){"配置已改变，请重新确认上传范围"}
            val service=OrganizeService(repository,{frozen}){DeepSeekProvider(it,reasoning=reasoning)}
            val task=service.plan(topicId,profileId,reasoning)
            if(auto&&settingsStore.data.first().sandboxEnabled)executeTask(task.id,confirmed=false)
        }
    }
    fun confirmOrganize(taskId: String) = request("执行整理") { executeTask(taskId, confirmed = true) }
    private suspend fun executeTask(taskId: String, confirmed: Boolean) {
        try {
            repository.executeOrganize(taskId, settingsStore.data.first().sandboxEnabled, confirmed)
            storageBytes.value = repository.storageBytes()
            message.value = "整理成果已保存到资料库，原件保留"
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val current = repository.db.dao().task(taskId)
                if (current?.status == "preview" && e !is CancellationException)
                    repository.db.dao().save(current.copy(status = "error", detail = ChatService.safeMessage(e)))
            }
            throw e
        }
    }
    fun act(success: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block(); storageBytes.value = repository.storageBytes() }
                message.value = success; onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = when (e) {
                    is IllegalArgumentException, is IllegalStateException -> e.message ?: "操作失败，请重试"
                    is SecurityException -> "无法读取文件或保护密钥，请重新授权；本次操作未完成"
                    else -> "操作失败，请检查文件是否可用及存储空间后重试"
                }
            } finally { busy.value = false }
        }
    }
}
