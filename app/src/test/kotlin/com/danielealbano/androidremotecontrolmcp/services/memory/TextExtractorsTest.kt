package com.danielealbano.androidremotecontrolmcp.services.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@DisplayName("TextExtractors")
class TextExtractorsTest {
    @Test
    fun `plain text is tidied but kept`() {
        val text = "第一行  \r\n\r\n\r\n\r\n第二行\r\n"
        assertEquals("第一行\n\n第二行", TextExtractors.plainText(ByteArrayInputStream(text.toByteArray())).text)
    }

    @Test
    fun `html loses tags and scripts, keeps paragraph breaks and entities`() {
        val html =
            "<html><head><style>p{}</style><script>alert(1)</script></head>" +
                "<body><h1>标题</h1><p>a &amp; b &lt;c&gt;</p><div>结尾<br>换行</div></body></html>"
        val text = TextExtractors.html(ByteArrayInputStream(html.toByteArray())).text
        assertEquals("标题\na & b <c>\n结尾\n换行", text)
    }

    @Test
    fun `docx paragraphs, tabs and runs come out in order`() {
        val xml =
            """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>""" +
                """<w:p><w:r><w:t xml:space="preserve">第一段 </w:t></w:r><w:r><w:t>续</w:t></w:r></w:p>""" +
                """<w:p><w:r><w:t>甲</w:t><w:tab/><w:t>乙 &amp; 丙</w:t><w:br/><w:t>丁</w:t></w:r></w:p>""" +
                """</w:body></w:document>"""
        val text = TextExtractors.docx(ByteArrayInputStream(docx(xml))).text
        assertEquals("第一段 续\n甲\t乙 & 丙\n丁", text)
    }

    @Test
    fun `a zip without the document part is not a docx`() {
        val bytes =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("other.txt"))
                        zip.write("x".toByteArray())
                        zip.closeEntry()
                    }
                }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { TextExtractors.docx(ByteArrayInputStream(bytes)) }
    }

    private fun docx(documentXml: String): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zip.write("<Types/>".toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write(documentXml.toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()
}
