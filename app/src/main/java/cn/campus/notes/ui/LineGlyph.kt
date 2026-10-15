package cn.campus.notes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

@Composable
fun LineGlyph(kind:String,modifier:Modifier=Modifier) {
    val ink=MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(22.dp)) {scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
        val stroke=Stroke(1.6f)
        fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(ink,Offset(x,y),Offset(a,b),1.6f)
        fun box(x:Float,y:Float,w:Float,h:Float,r:Float=2f)=drawRoundRect(ink,Offset(x,y),Size(w,h),CornerRadius(r),style=stroke)
        when(kind){
            "录音","AI"->{box(9f,2f,6f,12f,3f);drawArc(ink,0f,180f,false,Offset(5f,7f),Size(14f,12f),style=stroke);line(12f,19f,12f,23f);line(8f,23f,16f,23f)}
            "拍照"->{box(2f,6f,20f,15f,3f);drawCircle(ink,4f,Offset(12f,13f),style=stroke);line(7f,6f,9f,3f);line(9f,3f,15f,3f);line(15f,3f,17f,6f)}
            "文字"->{val p=Path().apply{moveTo(4f,20f);lineTo(4f,15f);lineTo(17f,2f);lineTo(22f,7f);lineTo(9f,20f);close()};drawPath(p,ink,style=stroke)}
            "导入"->{box(5f,2f,14f,20f);line(12f,18f,12f,8f);line(8f,12f,12f,8f);line(12f,8f,16f,12f)}
            "资料库"->{box(3f,3f,4f,18f,0f);box(9f,3f,4f,18f,0f);line(16f,3f,22f,20f);line(19f,2f,24f,19f)}
            "更多"->{for(x in listOf(6f,18f))for(y in listOf(6f,18f))drawCircle(ink,3f,Offset(x,y),style=stroke)}
            "日程"->{box(3f,5f,18f,17f);line(3f,10f,21f,10f);line(7f,2f,7f,7f);line(17f,2f,17f,7f);for(x in listOf(7f,12f,17f))for(y in listOf(14f,18f))drawCircle(ink,.8f,Offset(x,y))}
            "消息"->{val p=Path().apply{moveTo(4f,18f);lineTo(6f,14f);lineTo(6f,9f);cubicTo(6f,1f,18f,1f,18f,9f);lineTo(18f,14f);lineTo(20f,18f);close()};drawPath(p,ink,style=stroke);drawArc(ink,0f,180f,false,Offset(10f,19f),Size(4f,3f),style=stroke)}
            "首页"->{val p=Path().apply{moveTo(2f,11f);lineTo(12f,3f);lineTo(22f,11f);moveTo(5f,9f);lineTo(5f,22f);lineTo(19f,22f);lineTo(19f,9f)};drawPath(p,ink,style=stroke);box(10f,15f,4f,7f,0f)}
            else->{val p=Path().apply{moveTo(12f,2f);quadraticTo(12f,11f,22f,12f);quadraticTo(12f,12f,12f,22f);quadraticTo(12f,12f,2f,12f);quadraticTo(12f,12f,12f,2f)};drawPath(p,ink,style=stroke)}
        }
    }}
}
