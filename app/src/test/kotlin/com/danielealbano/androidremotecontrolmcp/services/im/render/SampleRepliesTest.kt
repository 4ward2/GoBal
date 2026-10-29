package com.danielealbano.androidremotecontrolmcp.services.im.render

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Exercises [ChannelRenderer] against whole simulated agent replies instead of hand-picked
 * snippets. The fixtures under `src/test/resources/render/sample-*.md` are not real transcripts
 * from any assistant or user — they were written for this test to look like the kind of reply a
 * coding agent sends (a short report, a file list, a diff table, fenced code, a formula, mixed
 * Chinese/English, emoji) so the renderer sees the same shapes it will see in production.
 */
@DisplayName("ChannelRenderer against whole sample replies")
class SampleRepliesTest {
    private val samples =
        listOf(
            "sample-refactor-report.md",
            "sample-bugfix-chat.md",
            "sample-deploy-notes.md",
            "sample-math-explainer.md",
            "sample-long-code-walkthrough.md",
            "sample-mixed-everything.md",
        )

    private val maxChars = 2000
    private val noImages = ChannelTraits(markdown = false, images = false, files = false, maxChars = maxChars)
    private val withImages = ChannelTraits(markdown = false, images = true, files = false, maxChars = maxChars)

    @TestFactory
    fun `every text part fits the limit and carries no markdown residue`(): List<DynamicTest> =
        samples.flatMap { name ->
            val markdown = load(name)
            listOf(noImages, withImages).map { traits ->
                DynamicTest.dynamicTest("$name (images=${traits.images})") {
                    val parts = ChannelRenderer.render(markdown, traits)
                    assertTrue(parts.isNotEmpty(), "$name produced no parts at all")
                    parts.filterIsInstance<OutboundPart.Text>().forEach { text ->
                        assertTrue(text.text.length <= maxChars, "$name: a Text part exceeds $maxChars chars")
                        val residue = MarkdownResidueCheck.residueIn(text.text)
                        assertTrue(residue == null, "$name left markdown residue ($residue) in: ${text.text.take(200)}")
                    }
                }
            }
        }

    @TestFactory
    fun `a table becomes a picture with a pipe-free fallback when images are allowed`(): List<DynamicTest> =
        samples.map { name ->
            DynamicTest.dynamicTest(name) {
                val markdown = load(name)
                val parts = ChannelRenderer.render(markdown, withImages)
                val allPictures = parts.filterIsInstance<OutboundPart.Picture>()
                val tablePictures = allPictures.filter { it.kind == PictureKind.TABLE }
                val hadTable = MarkdownBlocks.parse(markdown).any { it is MarkdownBlock.Table }
                if (hadTable) {
                    assertTrue(tablePictures.isNotEmpty(), "$name has a table but produced no table picture")
                }
                tablePictures.forEach { picture ->
                    assertFalse(picture.fallback.contains('|'), "$name: table fallback still has a pipe")
                    assertTrue(picture.source.contains('|'), "$name: table picture source lost its Markdown")
                }
            }
        }

    private fun load(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/render/$name")) { "missing fixture $name" }
            .bufferedReader(Charsets.UTF_8)
            .readText()
}
