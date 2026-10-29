package com.danielealbano.androidremotecontrolmcp.services.remote.paseo

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaseoGitTest {
    /**
     * Test parsing branch suggestions from branch_suggestions_response.
     * Schema: BranchSuggestionsResponseSchema (messages.ts line 5815)
     */
    @Test
    fun `branchSuggestions parses multiple branches with details`() {
        val payload =
            buildJsonObject {
                putJsonArray("branches") {
                    add("main")
                    add("develop")
                    add("feature/new-api")
                }
                putJsonArray("branchDetails") {
                    addJsonObject {
                        put("name", "main")
                        put("committerDate", 1696000000000)
                        put("hasLocal", true)
                        put("hasRemote", true)
                        put("localAhead", 0)
                    }
                    addJsonObject {
                        put("name", "develop")
                        put("committerDate", 1695000000000)
                        put("hasLocal", true)
                        put("hasRemote", true)
                        put("localAhead", 5)
                    }
                    addJsonObject {
                        put("name", "feature/new-api")
                        put("committerDate", 1694000000000)
                        put("hasLocal", true)
                        put("hasRemote", false)
                    }
                }
            }

        val branches = PaseoGitNodes.branchSuggestions(payload)

        assertEquals(3, branches.size)
        assertEquals("main", branches[0].name)
        assertEquals(1696000000000, branches[0].committerDate)
        assertFalse(branches[0].isRemote)

        assertEquals("develop", branches[1].name)
        assertEquals(1695000000000, branches[1].committerDate)

        assertEquals("feature/new-api", branches[2].name)
        assertFalse(branches[2].isRemote)
    }

    /**
     * Test parsing worktrees from paseo_worktree_list_response.
     * Schema: PaseoWorktreeSchema (messages.ts line 5882)
     */
    @Test
    fun `worktrees parses multiple worktrees with details`() {
        val payload =
            buildJsonObject {
                putJsonArray("worktrees") {
                    addJsonObject {
                        put("worktreePath", "/home/user/project/.git/worktrees/feature-a")
                        put("branchName", "feature/a")
                        put("head", "abc123def456")
                        put("createdAt", "2024-10-01T12:00:00Z")
                    }
                    addJsonObject {
                        put("worktreePath", "/home/user/project/.git/worktrees/feature-b")
                        put("branchName", "feature/b")
                        put("head", "def456ghi789")
                        put("createdAt", "2024-10-02T14:30:00Z")
                    }
                }
            }

        val worktrees = PaseoGitNodes.worktrees(payload)

        assertEquals(2, worktrees.size)
        assertEquals("/home/user/project/.git/worktrees/feature-a", worktrees[0].path)
        assertEquals("feature/a", worktrees[0].branch)
        assertEquals("abc123def456", worktrees[0].head)
        assertEquals("2024-10-01T12:00:00Z", worktrees[0].createdAt)

        assertEquals("feature/b", worktrees[1].branch)
    }

    /**
     * Test parsing push result from checkout_push_response.
     * Schema: CheckoutPushResponseSchema (messages.ts line 5422)
     */
    @Test
    fun `pushResult parses successful push`() {
        val payload =
            buildJsonObject {
                put("cwd", "/home/user/project")
                put("success", true)
            }

        val result = PaseoGitNodes.pushResult(payload)

        assertTrue(result.success)
        assertNull(result.error)
    }

    /**
     * Test parsing push result with error.
     */
    @Test
    fun `pushResult parses failed push with error message`() {
        val payload =
            buildJsonObject {
                put("cwd", "/home/user/project")
                put("success", false)
                putJsonObject("error") {
                    put("message", "Permission denied")
                    put("code", "403")
                }
            }

        val result = PaseoGitNodes.pushResult(payload)

        assertFalse(result.success)
        assertEquals("Permission denied", result.error)
    }

    /**
     * Test parsing validate branch response.
     * Schema: ValidateBranchResponseSchema (messages.ts line 5804)
     */
    @Test
    fun `validateBranchResponse parses existing branch`() {
        val payload =
            buildJsonObject {
                put("exists", true)
                put("resolvedRef", "refs/heads/main")
                put("isRemote", false)
            }

        val (exists, resolvedRef) = PaseoGitNodes.validateBranchResponse(payload)

        assertTrue(exists)
        assertEquals("refs/heads/main", resolvedRef)
    }

    /**
     * Test parsing validate branch response for non-existent branch.
     */
    @Test
    fun `validateBranchResponse parses non-existent branch`() {
        val payload =
            buildJsonObject {
                put("exists", false)
                put("resolvedRef", null)
                put("isRemote", false)
            }

        val (exists, resolvedRef) = PaseoGitNodes.validateBranchResponse(payload)

        assertFalse(exists)
        assertNull(resolvedRef)
    }

    /**
     * Test branch info parsing with current branch detection.
     */
    @Test
    fun `branchInfo detects current branch`() {
        val branchNode =
            buildJsonObject {
                put("name", "main")
                put("isRemote", false)
                put("committerDate", 1696000000000)
            }

        val branch = PaseoGitNodes.branchInfo(branchNode, currentBranch = "main")

        assertTrue(branch.isCurrent)
        assertEquals("main", branch.name)
        assertFalse(branch.isRemote)
    }

    /**
     * Test branch info parsing for remote branch.
     */
    @Test
    fun `branchInfo identifies remote branches`() {
        val branchNode =
            buildJsonObject {
                put("name", "origin/develop")
                put("hasLocal", false)
                put("hasRemote", true)
                put("committerDate", 1695000000000)
            }

        val branch = PaseoGitNodes.branchInfo(branchNode, currentBranch = "main")

        assertFalse(branch.isCurrent)
        assertTrue(branch.isRemote)
        assertEquals("origin/develop", branch.name)
    }
}
