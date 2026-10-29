package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import com.danielealbano.androidremotecontrolmcp.services.chat.Attachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/** Converts application attachments into Paseo protocol format for send_agent_message_request. */
class PaseoAttachments(
    private val rpc: PaseoRpc,
) {
    /**
     * Sends a message with attachments to an agent.
     *
     * @param agentId the agent ID (full or prefix)
     * @param text the message text
     * @param files the attachments to send
     * @param messageId optional client-provided message ID for deduplication
     * @return Result.success(Unit) if sent, Result.failure(...) if attachment is too large or other error
     */
    suspend fun send(
        agentId: String,
        text: String,
        files: List<Attachment>,
        messageId: String = "",
    ): Result<Unit> {
        val fields = messageFields(agentId, text, files, messageId)
        return rpc.call("send_agent_message_request", fields).map { }
    }

    companion object {
        private const val MAX_IMAGE_SIZE = 8 * 1024 * 1024 // 8 MB safe limit
        private const val MAX_FILE_SIZE = 8 * 1024 * 1024 // 8 MB safe limit

        /**
         * Builds the JSON payload for send_agent_message_request with images and attachments.
         *
         * @param agentId the agent ID
         * @param text the message text
         * @param files the attachments
         * @param messageId optional client-provided message ID
         * @return JsonObject with agentId, text, messageId, images, and attachments fields
         */
        fun messageFields(
            agentId: String,
            text: String,
            files: List<Attachment>,
            messageId: String,
        ): JsonObject =
            buildJsonObject {
                put("agentId", agentId)
                put("text", text)
                if (messageId.isNotEmpty()) {
                    put("messageId", messageId)
                }

                val (images, textAttachments) = files.partition { it.isImage }

                if (images.isNotEmpty()) {
                    put("images", imagesArray(images))
                }

                if (textAttachments.isNotEmpty()) {
                    put("attachments", attachmentsArray(textAttachments))
                }
            }

        private fun imagesArray(images: List<Attachment>): kotlinx.serialization.json.JsonArray {
            val imageObjects =
                images.mapNotNull { attachment ->
                    val file = File(attachment.path)
                    if (!file.exists()) {
                        return@mapNotNull null
                    }
                    if (file.length() > MAX_IMAGE_SIZE) {
                        return@mapNotNull null
                    }

                    val bytes = file.readBytes()
                    val base64 = Base64.getEncoder().encodeToString(bytes)

                    buildJsonObject {
                        put("data", base64)
                        put("mimeType", attachment.mime.ifEmpty { "image/jpeg" })
                    }
                }
            return JsonArray(imageObjects)
        }

        private fun attachmentsArray(textAttachments: List<Attachment>): kotlinx.serialization.json.JsonArray {
            val attachmentObjects =
                textAttachments.mapNotNull { attachment ->
                    val file = File(attachment.path)
                    if (!file.exists()) {
                        return@mapNotNull null
                    }
                    if (file.length() > MAX_FILE_SIZE) {
                        return@mapNotNull null
                    }

                    buildJsonObject {
                        put("type", "text")
                        put("mimeType", "text/plain")
                        put("title", attachment.name)
                        put("text", attachment.text)
                    }
                }
            return JsonArray(attachmentObjects)
        }
    }
}

/** Data class representing a file to send as an attachment. */
data class OutgoingFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OutgoingFile

        if (name != other.name) return false
        if (mimeType != other.mimeType) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
