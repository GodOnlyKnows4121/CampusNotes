package cn.campus.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*
import java.time.*

@Composable
fun HomeScreen(onNavigate:(Destination)->Unit) {
    val vm=LocalCampus.current;val data=LocalContent.current;val semesters by vm.semesters.collectAsState();val placements by vm.placements.collectAsState();val settings by vm.settings.collectAsState();val contentResults by vm.contentResults.collectAsState()
    val captureAction=rememberCaptureActions()
    var originalTimes by remember{mutableStateOf<Map<String,Long>>(emptyMap())}
    LaunchedEffect(data.nodes){originalTimes=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
        data.nodes.filter{it.kind==NodeKind.ORIGINAL&&it.localName!=null}.associate{it.id to vm.repository.document(it.localName!!).lastModified()}
    }}
    val recentRecords=(data.notes.map{RecentRecord(it.id,it.title,"文字记录",it.updatedAt)}+
        data.nodes.filter{it.kind==NodeKind.ORIGINAL}.map{RecentRecord(it.id,it.title,"原始资料",originalTimes[it.id]?:0L)}).sortedByDescending{it.at}
    var campus by rememberSaveable{mutableStateOf(false)};var records by rememberSaveable{mutableStateOf(false)};var inbox by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")};var search by rememberSaveable{mutableStateOf(false)};var recordId by rememberSaveable{mutableStateOf<String?>(null)};var allRecent by rememberSaveable{mutableStateOf(false)}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){
        var summary="";vm.act(onDone={vm.message.value=summary}){val topic=vm.repository.inboxTopic();summary=vm.repository.importDocuments(topic,uris)}
    }}
    val semester=semesters.find{it.id==settings.activeSemester};val now=LocalDateTime.now()
    val next=(0..7).flatMap{offset->val date=now.toLocalDate().plusDays(offset.toLong());data.courses.mapNotNull{c->
        val p=placements.find{it.courseId==c.id}?:CoursePlacement.legacy(c);val w=semester?.week(date)
        val times=semester?.times()?:Semester("legacy","","2020-01-06").times();val time=times.getOrNull(p.startPeriod-1)?.first
        if(c.day==date.dayOfWeek.value-1&&p.semesterId==semester?.id&&(w==null||p.occurs(c,w))&&time!=null&&LocalDateTime.of(date,time)>now)Triple(c,LocalDateTime.of(date,time),p)else null
    }}.minByOrNull{it.second}
    if(campus){CampusScreen({campus=false},{campus=false;onNavigate(Destination.SCHEDULE)});return}
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=4.dp).testTag("home_screen")) {
        val dense=maxHeight<330.dp||LocalDensity.current.fontScale>1.25f;val landscape=maxWidth>maxHeight
        @Composable fun CampusCard(modifier:Modifier){
            OutlinedCard(modifier,shape=RoundedCornerShape(20.dp)) {Row(Modifier.fillMaxSize().padding(if(dense)8.dp else 12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(if(dense)2.dp else 8.dp)){
                    Text("我的校园",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,maxLines=1)
                    if(!dense)Text("今日课程与校园记录",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary,maxLines=1)
                    OutlinedButton(onClick={campus=true},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp),modifier=Modifier.heightIn(min=28.dp)){Text("进入校园 →",style=MaterialTheme.typography.labelMedium,maxLines=1)}
                }
                Surface(onClick={next?.let{vm.scheduleFocus.value=it.first.id;onNavigate(Destination.SCHEDULE)}},modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                    Column(Modifier.padding(if(dense)7.dp else 12.dp),verticalArrangement=Arrangement.Center){
                        Text(if(semester==null)"课程预览" else "下一节课",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                        Text(next?.first?.let{it.title+if(it.example)" · 示例" else ""}?:"暂无课程",style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        next?.let {n->if(!dense){Text(if(semester==null)"请设置学期日期" else "${n.second.toLocalDate()} ${n.second.toLocalTime()}",style=MaterialTheme.typography.labelSmall);Text(n.first.room,style=MaterialTheme.typography.labelSmall,maxLines=1)}}
                    }
                }
            }}
        }
        @Composable fun Quick(modifier:Modifier){Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text("快速记录",fontWeight=FontWeight.Bold,style=if(dense)MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("录音","拍照","文字","导入").forEach{label->
                Surface(onClick={when(label){"文字"->records=true;"导入"->importer.launch(arrayOf("*/*"));else->captureAction(label)}},
                    modifier=Modifier.weight(1f).height(if(dense)44.dp else 66.dp).then(if(label=="文字")Modifier.testTag("capture")else Modifier),
                    shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                    Column(Modifier.padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                        if(!dense)LineGlyph(label)
                        Text(label,style=MaterialTheme.typography.labelMedium,maxLines=1)
                    }
                }
            }}
        }}
        @Composable fun Recent(modifier:Modifier){Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("最近记录",style=if(dense)MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);TextButton(onClick={allRecent=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=24.dp)){Text("查看全部 ›",style=MaterialTheme.typography.labelSmall)}}
            val recent=recentRecords.take(2)
            if(recent.isEmpty())Text("还没有记录，从快速记录开始。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
            recent.forEach{(id,title,meta)->OutlinedCard(onClick={recordId=id},modifier=Modifier.fillMaxWidth().weight(1f),shape=RoundedCornerShape(16.dp)){
                Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=if(dense)3.dp else 8.dp),verticalArrangement=Arrangement.Center){Text(title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);if(!dense)Text(meta,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)}
            }}
        }}
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(if(dense)6.dp else 12.dp)){
            OutlinedTextField(query,{query=it},placeholder={Text("搜索课程、笔记与资料…",style=MaterialTheme.typography.bodySmall,maxLines=1)},singleLine=true,
                modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),trailingIcon={TextButton(onClick={search=true}){Text("搜索",style=MaterialTheme.typography.labelSmall)}})
            if(landscape)Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)){CampusCard(Modifier.weight(1.2f).fillMaxHeight());Quick(Modifier.weight(1f));Recent(Modifier.weight(1f).fillMaxHeight())}
            else{CampusCard(Modifier.fillMaxWidth().weight(1.15f));Quick(Modifier.fillMaxWidth());Recent(Modifier.fillMaxWidth().weight(1f))}
        }
    }
    if(records)RecordsDialog{records=false};if(inbox)InboxDialog{inbox=false};recordId?.let{id->LocalRecordDialog(id){recordId=null}}
    if(allRecent)EditorFrame("全部记录",{allRecent=false},footer={TextButton(onClick={allRecent=false}){Text("返回")};TextButton(onClick={allRecent=false;inbox=true}){Text("待整理收件箱")}}){
        recentRecords.forEach{(id,title)->TextButton(onClick={allRecent=false;recordId=id}){Text(title)}}
    }
    if(search)EditorFrame("搜索本地资料",{search=false},footer={TextButton(onClick={search=false}){Text("返回")}}){
        val text=query.trim();if(text.isBlank())Text("请先输入关键词")else{
            data.courses.filter{it.title.contains(text,true)}.forEach{c->TextButton(onClick={search=false;vm.scheduleFocus.value=c.id;onNavigate(Destination.SCHEDULE)}){Text("课程 · ${c.title}")}}
            data.notes.filter{it.title.contains(text,true)||it.body.contains(text,true)}.forEach{n->TextButton(onClick={search=false;recordId=n.id}){Text("记录 · ${n.title}")}}
            data.nodes.filter{it.localName!=null&&it.title.contains(text,true)}.forEach{f->TextButton(onClick={search=false;recordId=f.id}){Text("资料 · ${f.title}")}}
            contentResults.filter{it.status=="confirmed"}.distinctBy{it.sourceId}.filter{it.text.contains(text,true)&&data.nodes.any{n->n.id==it.sourceId}}.forEach{r->TextButton(onClick={search=false;recordId=r.sourceId}){Text("已确认文字 · ${data.node(r.sourceId)?.title}")}}
        }
    }
}

private data class RecentRecord(val id:String,val title:String,val meta:String,val at:Long)
