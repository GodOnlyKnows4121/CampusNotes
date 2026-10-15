package cn.campus.notes.integration

import android.content.Context
import android.util.Xml
import cn.campus.notes.data.LibraryNode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Bounded, offline extraction. Unsupported or scanned files remain available as originals. */
class DocumentParser(private val context: Context) {
    fun extract(file: File, node: LibraryNode, checkCancelled: () -> Unit = {}): String {
        checkCancelled()
        require(file.isFile && file.length() in 1..20_000_000) { "本机解析支持 20 MB 以内的文件；原件保留" }
        val ext = node.title.substringAfterLast('.', "").lowercase()
        val result = when {
            ext in setOf("txt", "md", "markdown", "csv", "json") || node.mime.startsWith("text/") -> decode(file.inputStream().use { it.readBytesBounded(240_004) })
            ext == "docx" || node.mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> office(file, false, checkCancelled)
            ext == "pptx" || node.mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> office(file, true, checkCancelled)
            ext == "pdf" || node.mime == "application/pdf" -> pdf(file, checkCancelled)
            ext in setOf("doc", "ppt") -> throw IllegalArgumentException("旧版 DOC/PPT 尚不支持本机解析，请另存为 DOCX/PPTX 后导入；原件仍可打开")
            else -> throw IllegalArgumentException("此格式可保存和外部打开，尚不支持本机文字解析")
        }.trim()
        require(result.isNotBlank()) { "未提取到可读文字；扫描件请导出图片后主动 OCR，原件保留" }
        require(result.length <= MAX_TEXT) { "文字超过 60000 字，请拆分文件后解析；不会截断后假装完整" }
        return result
    }

    private fun pdf(file: File, checkCancelled: () -> Unit): String {
        PDFBoxResourceLoader.init(context)
        val scratch = File(context.cacheDir, "pdf-text").apply { mkdirs() }
        val memory = MemoryUsageSetting.setupMixed(4_000_000, 64_000_000).setTempDir(scratch)
        return PDDocument.load(file, "", memory).use { doc ->
            require(doc.numberOfPages <= 200) { "单份 PDF 解析上限 200 页，请拆分后处理" }
            require(doc.currentAccessPermission.canExtractContent()) { "此 PDF 不允许提取文字，原件保留" }
            val stripper = PDFTextStripper()
            // Parse page by page so a large document cannot accumulate an unbounded output.
            buildString {
                for (page in 1..doc.numberOfPages) {
                    checkCancelled()
                    stripper.startPage = page; stripper.endPage = page
                    val output = this
                    stripper.writeText(doc, object : java.io.Writer() {
                        override fun write(chars: CharArray, offset: Int, count: Int) {
                            checkCancelled()
                            require(output.length + count <= MAX_TEXT) { "PDF 文字超过 60000 字，请拆分后解析" }
                            output.append(chars, offset, count)
                        }
                        override fun flush() { }
                        override fun close() { }
                    }); append('\n')
                    require(length <= MAX_TEXT) { "PDF 文字超过 60000 字，请拆分后解析" }
                }
            }
        }
    }

    private fun office(file: File, slides: Boolean, checkCancelled: () -> Unit): String = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().take(2049).toList()
        require(entries.size <= 2048) { "文档内部条目过多，请拆分后处理" }
        val selected = entries.filter { entry -> if (slides) Regex("ppt/slides/slide[0-9]+\\.xml").matches(entry.name)
            else entry.name == "word/document.xml" || Regex("word/(header|footer)[0-9]+\\.xml").matches(entry.name) ||
                entry.name in setOf("word/footnotes.xml", "word/endnotes.xml") }
            .sortedWith(compareBy({ if (slides) it.name.substringAfterLast('/').removePrefix("slide").substringBefore('.').toIntOrNull() ?: 0 else if (it.name == "word/document.xml") 0 else 1 }, { it.name }))
        require(selected.isNotEmpty() && selected.size <= 200) { "文档缺少正文或超过 200 个页面/章节" }
        var total = 0
        buildString {
            selected.forEachIndexed { index, entry ->
                checkCancelled()
                val bytes = zip.getInputStream(entry).use { it.readBytesBounded(2_000_000) }
                total += bytes.size
                require(total <= 12_000_000) { "文档解压后过大，已停止解析" }
                val xml = decode(bytes)
                require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "不支持包含外部实体或 DTD 的文档" }
                if (slides) append("\n第 ${index + 1} 页\n")
                val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(StringReader(xml)) }
                var text = false
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    checkCancelled()
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) { "t" -> text = true; "tab" -> append('\t'); "br" -> append('\n') }
                        XmlPullParser.TEXT -> if (text) append(parser.text)
                        XmlPullParser.END_TAG -> when (parser.name) { "t" -> text = false; "p", "tr" -> append('\n') }
                        XmlPullParser.DOCDECL -> throw IllegalArgumentException("文档包含不支持的 DTD")
                    }
                    require(length <= MAX_TEXT) { "文档文字超过 60000 字，请拆分后解析" }
                    event = parser.next()
                }
                append('\n')
            }
        }
    }

    private fun decode(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val value = try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw IllegalArgumentException("文字编码不支持，请转换为 UTF-8 或 UTF-16 后导入") }
        require(!value.contains('\u0000')) { "文件不是可读文字" }
        return value
    }

    companion object { const val MAX_TEXT = 60_000 }
}

internal fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        require(out.size() + n <= max) { "内容超过处理上限，原件保留" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
