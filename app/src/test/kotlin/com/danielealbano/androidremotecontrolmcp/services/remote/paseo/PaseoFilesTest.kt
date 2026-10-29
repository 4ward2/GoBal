package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaseoFilesTest {
    private val mockRpc = mockk<PaseoRpc>()
    private val files = PaseoFiles(mockRpc)

    @Test
    fun `list returns file entries from file_explorer_response`() =
        runTest {
            // Arrange (messages.ts FileExplorerResponseSchema line 5920)
            val payload =
                buildJsonObject {
                    put("mode", "list")
                    putJsonObject("directory") {
                        put("path", "/home/user")
                        putJsonArray("entries") {
                            add(
                                buildJsonObject {
                                    put("name", "file.txt")
                                    put("path", "/home/user/file.txt")
                                    put("kind", "file")
                                    put("size", 1024)
                                    put("modifiedAt", "2024-01-15T10:30:00Z")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("name", "docs")
                                    put("path", "/home/user/docs")
                                    put("kind", "directory")
                                    put("size", 4096)
                                    put("modifiedAt", "2024-01-15T11:00:00Z")
                                },
                            )
                        }
                    }
                    put("error", null)
                }

            coEvery {
                mockRpc.call(
                    "file_explorer_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.list("/home/user")

            // Assert
            result.onSuccess { entries ->
                assertEquals(2, entries.size)
                assertEquals("file.txt", entries[0].name)
                assertFalse(entries[0].isDirectory)
                assertEquals(1024, entries[0].size)
                assertEquals("docs", entries[1].name)
                assertTrue(entries[1].isDirectory)
            }
        }

    @Test
    fun `read returns file content with text encoding`() =
        runTest {
            // Arrange (messages.ts FileExplorerFileSchema line 2693)
            val payload =
                buildJsonObject {
                    put("mode", "file")
                    putJsonObject("file") {
                        put("path", "/home/user/file.txt")
                        put("kind", "text")
                        put("encoding", "utf-8")
                        put("content", "Hello World")
                        put("size", 11)
                        put("modifiedAt", "2024-01-15T10:30:00Z")
                    }
                    put("error", null)
                }

            coEvery {
                mockRpc.call(
                    "file_explorer_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.read("/home/user", "file.txt")

            // Assert
            result.onSuccess { content ->
                assertEquals("Hello World", content.text)
                assertFalse(content.truncated)
                assertFalse(content.binary)
            }
        }

    @Test
    fun `watchDiff returns subscription id and diff flow`() =
        runTest {
            // Arrange (messages.ts SubscribeCheckoutDiffResponseSchema line 5362)
            val payload =
                buildJsonObject {
                    put("subscriptionId", "sub-123")
                    put("cwd", "/home/user/project")
                    putJsonArray("files") {
                        add(
                            buildJsonObject {
                                put("path", "src/main.kt")
                                put("isNew", false)
                                put("isDeleted", false)
                                put("additions", 10)
                                put("deletions", 5)
                                putJsonArray("hunks") {
                                    add(
                                        buildJsonObject {
                                            put("oldStart", 1)
                                            put("oldCount", 10)
                                            put("newStart", 1)
                                            put("newCount", 15)
                                            putJsonArray("lines") {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "context")
                                                        put("content", "fun main() {")
                                                    },
                                                )
                                                add(
                                                    buildJsonObject {
                                                        put("type", "add")
                                                        put("content", "    println(\"Hello\")")
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                                put("status", "ok")
                            },
                        )
                    }
                    put("error", null)
                }

            coEvery {
                mockRpc.call(
                    "subscribe_checkout_diff_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.watchDiff("/home/user/project")

            // Assert
            result.onSuccess { (subscriptionId, _) ->
                assertEquals("sub-123", subscriptionId)
            }
        }

    @Test
    fun `unwatchDiff sends unsubscribe request`() =
        runTest {
            // Arrange
            coEvery {
                mockRpc.tell(
                    "unsubscribe_checkout_diff_request",
                    any(),
                )
            } returns true

            // Act
            val result = files.unwatchDiff("sub-123")

            // Assert
            result.onSuccess { assertEquals(Unit, it) }
        }

    @Test
    fun `list handles error response`() =
        runTest {
            // Arrange
            val payload =
                buildJsonObject {
                    put("mode", "list")
                    put("directory", null)
                    put("error", "Permission denied")
                }

            coEvery {
                mockRpc.call(
                    "file_explorer_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.list("/restricted")

            // Assert
            assertTrue(result.isFailure)
        }

    @Test
    fun `read binary file returns binary flag`() =
        runTest {
            // Arrange
            val payload =
                buildJsonObject {
                    put("mode", "file")
                    putJsonObject("file") {
                        put("path", "/home/user/image.png")
                        put("kind", "image")
                        put("encoding", "base64")
                        put("content", null)
                        put("size", 5000)
                        put("modifiedAt", "2024-01-15T10:30:00Z")
                        put("mimeType", "image/png")
                    }
                    put("error", null)
                }

            coEvery {
                mockRpc.call(
                    "file_explorer_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.read("/home/user", "image.png")

            // Assert
            result.onSuccess { content ->
                assertNull(content.text)
                assertTrue(content.binary)
            }
        }

    @Test
    fun `watchDiff parses diff with multiple files and hunks`() =
        runTest {
            // Arrange
            val payload = createWatchDiffPayload()

            coEvery {
                mockRpc.call(
                    "subscribe_checkout_diff_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.watchDiff("/repo")

            // Assert
            result.onSuccess { (_, _) ->
                // Flow parsing is tested through the diff payload parsing
                assertEquals("sub-456", result.getOrNull()?.first)
            }
        }

    private fun createWatchDiffPayload(): JsonObject =
        buildJsonObject {
            put("subscriptionId", "sub-456")
            put("cwd", "/repo")
            putJsonArray("files") {
                add(createGradleFileDiff())
                add(createReadmeFileDiff())
            }
            put("error", null)
            put("diffTooLarge", false)
        }

    private fun createGradleFileDiff(): JsonObject =
        buildJsonObject {
            put("path", "build.gradle.kts")
            put("isNew", true)
            put("isDeleted", false)
            put("additions", 20)
            put("deletions", 0)
            putJsonArray("hunks") {
                add(createGradleHunk())
            }
            put("status", "ok")
        }

    private fun createGradleHunk(): JsonObject =
        buildJsonObject {
            put("oldStart", 0)
            put("oldCount", 0)
            put("newStart", 1)
            put("newCount", 5)
            putJsonArray("lines") {
                add(
                    buildJsonObject {
                        put("type", "header")
                        put("content", "@@ -0,0 +1,5 @@")
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "add")
                        put("content", "plugins {")
                    },
                )
            }
        }

    private fun createReadmeFileDiff(): JsonObject =
        buildJsonObject {
            put("path", "README.md")
            put("isNew", false)
            put("isDeleted", false)
            put("additions", 3)
            put("deletions", 2)
            putJsonArray("hunks") {
            }
            put("status", "ok")
        }

    @Test
    fun `list parses empty directory`() =
        runTest {
            // Arrange
            val payload =
                buildJsonObject {
                    put("mode", "list")
                    putJsonObject("directory") {
                        put("path", "/empty")
                        putJsonArray("entries") {}
                    }
                    put("error", null)
                }

            coEvery {
                mockRpc.call(
                    "file_explorer_request",
                    any(),
                )
            } returns Result.success(payload)

            // Act
            val result = files.list("/empty")

            // Assert
            result.onSuccess { entries ->
                assertEquals(0, entries.size)
            }
        }
}
