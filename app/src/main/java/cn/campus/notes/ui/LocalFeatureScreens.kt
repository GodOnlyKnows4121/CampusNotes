package cn.campus.notes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.testTag

@Composable
fun SemesterSettings(onDismiss:()->Unit) {
    val vm=LocalCampus.current; val semesters by vm.semesters.collectAsState(); val settings by vm.settings.collectAsState()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    EditorFrame("学期与节次",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")};TextButton(onClick={editing="new"}){Text("新增学期")}}) {
        Text("旧课程仍保留原时段。设置真实学期后，可编辑课程将其归入学期。")
        if(semesters.isEmpty()) Text("尚未设置学期，周次与日期不会自动推算。")
        semesters.forEach { s -> OutlinedCard { Column(Modifier.padding(12.dp)) {
            Text(s.name); Text("${s.startDate} · ${s.weeks} 周 · ${s.times().size} 节")
            Row { TextButton(onClick={vm.act {vm.settingsStore.activeSemester(s.id)}}){Text(if(settings.activeSemester==s.id) "当前学期" else "设为当前")};TextButton(onClick={editing=s.id}){Text("编辑")}}
        } } }
        TextButton(onClick={vm.act {vm.settingsStore.activeSemester(null)}}){Text("查看未设置学期的旧课程")}
    }
    editing?.let { id -> key(id) { val old=semesters.find {it.id==id}
        TextEditor("学期设置",listOf("学期名称" to (old?.name?:""),"第一周周一（yyyy-MM-dd）" to (old?.startDate?:""),
            "总周数（1–30）" to (old?.weeks?:20).toString(),"每节课时间（每行 HH:mm-HH:mm）" to (old?.periods?:Semester.DEFAULT_PERIODS)),{editing=null}){v->
            vm.act("学期已保存",{editing=null}) { val s=Semester(old?.id?:newId(),v[0],v[1],v[2].toIntOrNull()?:0,v[3]);vm.repository.saveSemester(s);vm.settingsStore.activeSemester(s.id) }
        }
    } }
}

@Composable
fun ScheduledCourseEditor(course:LocalCourse?,semesterId:String?,onDismiss:()->Unit) {
    val vm=LocalCampus.current; val placements by vm.placements.collectAsState()
    val p=placements.find {it.courseId==course?.id}?:course?.let(CoursePlacement::legacy)
    TextEditor(if(course==null) "新增课程" else "编辑课程",listOf("课程名称" to (course?.title?:""),"星期（1–7）" to ((course?.day?:0)+1).toString(),
        "开始节次" to (p?.startPeriod?:1).toString(),"结束节次" to (p?.endPeriod?:2).toString(),"教室" to (course?.room?:""),"教师" to (course?.teacher?:""),
        "开始周" to (course?.firstWeek?:1).toString(),"结束周" to (course?.lastWeek?:16).toString(),
        "周次规则（每周/单周/双周/指定周）" to (mapOf("all" to "每周","odd" to "单周","even" to "双周","custom" to "指定周")[p?.pattern]?:"每周"),"指定周（英文逗号分隔）" to (p?.customWeeks?:"")),onDismiss){v->
        vm.act("课程已保存",onDismiss){fun n(i:Int)=v[i].trim().toIntOrNull()?:error("星期、节次、周次须为整数")
            val id=course?.id?:newId();val c=LocalCourse(id,v[0],n(1)-1,0,v[4],v[5],n(6),n(7))
            val pattern=mapOf("每周" to "all","单周" to "odd","双周" to "even","指定周" to "custom")[v[8].trim()]?:error("周次规则请填写每周、单周、双周或指定周")
            vm.repository.saveScheduledCourse(c,CoursePlacement(id,semesterId,n(2),n(3),pattern,v[9].trim()))
        }
    }
}

@Composable
fun CampusScreen(onDismiss:()->Unit,onSchedule:()->Unit) {
    val vm=LocalCampus.current;val data=LocalContent.current;val todos by vm.todos.collectAsState();val semesters by vm.semesters.collectAsState()
    val placements by vm.placements.collectAsState();val settings by vm.settings.collectAsState();val links by vm.links.collectAsState()
    val semester=semesters.find {it.id==settings.activeSemester};val today=LocalDate.now();val week=semester?.week(today)
    val courses=data.courses.filter {c-> val p=placements.find {it.courseId==c.id}?:CoursePlacement.legacy(c)
        c.day==today.dayOfWeek.value-1&&p.semesterId==semester?.id&&(week==null||p.occurs(c,week)) }
    var edit by rememberSaveable{mutableStateOf<String?>(null)};var records by rememberSaveable{mutableStateOf(false)}
    BackHandler {onDismiss()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row {TextButton(onClick=onDismiss){Text("返回首页")};TextButton(onClick=onSchedule){Text("周课表")}}
        Text("我的校园",style=MaterialTheme.typography.headlineMedium);Text(today.toString())
        Text("今日课程",style=MaterialTheme.typography.titleLarge)
        if(courses.isEmpty())Text("今天没有匹配的课程")
        courses.forEach {c-> FeatureCard(c.title,"${c.room} · ${c.teacher}"){records=true}
            val ids=links.filter {it.courseId==c.id}.map {it.recordId}.toSet()
            Text("关联记录："+(data.notes.filter {it.id in ids}.map {it.title}+data.nodes.filter {it.id in ids}.map {it.title}).joinToString().ifBlank {"暂无，可在记录中关联"},style=MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("作业与待办",style=MaterialTheme.typography.titleLarge);TextButton(onClick={edit="new"}){Text("新增")}}
        if(todos.isEmpty())Text("暂无待办")
        todos.forEach {t->OutlinedCard {Column(Modifier.padding(12.dp)) {
            Row {Checkbox(t.done,{done->vm.act{vm.repository.saveTodo(t.copy(done=done))}});Column(Modifier.weight(1f)){Text(t.title);if(t.body.isNotBlank())Text(t.body,style=MaterialTheme.typography.bodySmall);t.dueAt?.let{Text("截止：${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()}",style=MaterialTheme.typography.bodySmall)}}}
            Row {TextButton(onClick={edit=t.id}){Text("编辑")};TextButton(onClick={vm.act("已移入回收站"){vm.repository.deleteTodo(t.id)}}){Text("移入回收站")}}
        }}}
    }
    if(records)RecordsDialog{records=false}
    edit?.let{id->key(id){CampusTodoEditor(todos.find{it.id==id}){edit=null}}}
}

@Composable
private fun CampusTodoEditor(old:CampusTodo?,onDismiss:()->Unit) {
    val vm=LocalCampus.current;val data=LocalContent.current;val placements by vm.placements.collectAsState();val terms by vm.semesters.collectAsState()
    val format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    var title by rememberSaveable{mutableStateOf(old?.title?:"")};var body by rememberSaveable{mutableStateOf(old?.body?:"")}
    var deadline by rememberSaveable{mutableStateOf(old?.dueAt?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().format(format)}?:"")}
    var course by rememberSaveable{mutableStateOf(old?.courseId)}
    EditorFrame("作业与待办",onDismiss,footer={TextButton(onClick=onDismiss){Text("取消")};TextButton(onClick={vm.act("待办已保存",onDismiss){
        val due=if(deadline.isBlank())null else try{LocalDateTime.parse(deadline.trim(),format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}catch(_:Exception){error("截止时间格式：yyyy-MM-dd HH:mm")}
        vm.repository.saveTodo(CampusTodo(old?.id?:newId(),title,body,due,course,old?.done?:false))
    }},modifier=Modifier.testTag("todo_save")){Text("保存")}}){
        OutlinedTextField(title,{title=it},label={Text("标题")},modifier=Modifier.fillMaxWidth().testTag("todo_title"))
        OutlinedTextField(body,{body=it},label={Text("内容")},modifier=Modifier.fillMaxWidth().testTag("todo_body"),maxLines=4)
        OutlinedTextField(deadline,{deadline=it},label={Text("截止时间（yyyy-MM-dd HH:mm，可空）")},modifier=Modifier.fillMaxWidth())
        Text("关联课程（可选）");FilterChip(course==null,{course=null},label={Text("不关联")})
        data.courses.forEach{c->val term=terms.find{t->t.id==placements.find{it.courseId==c.id}?.semesterId}
            FilterChip(course==c.id,{course=c.id},label={Text("${c.title} · 周${c.day+1} · ${term?.name?:"未设置学期"}")})
        }
        Text("提醒将在通知阶段接入。",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun InboxDialog(onDismiss:()->Unit) {
    val vm=LocalCampus.current;val data=LocalContent.current;val links by vm.links.collectAsState();val inbox=data.nodes.filter{it.parentId=="inbox-topic"}
    val notes=data.notes.filter {n->links.none{it.recordId==n.id&&data.node(it.topicId)?.kind==NodeKind.TOPIC}}
    var selected by rememberSaveable{mutableStateOf<String?>(null)}
    EditorFrame("待整理收件箱",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")}}){
        if(inbox.isEmpty()&&notes.isEmpty())Text("快速记录和未归档导入会保存在这里。")
        (inbox.map{it.id to it.title}+notes.map{it.id to it.title}).forEach{(id,title)->FeatureCard(title,"选择主题和课程关联"){selected=id}}
    }
    selected?.let{id->RecordAssociation(id){selected=null}}
}

@Composable
fun RecordAssociation(recordId:String,onDismiss:()->Unit) {
    val vm=LocalCampus.current;val data=LocalContent.current;val links by vm.links.collectAsState();val old=links.find{it.recordId==recordId}
    var topic by rememberSaveable{mutableStateOf(old?.topicId?:data.node(recordId)?.parentId?.takeIf{it!="inbox-topic"})};var course by rememberSaveable{mutableStateOf(old?.courseId)}
    EditorFrame("记录归属与课程",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")};TextButton(onClick={vm.act("关联已保存",onDismiss){
        vm.repository.assignRecord(recordId,course,topic)
    }}){Text("保存")}}){
        Text("课程（可选）");FilterChip(course==null,{course=null},label={Text("不关联")})
        data.courses.forEach{c->FilterChip(course==c.id,{course=c.id},label={Text(c.title+" · 周"+(c.day+1))})}
        Text("归档主题（可选）");FilterChip(topic==null,{topic=null},label={Text("待整理收件箱")})
        data.nodes.filter{it.kind==NodeKind.TOPIC&&it.id!="inbox-topic"}.forEach{t->FilterChip(topic==t.id,{topic=t.id},label={Text("${data.node(t.parentId)?.title} / ${t.title}")})}
    }
}

@Composable
fun TrashDialog(onDismiss:()->Unit) {
    val vm=LocalCampus.current;val trash by vm.trash.collectAsState();var purge by rememberSaveable{mutableStateOf<String?>(null)}
    EditorFrame("回收站",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")}}){
        Text("不会自动清空。恢复保留层级关系；永久删除才移除本地副本。外部原件不变。")
        if(trash.isEmpty())Text("回收站为空")
        trash.forEach{t->OutlinedCard{Column(Modifier.padding(12.dp)){Text(t.title);Text(t.kind,style=MaterialTheme.typography.bodySmall)
            Row{TextButton(onClick={vm.act("已恢复"){vm.repository.restoreTrash(t.id)}}){Text("恢复")};TextButton(onClick={purge=t.id}){Text("永久删除")}}}}}
    }
    purge?.let{id->DeleteConfirmation(trash.find{it.id==id}?.title?:"条目","永久删除此条目及其下级本地副本，无法恢复。",{purge=null}){
        vm.act("已永久删除",{purge=null}){vm.repository.purgeTrash(id)}
    }}
}

@Composable
fun LocalRecordDialog(recordId:String,onDismiss:()->Unit) {
    val vm=LocalCampus.current;val data=LocalContent.current;val context=LocalContext.current
    val note=data.notes.find{it.id==recordId};val file=data.node(recordId)
    var edit by rememberSaveable{mutableStateOf(false)};var association by rememberSaveable{mutableStateOf(false)};var deleting by rememberSaveable{mutableStateOf(false)}
    EditorFrame(note?.title?:file?.title?:"记录已移除",onDismiss,footer={TextButton(onClick=onDismiss){Text("返回")}}){
        if(note!=null){Text(note.body);TextButton(onClick={edit=true}){Text("编辑")}}
        if(file!=null){Text("${file.bytes} 字节 · 本地原件");TextButton(onClick={try{
            val local=vm.repository.document(file.localName?:error("无本地副本"));check(local.isFile)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.documents",local)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }catch(_:Exception){vm.error.value="无法打开文件，请确认本地副本存在且已安装对应查看应用"}}){Text("打开原件")}}
        if(note!=null||file!=null){TextButton(onClick={association=true}){Text("关联课程 / 归档主题")};TextButton(onClick={deleting=true}){Text("移入回收站")}}
    }
    if(edit&&note!=null)TextEditor("编辑记录",listOf("标题" to note.title,"正文" to note.body),{edit=false}){v->vm.act("记录已保存",{edit=false}){vm.repository.saveNote(note.copy(title=v[0],body=v[1]))}}
    if(association)RecordAssociation(recordId){association=false}
    if(deleting)DeleteConfirmation("记录","移入回收站后可以恢复。外部源文件不变。",{deleting=false}){
        vm.act("已移入回收站",onDismiss){if(note!=null)vm.repository.deleteNote(recordId)else vm.repository.deleteNode(recordId)}
    }
}
