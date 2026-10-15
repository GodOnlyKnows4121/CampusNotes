package cn.campus.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable
fun ScheduleScreen() {
    val vm=LocalCampus.current;val data=LocalContent.current;val placements by vm.placements.collectAsState();val semesters by vm.semesters.collectAsState();val settings by vm.settings.collectAsState();val focus by vm.scheduleFocus.collectAsState();val links by vm.links.collectAsState()
    val semester=semesters.find{it.id==settings.activeSemester};val times=semester?.times()?:Semester("legacy","","2020-01-06").times()
    var week by rememberSaveable(semester?.id){mutableIntStateOf(semester?.week(LocalDate.now())?.coerceIn(1,semester.weeks)?:1)}
    var editor by rememberSaveable{mutableStateOf<String?>(null)};var deleting by rememberSaveable{mutableStateOf<String?>(null)};var expanded by rememberSaveable{mutableStateOf<String?>(null)}
    var calendar by remember{mutableStateOf(false)};var settingsOpen by rememberSaveable{mutableStateOf(false)};var selectedRecord by rememberSaveable{mutableStateOf<String?>(null)}
    val scroll=rememberScrollState();val density=LocalDensity.current;val coroutine=rememberCoroutineScope();val progress=remember{Animatable(0f)}
    var gridOrigin by remember{mutableStateOf(Offset.Zero)};val bounds=remember{mutableStateMapOf<String,Rect>()}
    fun close(){coroutine.launch{progress.animateTo(0f,tween(150));expanded=null}}
    LaunchedEffect(expanded){if(expanded!=null){progress.snapTo(0f);progress.animateTo(1f,tween(220))}}
    LaunchedEffect(scroll.value){expanded=null}
    LaunchedEffect(focus,semesters,placements){val id=focus?:return@LaunchedEffect;val c=data.courses.find{it.id==id}?:return@LaunchedEffect;val p=placements.find{it.courseId==id}?:CoursePlacement.legacy(c)
        if(p.semesterId!=settings.activeSemester){vm.settingsStore.activeSemester(p.semesterId)}else{week=c.firstWeek;scroll.scrollTo(with(density){((p.startPeriod-1)*58).dp.roundToPx()});expanded=id;vm.scheduleFocus.value=null}}
    BackHandler(expanded!=null||calendar){if(calendar)calendar=false else close()}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp).testTag("schedule_screen")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){TextButton(onClick={settingsOpen=true}){Text(semester?.name?:"设置学期 / 查看旧课程",maxLines=1,overflow=TextOverflow.Ellipsis)};TextButton(onClick={editor="new"},modifier=Modifier.testTag("new_course")){Text("＋ 课程")}}
        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={expanded=null;week--},enabled=week>1){Text("‹")}
            Box {TextButton(onClick={if(semester==null)settingsOpen=true else calendar=!calendar},modifier=Modifier.testTag("week_calendar")){
                Column(horizontalAlignment=Alignment.CenterHorizontally){Text("第 $week 周 ⌄",style=MaterialTheme.typography.titleMedium);semester?.let{val start=LocalDate.parse(it.startDate).plusWeeks((week-1).toLong());Text("$start – ${start.plusDays(6)}",style=MaterialTheme.typography.labelSmall)}}
            }
            DropdownMenu(calendar,{calendar=false},modifier=Modifier.widthIn(max=330.dp).testTag("semester_calendar")){
                semester?.let {s->TeachingCalendar(s,week){w->week=w;calendar=false;expanded=null}}
            }}
            TextButton(onClick={expanded=null;week++},enabled=week<(semester?.weeks?:30)){Text("›")}
            TextButton(onClick={expanded=null;week=semester?.week(LocalDate.now())?.coerceIn(1,semester.weeks)?:1}){Text("本周",style=MaterialTheme.typography.labelMedium)}
        }}
        if(semester==null)Text("旧课程保留原时间；先设置学期才能按日期选周。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned{gridOrigin=it.positionInRoot()}){
            val column=(maxWidth-32.dp)/7;val row=58.dp;val header=38.dp;val availableW=maxWidth;val availableH=maxHeight
            val shown=data.courses.filter{c->val p=placements.find{it.courseId==c.id}?:CoursePlacement.legacy(c);p.semesterId==semester?.id&&p.occurs(c,week)}
            Box(Modifier.fillMaxSize().verticalScroll(scroll)){
                Column(Modifier.fillMaxWidth().height(header+row*times.size)){
                    Row(Modifier.height(header)){Spacer(Modifier.width(32.dp));repeat(7){day->Column(Modifier.width(column),horizontalAlignment=Alignment.CenterHorizontally){Text(listOf("一","二","三","四","五","六","日")[day],style=MaterialTheme.typography.labelSmall);semester?.let{Text(LocalDate.parse(it.startDate).plusWeeks((week-1).toLong()).plusDays(day.toLong()).dayOfMonth.toString(),style=MaterialTheme.typography.labelSmall)}}}}
                    times.forEachIndexed{index,t->Row(Modifier.height(row)){
                        Column(Modifier.width(32.dp).padding(top=10.dp)){Text((index+1).toString(),style=MaterialTheme.typography.labelSmall);Text(t.first.toString(),style=MaterialTheme.typography.labelSmall)}
                        repeat(7){Surface(Modifier.width(column).fillMaxHeight().padding(2.dp),shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){}}
                    }}
                }
                shown.forEach{c->val p=placements.find{it.courseId==c.id}?:CoursePlacement.legacy(c)
                    Surface(onClick={expanded=c.id},modifier=Modifier.offset(x=32.dp+column*c.day+2.dp,y=header+row*(p.startPeriod-1)+2.dp)
                        .width(column-4.dp).height(row*(p.endPeriod-p.startPeriod+1)-4.dp).onGloballyPositioned{val at=it.positionInRoot()-gridOrigin;val rect=Rect(at,androidx.compose.ui.geometry.Size(it.size.width.toFloat(),it.size.height.toFloat()));if(bounds[c.id]!=rect)bounds[c.id]=rect}.testTag("course_${c.id}"),
                        shape=RoundedCornerShape(9.dp),color=courseColor(c.id)){
                        Column(Modifier.padding(4.dp),verticalArrangement=Arrangement.SpaceBetween){Text(c.title,style=MaterialTheme.typography.labelSmall,maxLines=4,overflow=TextOverflow.Ellipsis);Text(c.room.substringAfterLast(' '),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)}
                    }
                }
            }
            val course=shown.find{it.id==expanded};val origin=course?.let{bounds[it.id]}
            if(course!=null&&origin!=null){
                Box(Modifier.matchParentSize().clickable(onClick={close()}).testTag("course_blank"))
                val p=placements.find{it.courseId==course.id}?:CoursePlacement.legacy(course)
                val targetW=minOf(280.dp,availableW-8.dp);val targetH=minOf((300*density.fontScale.coerceAtMost(1.6f)).dp,availableH-8.dp)
                val targetX=(with(density){origin.center.x.toDp()}-targetW/2).coerceIn(4.dp,(availableW-targetW-4.dp).coerceAtLeast(4.dp))
                val targetY=(with(density){origin.center.y.toDp()}-targetH/2).coerceIn(4.dp,(availableH-targetH-4.dp).coerceAtLeast(4.dp))
                val t=progress.value;val ow=with(density){origin.width.toDp()};val oh=with(density){origin.height.toDp()};val ox=with(density){origin.left.toDp()};val oy=with(density){origin.top.toDp()}
                Surface(Modifier.offset(x=ox+(targetX-ox)*t,y=oy+(targetY-oy)*t).width(ow+(targetW-ow)*t).height(oh+(targetH-oh)*t).clickable{}.testTag("course_details"),shape=RoundedCornerShape(16.dp),color=courseColor(course.id),shadowElevation=12.dp){
                    if(t>.85f)Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text(course.title,style=MaterialTheme.typography.titleLarge)
                        Text("周${listOf("一","二","三","四","五","六","日")[course.day]} · 第${p.startPeriod}–${p.endPeriod}节")
                        Text("${times.getOrNull(p.startPeriod-1)?.first}–${times.getOrNull(p.endPeriod-1)?.second}")
                        Text("教室：${course.room}");Text("教师：${course.teacher}");Text("第 ${course.firstWeek}–${course.lastWeek} 周 · ${mapOf("all" to "每周","odd" to "单周","even" to "双周","custom" to "指定周")[p.pattern]}")
                        if(course.example)ExampleNotice("示例课程")
                        val ids=links.filter{it.courseId==course.id}.map{it.recordId}.toSet()
                        (data.notes.filter{it.id in ids}.map{it.id to it.title}+data.nodes.filter{it.id in ids}.map{it.id to it.title}).forEach{(id,title)->TextButton(onClick={selectedRecord=id}){Text(title)}}
                        Row{TextButton(onClick={expanded=null;editor=course.id}){Text("编辑")};TextButton(onClick={expanded=null;deleting=course.id}){Text("删除")};TextButton(onClick={close()}){Text("收起详情")}}
                    }
                }
            }
        }
    }
    if(settingsOpen)SemesterSettings{settingsOpen=false}
    editor?.let{id->key(id){ScheduledCourseEditor(data.courses.find{it.id==id},semester?.id){editor=null}}}
    deleting?.let{id->DeleteConfirmation("课程","移入回收站后可恢复。",{deleting=null}){vm.act("课程已移入回收站",{deleting=null}){vm.repository.deleteCourse(id)}}}
    selectedRecord?.let{id->LocalRecordDialog(id){selectedRecord=null}}
}

private fun courseColor(id:String)=listOf(Color(0xFF233039),Color(0xFF302A3E),Color(0xFF24342D),Color(0xFF382E25))[Math.floorMod(id.hashCode(),4)]

@Composable
private fun TeachingCalendar(semester:Semester,week:Int,onWeek:(Int)->Unit){
    val first=LocalDate.parse(semester.startDate);val end=first.plusWeeks(semester.weeks.toLong()).minusDays(1)
    var month by remember{mutableStateOf(YearMonth.from(first.plusWeeks((week-1).toLong())))}
    Column(Modifier.width(288.dp).padding(8.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={month=month.minusMonths(1)},enabled=month>YearMonth.from(first)){Text("‹")};Text(month.toString());TextButton(onClick={month=month.plusMonths(1)},enabled=month<YearMonth.from(end)){Text("›")}
        }
        Row{listOf("一","二","三","四","五","六","日").forEach{Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)}}
        val start=month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value-1).toLong());val count=((month.atDay(1).dayOfWeek.value-1+month.lengthOfMonth()+6)/7)
        repeat(count){r->Row{repeat(7){c->val day=start.plusDays((r*7+c).toLong());val w=semester.week(day)
            Surface(Modifier.weight(1f),color=if(w==week)MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,shape=RoundedCornerShape(7.dp)){
                TextButton(onClick={onWeek(w)},enabled=day>=first&&day<=end,contentPadding=PaddingValues(0.dp),modifier=Modifier.height(36.dp).testTag("date_$day")){Text(day.dayOfMonth.toString(),style=MaterialTheme.typography.labelSmall)}
            }
        }}}
    }
}
