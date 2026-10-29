package com.danielealbano.androidremotecontrolmcp.services.character

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32

@DisplayName("CharacterCardParser")
class CharacterCardParserTest {
    private val v2 =
        """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"小夏","description":"一只爱说话的猫。\n第二行。",
          "personality":"傲娇","scenario":"在阳台上","first_mes":"{{user}}，你回来啦～我是{{char}}。",
          "mes_example":"<START>\n{{user}}: 你好\n{{char}}: 喵","creator":"tester","tags":["cat","cute"],
          "alternate_greetings":["喵？"],"system_prompt":"","post_history_instructions":""}}"""

    @Test
    fun `v2 json card`() {
        val card = CharacterCardParser.fromJson(v2)!!
        assertEquals("小夏", card.name)
        assertEquals("傲娇", card.personality)
        assertEquals("一只爱说话的猫。", card.summary)
        assertEquals(listOf("cat", "cute"), card.tags)
        assertEquals(listOf("喵？"), card.alternateGreetings)
        assertEquals(16, card.id.length)
    }

    @Test
    fun `v1 flat json and v3 wrapper give the same id as v2 for the same text`() {
        val v1 = """{"name":"小夏","description":"一只爱说话的猫。\n第二行。","personality":"傲娇","scenario":"在阳台上",
            "first_mes":"{{user}}，你回来啦～我是{{char}}。","mes_example":""}"""
        val v3 = v2.replace("chara_card_v2", "chara_card_v3")
        assertEquals(CharacterCardParser.fromJson(v2)!!.id, CharacterCardParser.fromJson(v1)!!.id)
        assertEquals(CharacterCardParser.fromJson(v2)!!.id, CharacterCardParser.fromJson(v3)!!.id)
    }

    @Test
    fun `png with a chara chunk is a card and keeps the image as avatar`() {
        val png = pngWith("chara" to Base64.getEncoder().encodeToString(v2.toByteArray()))
        val parsed = CharacterCardParser.parse(png)!!
        assertEquals("小夏", parsed.card.name)
        assertTrue(parsed.avatarPng!!.contentEquals(png))
    }

    @Test
    fun `ccv3 chunk wins over chara and chunks after IDAT are still found`() {
        val v3 = v2.replace("小夏", "小夏三代").replace("chara_card_v2", "chara_card_v3")
        val png =
            pngWith(
                "chara" to Base64.getEncoder().encodeToString(v2.toByteArray()),
                "ccv3" to Base64.getEncoder().encodeToString(v3.toByteArray()),
                textAfterImage = true,
            )
        assertEquals("小夏三代", CharacterCardParser.parse(png)!!.card.name)
    }

    @Test
    fun `plain images, other json and garbage are not cards`() {
        assertNull(CharacterCardParser.parse(pngWith()))
        assertNull(CharacterCardParser.parse("""{"hello":"world"}""".toByteArray()))
        assertNull(CharacterCardParser.parse("""{"spec":"chara_card_v2","data":{"name":"  "}}""".toByteArray()))
        assertNull(CharacterCardParser.parse(byteArrayOf(1, 2, 3)))
        assertNull(CharacterCardParser.parse(pngWith("chara" to "not base64 !!!")))
    }

    @Test
    fun `a truncated png does not crash`() {
        val png = pngWith("chara" to Base64.getEncoder().encodeToString(v2.toByteArray()))
        assertNotNull(CharacterCardParser.parse(png))
        assertNull(CharacterCardParser.parse(png.copyOf(png.size / 2)))
    }

    /** A minimal PNG: signature, IHDR, optional tEXt chunks, IDAT, IEND. */
    private fun pngWith(
        vararg texts: Pair<String, String>,
        textAfterImage: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(out, "IHDR", ByteArray(13))
        val textChunks = {
            texts.forEach { (k, v) ->
                chunk(
                    out,
                    "tEXt",
                    k.toByteArray(Charsets.ISO_8859_1) + 0 + v.toByteArray(Charsets.ISO_8859_1),
                )
            }
        }
        if (!textAfterImage) textChunks()
        chunk(out, "IDAT", byteArrayOf(0, 1, 2, 3))
        if (textAfterImage) textChunks()
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc =
            CRC32()
                .apply {
                    update(typeBytes)
                    update(data)
                }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
    }
}
