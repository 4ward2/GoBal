package com.danielealbano.androidremotecontrolmcp.services.im.render

/**
 * Turns one Markdown reply into the pieces a channel should send: unchanged text for a channel
 * that renders Markdown itself, or plain text plus pictures for one that only shows plain text.
 * [downgrade] turns a single [OutboundPart.Picture] into text when its picture could not be made
 * or delivered; drawing the picture itself happens elsewhere, outside this pure-logic layer.
 */
object ChannelRenderer {
    fun render(
        markdown: String,
        traits: ChannelTraits,
    ): List<OutboundPart> {
        if (markdown.isEmpty()) return emptyList()
        return if (traits.markdown) {
            TextChunker.chunk(markdown, traits.maxChars).map { OutboundPart.Text(it) }
        } else {
            renderPlain(markdown, traits)
        }
    }

    /** [picture]'s fallback text, split to fit [maxChars], for when its picture cannot be sent. */
    fun downgrade(
        picture: OutboundPart.Picture,
        maxChars: Int,
    ): List<OutboundPart.Text> = TextChunker.chunk(picture.fallback, maxChars).map { OutboundPart.Text(it) }

    private fun renderPlain(
        markdown: String,
        traits: ChannelTraits,
    ): List<OutboundPart> {
        val assembler = BlockAssembler()
        MarkdownBlocks.parse(markdown).forEach { dispatch(it, traits, assembler) }
        return assembler.finish().flatMap { toParts(it, traits.maxChars) }
    }

    private fun dispatch(
        block: MarkdownBlock,
        traits: ChannelTraits,
        out: BlockAssembler,
    ) {
        when (block) {
            is MarkdownBlock.Lines -> out.addText(PlainBlocks.render(block.lines))
            is MarkdownBlock.Table -> dispatchTable(block, traits, out)
            is MarkdownBlock.Code -> dispatchCode(block, traits, out)
            is MarkdownBlock.Formula -> dispatchFormula(block, traits, out)
        }
    }

    private fun dispatchTable(
        block: MarkdownBlock.Table,
        traits: ChannelTraits,
        out: BlockAssembler,
    ) {
        val fallback = TableText.fallback(block.lines)
        if (traits.images) {
            out.addPicture(OutboundPart.Picture(PictureKind.TABLE, block.lines.joinToString("\n"), fallback))
        } else {
            out.addText(fallback)
        }
    }

    private fun dispatchCode(
        block: MarkdownBlock.Code,
        traits: ChannelTraits,
        out: BlockAssembler,
    ) {
        if (!CodeText.isLong(block)) {
            out.addText(block.lines.joinToString("\n"))
            return
        }
        val fallback = CodeText.plain(block)
        if (traits.images) {
            out.addPicture(OutboundPart.Picture(PictureKind.CODE, CodeText.source(block), fallback))
        } else {
            out.addText(fallback)
        }
    }

    private fun dispatchFormula(
        block: MarkdownBlock.Formula,
        traits: ChannelTraits,
        out: BlockAssembler,
    ) {
        val fallback = FormulaText.fallback(block.lines)
        if (traits.images) {
            out.addPicture(OutboundPart.Picture(PictureKind.FORMULA, block.lines.joinToString("\n"), fallback))
        } else {
            out.addText(fallback)
        }
    }

    private fun toParts(
        chunk: Chunk,
        maxChars: Int,
    ): List<OutboundPart> =
        when (chunk) {
            is Chunk.TextChunk -> TextChunker.chunk(chunk.value, maxChars).map { OutboundPart.Text(it) }
            is Chunk.PictureChunk -> listOf(chunk.part)
        }
}

/** The merged-text-or-picture sequence [ChannelRenderer] builds before character-limit chunking. */
private sealed interface Chunk {
    data class TextChunk(
        val value: String,
    ) : Chunk

    data class PictureChunk(
        val part: OutboundPart.Picture,
    ) : Chunk
}

/**
 * Collects blocks into [Chunk]s: adjacent plain-text blocks are merged (joined by a blank line and
 * cleaned up) so the final split fills each message close to the limit instead of one block per
 * message; a picture flushes whatever text came before it first.
 */
private class BlockAssembler {
    private val chunks = mutableListOf<Chunk>()
    private val textBuffer = mutableListOf<String>()

    fun addText(text: String) {
        if (text.isNotEmpty()) textBuffer += text
    }

    fun addPicture(part: OutboundPart.Picture) {
        flushText()
        chunks += Chunk.PictureChunk(part)
    }

    fun finish(): List<Chunk> {
        flushText()
        return chunks
    }

    private fun flushText() {
        if (textBuffer.isEmpty()) return
        val joined = collapseBlankLines(textBuffer.joinToString("\n\n")).trim()
        if (joined.isNotEmpty()) chunks += Chunk.TextChunk(joined)
        textBuffer.clear()
    }

    private fun collapseBlankLines(text: String): String = text.replace(Regex("\n{3,}"), "\n\n")
}
