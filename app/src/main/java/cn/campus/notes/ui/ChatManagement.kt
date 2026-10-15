package cn.campus.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*

@Composable
fun ChatSessionsDialog(onDismiss:()->Unit) {
    val vm=LocalCampus.current;val sessions by vm.sessions.collectAsState();val active by vm.activeChat.collectAsState()
    val busy by vm.busy.collectAsState();val running by vm.aiRunning.collectAsState()
    var rename by rememberSaveable{mutableStateOf<String?>(null)};var deleting by rememberSaveable{mutableStateOf<String?>(null)}
    EditorFrame("独立会话",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")};Button(enabled=!busy,onClick=vm::newSession,modifier=Modifier.testTag("new_chat_session")){Text("新建会话")}}) {
        Text("会话分别保存上下文、草稿、模型及强度。切换会话不会发送请求；生成中的请求保持原配置。")
        sessions.forEach{s->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(10.dp)) {
            Text((if(s.id==active)"当前 · " else "")+s.title,style=MaterialTheme.typography.titleMedium)
            Row {
                TextButton(enabled=!busy,onClick={vm.selectSession(s.id);onDismiss()},modifier=Modifier.testTag("select_session_${s.id}")){Text("打开")}
                TextButton(enabled=!busy,onClick={rename=s.id},modifier=Modifier.testTag("rename_session_${s.id}")){Text("命名")}
                TextButton(enabled=!busy&&running==null,onClick={deleting=s.id},modifier=Modifier.testTag("delete_session_${s.id}")){Text("删除")}
            }
        }}}
    }
    rename?.let{id->sessions.find{it.id==id}?.let{s->TextEditor("会话名称",listOf("名称" to s.title),{rename=null}){values->vm.act("会话已命名",{rename=null}){vm.sessionStore.rename(id,values[0])}}}}
    deleting?.let{id->DeleteConfirmation("会话","删除本会话消息和草稿；其他会话及已另存的本地记录保留。此操作不能恢复。",{deleting=null}){vm.deleteSession(id);deleting=null}}
}

@Composable
fun ChatProfilesDialog(onDismiss:()->Unit) {
    val vm=LocalCampus.current;val settings by vm.settings.collectAsState();val busy by vm.busy.collectAsState()
    var editing by rememberSaveable{mutableStateOf<String?>(null)};var deleting by rememberSaveable{mutableStateOf<String?>(null)}
    var legacy by rememberSaveable{mutableStateOf(false)}
    EditorFrame("语言模型配置",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")};Button(enabled=!busy,onClick={editing=newId()},modifier=Modifier.testTag("new_chat_profile")){Text("新增模型")}}){
        Text("每个配置独立保存地址、模型和加密密钥。保存不会联网；ASR/OCR 配置保持独立。")
        settings.chatProfiles.forEach{p->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
            Text(p.title,style=MaterialTheme.typography.titleMedium);Text(p.model.ifBlank{"未填写模型"});Text(p.endpoint.ifBlank{"未填写地址"},style=MaterialTheme.typography.bodySmall)
            Text(if(p.hasKey)"已保存加密密钥" else "尚未保存密钥",style=MaterialTheme.typography.bodySmall)
            Row{TextButton(enabled=!busy,onClick={if(p.id==LEGACY_CHAT)legacy=true else editing=p.id}){Text("编辑")};if(p.id!=LEGACY_CHAT)TextButton(enabled=!busy,onClick={deleting=p.id}){Text("移除配置")}}
        }}}
    }
    editing?.let{id->key(id){ChatProfileEditor(id,settings.chatProfiles.find{it.id==id}){editing=null}}}
    if(legacy)ModelSettingsDialog{legacy=false}
    deleting?.let{id->DeleteConfirmation("模型配置","移除该配置及其密钥密文；已有消息保留，使用此配置的会话需重新选择模型。正在执行的请求不变。",{deleting=null}){
        vm.act("模型配置已移除",{deleting=null}){vm.settingsStore.deleteChatProfile(id)}
    }}
}

@Composable
private fun ChatProfileEditor(id:String,old:ChatModelProfile?,onDismiss:()->Unit) {
    val vm=LocalCampus.current;val busy by vm.busy.collectAsState()
    var title by rememberSaveable{mutableStateOf(old?.title?:"")};var endpoint by rememberSaveable{mutableStateOf(old?.endpoint?:"")};var model by rememberSaveable{mutableStateOf(old?.model?:"")}
    var secret by remember{mutableStateOf("")};var remove by rememberSaveable{mutableStateOf(false)}
    EditorFrame("语言模型配置",onDismiss,footer={TextButton(onClick=onDismiss,enabled=!busy){Text("取消")};Button(enabled=!busy,onClick={
        vm.act("语言模型配置已保存",{secret="";onDismiss()}){vm.settingsStore.saveChatProfile(id,title,endpoint,model,secret,remove);vm.normalizeChatProfile(id)}
    },modifier=Modifier.testTag("save_chat_profile")){Text("保存")}}) {
        OutlinedTextField(title,{title=it},label={Text("配置名称")},modifier=Modifier.fillMaxWidth().testTag("profile_title"))
        OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS API 地址")},modifier=Modifier.fillMaxWidth().testTag("profile_endpoint"))
        OutlinedTextField(model,{model=it},label={Text("实际模型名称")},modifier=Modifier.fillMaxWidth().testTag("profile_model"))
        Text(if(old?.hasKey==true)"密钥留空保持已有值" else "尚未保存密钥")
        OutlinedTextField(secret,{secret=it},label={Text("新 API 密钥")},visualTransformation=PasswordVisualTransformation(),enabled=!remove,modifier=Modifier.fillMaxWidth())
        Row{Checkbox(remove,{remove=it});Text("保存时移除密钥",Modifier.padding(top=12.dp))}
        Text("密钥不回显、不写入日志，不随屏幕旋转保存。模型切换影响下一次请求；历史消息保留原模型标记。")
    }
}
