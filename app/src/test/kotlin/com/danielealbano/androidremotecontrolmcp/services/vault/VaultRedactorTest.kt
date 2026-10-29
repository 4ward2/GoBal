package com.danielealbano.androidremotecontrolmcp.services.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VaultRedactor")
class VaultRedactorTest {
    private val redactor =
        VaultRedactor(
            mapOf(
                "phone" to "13800138000",
                "address" to "陕西省西安市长安区西沣路兴隆段266号",
                "email" to "Me@Example.com",
                "short" to "abc",
            ),
        )

    @Test
    fun `plain phone number is replaced`() {
        assertEquals("手机 ‹vault:phone› 已绑定", redactor.redact("手机 13800138000 已绑定"))
    }

    @Test
    fun `phone split by spaces or dashes is replaced`() {
        assertEquals("‹vault:phone›", redactor.redact("138 0013 8000"))
        assertEquals("‹vault:phone›", redactor.redact("138-0013-8000"))
    }

    @Test
    fun `phone inside a longer number is left alone`() {
        assertEquals("913800138000", redactor.redact("913800138000"))
        assertEquals("138001380001", redactor.redact("138001380001"))
    }

    @Test
    fun `chinese address is replaced inside tsv text`() {
        val row = "node_1\tTextView\t陕西省西安市长安区西沣路兴隆段266号\t-"
        assertEquals("node_1\tTextView\t‹vault:address›\t-", redactor.redact(row))
    }

    @Test
    fun `text match is case insensitive`() {
        assertEquals("mail: ‹vault:email›", redactor.redact("mail: me@example.com"))
    }

    @Test
    fun `values shorter than the minimum are not redacted`() {
        assertEquals("abc abcdef", redactor.redact("abc abcdef"))
    }

    @Test
    fun `every occurrence is replaced`() {
        assertEquals("‹vault:phone›/‹vault:phone›", redactor.redact("13800138000/13800138000"))
    }

    @Test
    fun `empty redactor returns text unchanged`() {
        assertEquals("13800138000", VaultRedactor(emptyMap()).redact("13800138000"))
    }

    @Test
    fun `regex metacharacters in values are treated literally`() {
        val r = VaultRedactor(mapOf("x" to "a.b*c"))
        assertEquals("aXbbc ‹vault:x›", r.redact("aXbbc a.b*c"))
    }

    @Test
    fun `two character chinese name is redacted`() {
        val r = VaultRedactor(mapOf("name" to "张三"))
        assertEquals("收货人 ‹vault:name›", r.redact("收货人 张三"))
    }

    @Test
    fun `country prefix and formatting variants of a phone are redacted`() {
        assertEquals("‹vault:phone›", redactor.redact("+86 138 0013 8000"))
        assertEquals("‹vault:phone›", redactor.redact("138.0013.8000"))
        assertEquals("‹vault:phone›", redactor.redact("138\u00a00013\u30008000"))
        val stored = VaultRedactor(mapOf("phone" to "+8613800138000"))
        assertEquals("tel ‹vault:phone›", stored.redact("tel 13800138000"))
    }

    @Test
    fun `id number ending in X is redacted in either case`() {
        val r = VaultRedactor(mapOf("id_card" to "11010519491231002X"))
        assertEquals("‹vault:id_card›", r.redact("110105 19491231 002x"))
    }

    @Test
    fun `multi line value is redacted in raw and json escaped form`() {
        val r = VaultRedactor(mapOf("address" to "西沣路266号\n3号楼"))
        assertEquals("‹vault:address›", r.redact("西沣路266号 3号楼"))
        assertEquals("{\"a\":\"‹vault:address›\"}", r.redact("{\"a\":\"西沣路266号\\n3号楼\"}"))
    }
}
