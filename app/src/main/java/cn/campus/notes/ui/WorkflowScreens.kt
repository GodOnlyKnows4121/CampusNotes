package cn.campus.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*
import cn.campus.notes.integration.ThinkingLevel

fun workflowStatus(value:String)=when(value){"planning"->"模型正在规划";"processing"->"正在识别/解析";"review"->"等待逐份核对";"answering"->"正在生成结果";"complete"->"模型流程已完成";"cancelled"->"已取消";"interrupted"->"应用退出后中断";"invalidated"->"来源已删除";else->"未完成"}
fun toolLabel(value:String)=when(value){"asr"->"语音转写 ASR";"ocr"->"图片识别 OCR";"parse"->"本机文字提取";"read_confirmed"->"使用已确认文字";"skip"->"本次不使用";else->value}

@Composable fun AttachmentPicker(sessionId:String,audioOnly:Boolean,onDismiss:()->Unit){
    val vm=LocalCampus.current;val data=LocalContent.current
    val selected by vm.attachments.collectAsState();val busy by vm.busy.collectAsState()
    val ids=selected.filter{it.sessionId==sessionId}.map{it.sourceId}.toSet()
    var recording by rememberSaveable{mutableStateOf(false)}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())vm.importAttachments(sessionId,uris)}
    EditorFrame(if(audioOnly)"添加音频" else "添加本次资料",onDismiss,footer={TextButton(onClick=onDismiss){Text("完成（${ids.size}/10）")}}){
        Text("选择和导入只保存到本机，不调用模型。发送时会预览本次资料与服务；最多10份，可逐份移除。")
        OutlinedButton(enabled=!busy,onClick={importer.launch(if(audioOnly)arrayOf("audio/*")else arrayOf("*/*"))},modifier=Modifier.testTag("workflow_import")){Text("系统文件多选导入")}
        Text("支持音频、照片、DOCX、PPTX、PDF、TXT、Markdown 等；旧 DOC/PPT 可保存，当前不能直接解析。",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled=!busy,onClick={recording=true}){Text("录制新音频")}
        val originals=data.nodes.filter{it.kind==NodeKind.ORIGINAL&&(!audioOnly||cn.campus.notes.integration.processingMime(it).startsWith("audio/"))}
        if(originals.isEmpty())Text("当前没有可选择的原件，可先导入或在首页保存。")
        originals.forEach{source->OutlinedCard(Modifier.fillMaxWidth()) {Row(Modifier.padding(8.dp)){
            Checkbox(source.id in ids,{vm.setAttachment(sessionId,source.id,it)},enabled=!busy,modifier=Modifier.testTag("attach_${source.id}"))
            Column(Modifier.weight(1f)){Text(source.title);Text("${source.bytes}字节 · ${source.mime}",style=MaterialTheme.typography.bodySmall)}
        }}}
    }
    if(recording)RecordingDialog(onAttach={id->vm.setAttachment(sessionId,id,true)}){recording=false}
}

@Composable fun WorkflowPreview(approval:WorkflowApproval,onDismiss:()->Unit){
    val vm=LocalCampus.current;val running by vm.aiRunning.collectAsState();val busy by vm.busy.collectAsState()
    EditorFrame("本次联动范围",onDismiss,footer={TextButton(onClick=onDismiss){Text("取消")};Button(enabled=running==null&&!busy,onClick={onDismiss();vm.startWorkflow(approval)},modifier=Modifier.testTag("approve_workflow")){Text("同意本次范围并开始")}}){
        Text("目标：${approval.prompt}")
        Text("语言模型：${approval.chat.model}\n${approval.chat.endpoint}\n强度：${ThinkingLevel.from(approval.reasoning).label}")
        Text("先把目标及所选文件名、类型交给语言模型规划；只执行本次所选来源的有效识别工具。未确认的识别文字不会发送给语言模型。")
        approval.sources.forEachIndexed{index,source->Text("${index+1}. ${source.title}\n${source.bytes}字节 · ${toolLabel(if(source.confirmedId!=null)"read_confirmed" else source.tool)}")}
        if(approval.sources.any{it.tool=="asr"&&it.confirmedId==null})Text("ASR：${approval.asr.model}\n${approval.asr.endpoint}\nAAC/M4A最多按3分钟/5MB分段，每份最多128段，可能产生多次识别请求。")
        if(approval.sources.any{it.tool=="ocr"&&it.confirmedId==null})Text("OCR：${approval.ocr.model}\n${approval.ocr.endpoint}\n单次最多等待约5.5分钟，可取消。")
        Text("语言模型和在线识别可能计费，不自动重试；本机解析不联网。已有确认文字或核对后的文字会用于本次回答/整理，合计上限40000字。原件保留。")
        if(approval.mode=="organize"){
            Text("本组独立规则：${approval.rule.ifBlank{"保留原件，成果单独保存"}}")
            Text(if(approval.sandboxAuto)"本次已开启沙箱自主整理：有效计划可自动新建成果。" else "识别与模型计划完成后，仍须预览确认才写入成果。")
        }
    }
}

@Composable fun WorkflowCard(workflow:AiWorkflow,onOpen:()->Unit){
    val a=runCatching{WorkflowApproval.read(workflow.approval)}.getOrNull()
    OutlinedCard(Modifier.fillMaxWidth().testTag("workflow_${workflow.id}")) {Column(Modifier.padding(12.dp)){
        Text("资料联动 · ${workflowStatus(workflow.status)}",style=MaterialTheme.typography.titleSmall)
        Text(a?.prompt.orEmpty(),maxLines=2)
        if(workflow.error.isNotBlank())Text(workflow.error,color=MaterialTheme.colorScheme.error)
        TextButton(onClick=onOpen){Text(if(workflow.status=="review")"核对文字并继续" else "查看联动日志")}
    }}
}

@Composable fun WorkflowDetailDialog(id:String,onDismiss:()->Unit){
    val vm=LocalCampus.current;val tasks by vm.workflows.collectAsState();val results by vm.contentResults.collectAsState()
    val organize by vm.tasks.collectAsState();val running by vm.aiRunning.collectAsState();val activeTask by vm.runningWorkflow.collectAsState();val busy by vm.busy.collectAsState()
    val task=tasks.find{it.id==id}
    val approval=task?.let{runCatching{WorkflowApproval.read(it.approval)}.getOrNull()}
    val references=task?.let{runCatching{WorkflowResult.read(it.results)}.getOrDefault(emptyList())}.orEmpty()
    var reviewId by rememberSaveable{mutableStateOf<String?>(null)};var originalId by rememberSaveable{mutableStateOf<String?>(null)}
    val ready=references.any{it.resultId!=null}&&references.filter{it.resultId!=null}.all{item->results.any{it.id==item.resultId&&it.status=="confirmed"}}
    EditorFrame("资料联动详情",onDismiss,footer={
        TextButton(onClick=onDismiss){Text("返回")}
        if(task?.status=="review")Button(enabled=ready&&running==null&&!busy,onClick={vm.resumeWorkflow(id)},modifier=Modifier.testTag("resume_workflow")){Text("继续模型请求")}
        if(activeTask==id&&running!=null)TextButton(onClick=vm::cancelAi){Text("停止")}
    }){
        if(task==null||approval==null)Text("任务已不存在")else{
            Text(workflowStatus(task.status));Text("目标：${approval.prompt}")
            Text("本次模型：${approval.chat.model} · ${ThinkingLevel.from(approval.reasoning).label}\n${approval.chat.endpoint}")
            if(task.error.isNotBlank())Text(task.error,color=MaterialTheme.colorScheme.error)
            if(approval.mode=="organize")Text("独立整理规则：${approval.rule}")
            approval.sources.forEach{source->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){
                Text(source.title,style=MaterialTheme.typography.titleSmall)
                val item=references.find{it.sourceId==source.id};val result=results.find{it.id==item?.resultId}
                Text(item?.let{toolLabel(it.tool)}?:"待模型规划")
                if(result!=null){
                    Text(if(result.status=="confirmed")"文字已核对确认" else "文字尚未确认")
                    TextButton(onClick={reviewId=result.id},modifier=Modifier.testTag("workflow_review_${result.id}")){Text("查看 / 核对这份文字")}
                }
                TextButton(enabled=LocalContent.current.nodes.any{it.id==source.id},onClick={originalId=source.id}){Text("查看原件与来源")}
            }}}
            if(task.status=="review"){
                Text("请逐份打开核对，修正后确认；全部有效文字确认后，点击继续会向上述语言模型发送本次文字，可能计费。")
                TextButton(enabled=running==null&&!busy,onClick={vm.cancelPendingWorkflow(id)}){Text("取消待核对任务")}
            }
            task.organizeTaskId?.let{organizeId->organize.find{it.id==organizeId}?.let{plan->
                HorizontalDivider();Text("整理成果计划 · ${plan.status}");Text(plan.title);Text(plan.body)
                if(plan.status=="preview")TextButton(enabled=running==null,onClick={vm.confirmOrganize(plan.id)}){Text("确认写入沙箱（原件保留）")}
                if(plan.status=="complete")Text("成果已实际保存，原件保留")
            }}
            Text("执行日志",style=MaterialTheme.typography.titleMedium);Text(task.log)
            if(task.status in setOf("error","cancelled","interrupted"))TextButton(enabled=running==null&&!busy,onClick={
                vm.prepareWorkflow(approval.mode,approval.prompt,approval.sources.map{it.id},if(approval.mode=="organize")approval.chat.id else null,
                    if(approval.mode=="organize")approval.reasoning else null,approval.topicId)
            }){Text("重新预览任务（不会自动重试）")}
        }
    }
    reviewId?.let{rid->results.find{it.id==rid}?.let{ContentReviewDialog(it){reviewId=null}}}
    originalId?.let{LocalRecordDialog(it){originalId=null}}
}
