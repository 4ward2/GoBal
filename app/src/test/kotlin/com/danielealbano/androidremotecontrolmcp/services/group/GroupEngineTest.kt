package com.danielealbano.androidremotecontrolmcp.services.group

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("Group chat")
class GroupEngineTest {
    private val boss = GroupMember("assistant", "小助手", models = listOf(SubModel("p", "m-pro")), admin = true)
    private val cat = GroupMember("char:1", "小夏", models = listOf(SubModel("p", "m-pro"), SubModel("p", "m-flash")))
    private val codex = GroupMember("remote:pc/a1", "Codex", remote = true)
    private val group = Group("g", "测试群", listOf(boss, cat, codex))

    /** Answers from a script: what each worker says, in the order it is asked. */
    private class Script(
        private val lines: Map<String, List<String>>,
    ) : GroupSpeaker {
        val asked = mutableListOf<Pair<String, String>>()
        private val turn = HashMap<String, Int>()

        override suspend fun speak(
            worker: GroupWorker,
            system: String,
            text: String,
        ): Result<String> {
            asked += worker.handle to text
            val index = turn.merge(worker.handle, 1, Int::plus)!! - 1
            val line = lines[worker.handle]?.getOrNull(index)
            return if (line == null) Result.failure(IllegalStateException("没连上")) else Result.success(line)
        }
    }

    private suspend fun run(script: Script): List<GroupMessage> {
        val said = mutableListOf(GroupMessage("u", GroupProtocol.USER, "做个登录页", 0))
        var now = 0L
        GroupEngine(script, { ++now }) { said += it }.run(group, said.toList())
        return said
    }

    @Test
    fun `a member with several models is one worker per model, and an agent on a computer is one`() {
        assertEquals(listOf("小助手", "小夏·m-pro", "小夏·m-flash", "Codex"), group.workers.map { it.handle })
        assertEquals(listOf("小助手"), group.admins.map { it.handle })
    }

    @Test
    fun `tasks are read by their handles, the longest first, and other lines are not tasks`() {
        val reply = "我来分一下：\n@小夏·m-flash: 写文案\n- @Codex：写代码\n@小夏 你觉得呢\n@不存在: 干活\n完了说一声"
        val tasks = GroupProtocol.assignments(reply, group.workers)
        assertEquals(listOf("小夏·m-flash" to "写文案", "Codex" to "写代码"), tasks.map { it.worker.handle to it.task })
    }

    @Test
    fun `the admin hands out, the members deliver, the admin sends one back and then concludes`() =
        runTest {
            val script =
                Script(
                    mapOf(
                        "小助手" to listOf("@Codex: 写登录页代码\n@小夏·m-flash: 写按钮文案", "@Codex: 少了密码框，补上", "都合格了，登录页做好了。"),
                        "Codex" to listOf("代码 v1", "代码 v2"),
                        "小夏·m-flash" to listOf("登录"),
                    ),
                )
            val said = run(script)
            assertEquals(
                listOf("user", "小助手", "Codex", "小夏·m-flash", "小助手", "Codex", "小助手"),
                said.map { it.from },
            )
            assertEquals("都合格了，登录页做好了。", said.last().text)
            assertTrue(said.filter { it.from == "小助手" }.all { it.admin })
            assertEquals("Codex", said[2].taskFor)
            // A member is given its task and nothing else of the conversation.
            assertEquals("少了密码框，补上", script.asked.last { it.first == "Codex" }.second)
            // The admin sees what was delivered when it reviews.
            assertTrue(
                script.asked
                    .last { it.first == "小助手" }
                    .second
                    .contains("代码 v2"),
            )
        }

    @Test
    fun `the admin's brief starts at the margin and names every worker`() {
        val brief = GroupProtocol.adminBrief(group, group.admins.single())
        assertTrue(brief.startsWith("你是群聊「测试群」的管理员「小助手」"))
        assertTrue(brief.lines().none { it.startsWith(" ") })
        assertTrue(group.workers.all { brief.contains("- @" + it.handle) })
    }

    @Test
    fun `an admin that answers directly ends the work at once`() =
        runTest {
            val said = run(Script(mapOf("小助手" to listOf("这个我直接答：用邮箱登录。"))))
            assertEquals(listOf("user", "小助手"), said.map { it.from })
        }

    @Test
    fun `a member that does not answer is reported to the admin, and endless sending back stops`() =
        runTest {
            val again = List(GroupEngine.MAX_ROUNDS + 2) { "@Codex: 再来" }
            val said = run(Script(mapOf("小助手" to again)))
            assertTrue(said.any { it.from == "Codex" && it.text.contains("没有完成") })
            assertEquals(GroupEngine.MAX_ROUNDS, said.count { it.from == "小助手" })
            assertEquals(GroupEngine.OUT_OF_ROUNDS, said.last().text)
        }

    @Test
    fun `an admin cannot give a task to itself`() =
        runTest {
            val said = run(Script(mapOf("小助手" to listOf("@小助手: 自己干"))))
            assertEquals(listOf("user", "小助手"), said.map { it.from })
        }

    @Test
    fun `groups and what was said in them are kept, and a deleted group is gone`(
        @TempDir dir: File,
    ) {
        val store = GroupStore(dir)
        store.save(group)
        store.append("g", GroupMessage("1", "user", "你好", 1))
        store.append("g", GroupMessage("2", "小助手", "在", 2, admin = true))
        val again = GroupStore(dir)
        assertEquals(group, again.groups().single())
        assertEquals(listOf("你好", "在"), again.messages("g").map { it.text })
        again.delete("g")
        assertTrue(GroupStore(dir).groups().isEmpty())
        assertFalse(File(dir, "g.json").exists())
    }
}
