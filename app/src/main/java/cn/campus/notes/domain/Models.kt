package cn.campus.notes.domain

data class Course(
    val id: String, val title: String, val day: Int, val slot: Int,
    val room: String, val teacher: String, val weeks: String = "第 1–16 周"
)
data class LibrarySubject(val id: String, val title: String, val topics: List<String>)
data class LibraryGroup(
    val id: String, val title: String, val organizationRule: String,
    val subjects: List<LibrarySubject>
)

enum class WriteScope { APP_SANDBOX, EXTERNAL }
enum class WriteDecision { ALLOW, REQUIRE_CONFIRMATION }

/** Future executors must resolve the actual destination and enforce this before writing.
 * This policy alone does not grant OS permissions or constitute an implemented sandbox.
 */
object WritePolicy {
    fun evaluate(scope: WriteScope, sensitive: Boolean): WriteDecision =
        if (scope == WriteScope.APP_SANDBOX && !sensitive) WriteDecision.ALLOW
        else WriteDecision.REQUIRE_CONFIRMATION
}

object DemoData {
    val courses = listOf(
        Course("math", "高等数学", 0, 0, "教学楼 A201", "示例教师"),
        Course("english", "大学英语", 1, 1, "教学楼 B302", "示例教师"),
        Course("coding", "程序设计", 2, 0, "实验楼 203", "示例教师"),
        Course("physics", "大学物理", 3, 2, "教学楼 A105", "示例教师"),
        Course("sport", "体育", 4, 3, "运动场", "示例教师")
    )
    val groups = listOf(
        LibraryGroup("study", "我的学科", "按学科 → 课堂主题归档，原件与 AI 成果分开保存。", listOf(
            LibrarySubject("math", "高等数学", listOf("极限与连续", "导数与微分")),
            LibrarySubject("coding", "程序设计", listOf("变量与类型", "流程控制"))
        )),
        LibraryGroup("work", "我的工作", "按项目 → 任务整理，保留来源和版本，不覆盖原件。", listOf(
            LibrarySubject("project", "项目资料", listOf("需求讨论", "阶段交付")),
            LibrarySubject("meeting", "会议记录", listOf("每周例会"))
        ))
    )
}
