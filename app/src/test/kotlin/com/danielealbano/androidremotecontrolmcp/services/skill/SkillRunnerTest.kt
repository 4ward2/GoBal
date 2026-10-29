package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.Node
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.screen
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.window
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SkillRunner")
class SkillRunnerTest {
    private val app = "com.sankuai.meituan"

    /** A scripted phone: [screenFor] decides what is shown from what has been done so far. */
    private inner class FakeDevice(
        var ocrText: String? = null,
        var clickWorks: Boolean = true,
        val screenFor: (FakeDevice) -> MultiWindowResult,
    ) : SkillDevice {
        val actions = mutableListOf<String>()
        var scrolls = 0

        override fun read(): MultiWindowResult = screenFor(this)

        override suspend fun click(
            nodeId: String,
            snapshot: MultiWindowResult,
        ): Result<Unit> {
            actions += "click:$nodeId"
            return if (clickWorks) Result.success(Unit) else Result.failure(IllegalStateException("no"))
        }

        override suspend fun tap(
            x: Float,
            y: Float,
        ): Result<Unit> {
            actions += "tap:${x.toInt()},${y.toInt()}"
            return Result.success(Unit)
        }

        override suspend fun setText(
            nodeId: String,
            text: String,
            snapshot: MultiWindowResult,
        ): Result<Unit> {
            actions += "type:$nodeId=$text"
            return Result.success(Unit)
        }

        override suspend fun scroll(direction: SkillScrollDirection): Result<Unit> {
            scrolls++
            actions += "scroll"
            return Result.success(Unit)
        }

        override suspend fun back(): Result<Unit> {
            actions += "back"
            return Result.success(Unit)
        }

        override suspend fun launch(
            packageName: String,
            uri: String?,
        ): Result<Unit> {
            actions += "launch:$packageName"
            return Result.success(Unit)
        }

        override suspend fun ocrScreen(): String? {
            actions += "ocr"
            return ocrText
        }

        /** Texts OCR "sees" on screen and where. */
        var ocrHits: Map<String, OcrHit> = emptyMap()

        /** Where the picture is found, or null. */
        var imageHit: OcrHit? = null

        override suspend fun findImage(step: SkillStep.Image): OcrHit? {
            actions += "image"
            return imageHit
        }

        override suspend fun ocrLocate(text: String): OcrHit? {
            actions += "locate:$text"
            return ocrHits[text]
        }
    }

    private var now = 0L

    private fun runner(device: SkillDevice) =
        SkillRunner(
            device,
            SkillTiming(pauseMinMs = 0, pauseMaxMs = 0),
            clock = { now },
            sleep = { now += it },
        )

    private suspend fun SkillRunner.runSteps(vararg steps: SkillStep): SkillRunResult {
        val skill = Skill(name = "x", steps = steps.toList())
        return run(skill, emptyMap())
    }

    private val orderSkill =
        Skill(
            name = "点{{店名}}外卖",
            params = listOf(SkillParam("店名", "蜜雪冰城")),
            steps =
                listOf(
                    SkillStep.Launch(app),
                    SkillStep.Type(text = "{{店名}}"),
                    SkillStep.Tap(NodeSelector(text = "{{店名}}", match = TextMatch.CONTAINS)),
                    SkillStep.Verify("{{店名}}"),
                    SkillStep.Tap(NodeSelector(text = "去结算")),
                    SkillStep.Tap(NodeSelector(text = "提交订单")),
                ),
        )

    private fun orderScreens(shop: String): (FakeDevice) -> MultiWindowResult =
        { d ->
            when {
                d.actions.none { it.startsWith("launch") } -> {
                    screen(window("launcher", Node(text = "美团")))
                }

                d.actions.none { it.startsWith("click") } -> {
                    screen(
                        window(
                            app,
                            Node(id = "search", editable = true),
                            Node(id = "card", clickable = true, children = listOf(Node(text = "$shop(高新店) 4.8分"))),
                        ),
                    )
                }

                else -> {
                    screen(
                        window(
                            app,
                            Node(text = "$shop(高新店)"),
                            Node(id = "pay", text = "去结算", clickable = true),
                            Node(id = "submit", text = "提交订单", clickable = true),
                        ),
                    )
                }
            }
        }

    @Test
    fun `replays with a parameter and stops before the order is submitted`() =
        runTest {
            val device = FakeDevice(screenFor = orderScreens("瑞幸"))
            val result = runner(device).run(orderSkill, mapOf("店名" to "瑞幸"))
            assertEquals(SkillRunStatus.HANDED_OVER, result.status)
            assertEquals(6, result.stoppedAt)
            assertEquals(listOf("launch:$app", "type:search=瑞幸", "click:card", "click:pay"), device.actions)
            assertTrue(result.message.contains("提交订单"))
        }

    @Test
    fun `a failed checkpoint stops the run before anything is ordered`() =
        runTest {
            // The app opened another shop: the page never shows the requested name, OCR agrees.
            val device =
                FakeDevice(ocrText = "瑞幸咖啡") { d ->
                    if (d.actions.none { it.startsWith("launch") }) {
                        screen()
                    } else {
                        screen(
                            window(
                                app,
                                Node(id = "card", text = "蜜雪冰城 甜品", clickable = true),
                                Node(text = "去结算", clickable = true),
                            ),
                        )
                    }
                }
            val skill =
                Skill(
                    name = "x",
                    steps =
                        listOf(
                            SkillStep.Launch(app),
                            SkillStep.Tap(NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS)),
                            SkillStep.Verify("霸王茶姬"),
                            SkillStep.Tap(NodeSelector(text = "去结算")),
                        ),
                )
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.FAILED, result.status)
            assertEquals(3, result.stoppedAt)
            assertTrue("ocr" in device.actions)
            assertEquals(1, device.actions.count { it.startsWith("click") })
            assertEquals(2, result.completedSteps)
        }

    @Test
    fun `a checkpoint passes on OCR when the accessibility tree has no text`() =
        runTest {
            val device = FakeDevice(ocrText = "蜜雪 冰城 高新店") { screen(window(app, Node(desc = null))) }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Verify("蜜雪冰城")))
            assertEquals(SkillRunStatus.DONE, runner(device).run(skill, emptyMap()).status)
        }

    @Test
    fun `a control below the fold is found by scrolling down`() =
        runTest {
            val device =
                FakeDevice { d ->
                    if (d.scrolls >=
                        2
                    ) {
                        screen(window(app, Node(id = "far", text = "蜜雪冰城", clickable = true)))
                    } else {
                        screen(window(app, Node(text = "别家")))
                    }
                }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Tap(NodeSelector(text = "蜜雪冰城"))))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.DONE, result.status)
            assertEquals(listOf("scroll", "scroll", "click:far"), device.actions)
        }

    @Test
    fun `a tap that dismisses a passing popup is made when the popup is there and skipped when it is not`() =
        runTest {
            val steps =
                listOf(
                    SkillStep.Tap(NodeSelector(text = "暂不升级"), scroll = false, optional = true),
                    SkillStep.Tap(NodeSelector(text = "拼好饭")),
                )
            val without = FakeDevice { screen(window(app, Node(id = "p", text = "拼好饭", clickable = true))) }
            val skipped = runner(without).run(Skill(name = "x", steps = steps), emptyMap())
            assertEquals(SkillRunStatus.DONE, skipped.status)
            assertEquals(listOf("click:p"), without.actions)
            assertEquals(0, without.scrolls)
            val popup = Node(id = "later", text = "暂不升级", clickable = true)
            val entry = Node(id = "p", text = "拼好饭", clickable = true, top = 600)
            val with = FakeDevice { screen(window(app, popup, entry)) }
            assertEquals(SkillRunStatus.DONE, runner(with).run(Skill(name = "x", steps = steps), emptyMap()).status)
            assertEquals(listOf("click:later", "click:p"), with.actions)
        }

    @Test
    fun `a control that is never found fails after the scroll budget`() =
        runTest {
            val device = FakeDevice { screen(window(app, Node(text = "别家"))) }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Tap(NodeSelector(text = "蜜雪冰城"))))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.FAILED, result.status)
            assertEquals(SkillTiming().maxScrolls, device.scrolls)
        }

    @Test
    fun `a refused accessibility click falls back to a real tap`() =
        runTest {
            val device =
                FakeDevice(clickWorks = false) {
                    screen(window(app, Node(id = "b", text = "加入购物车", clickable = true)))
                }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Tap(NodeSelector(text = "加入购物车"), scroll = false)))
            assertEquals(SkillRunStatus.DONE, runner(device).run(skill, emptyMap()).status)
            assertEquals(listOf("click:b", "tap:250,${100 + 40}"), device.actions)
        }

    @Test
    fun `a hand-over step stops with its reason`() =
        runTest {
            val device = FakeDevice { screen() }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Back, SkillStep.HandOver("请登录"), SkillStep.Back))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.HANDED_OVER, result.status)
            assertEquals("请登录", result.message)
            assertEquals(listOf("back"), device.actions)
        }

    @Test
    fun `a checkpoint after a tap looks at the new screen, not the one tapped`() =
        runTest {
            var readsAfterClick = 0
            val list = window(app, Node(id = "card", text = "蜜雪冰城 甜品", clickable = true))
            val otherShop = window(app, Node(text = "瑞幸咖啡"))
            val device =
                FakeDevice { d ->
                    when {
                        d.actions.isEmpty() -> screen(list)

                        // The app takes a few reads to switch pages after the tap.
                        readsAfterClick++ < 3 -> screen(list)

                        else -> screen(otherShop)
                    }
                }
            val skill =
                Skill(
                    name = "x",
                    steps =
                        listOf(
                            SkillStep.Tap(NodeSelector(text = "蜜雪冰城", match = TextMatch.CONTAINS)),
                            SkillStep.Verify("蜜雪冰城", ocr = false),
                        ),
                )
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.FAILED, result.status)
            assertEquals(2, result.stoppedAt)
        }

    @Test
    fun `a captcha stops the replay for the user without scrolling across it`() =
        runTest {
            val slider = window(app, Node(text = "请向右滑动滑块验证 150a2777"))
            val device = FakeDevice { screen(slider) }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Tap(NodeSelector(text = "搜索"))))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.HANDED_OVER, result.status)
            assertEquals(ScreenBlockers.CAPTCHA_REASON, result.message)
            assertEquals(0, device.scrolls)
        }

    @Test
    fun `a login page met while checking hands over with the login reason`() =
        runTest {
            val login = window(app, Node(text = "欢迎登录美团"), Node(text = "获取短信验证码"))
            val device = FakeDevice { screen(login) }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Verify("蜜雪冰城", ocr = false)))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.HANDED_OVER, result.status)
            assertEquals(ScreenBlockers.LOGIN_REASON, result.message)
        }

    @Test
    fun `an OCR tap hits the text's position even though the tree lacks it`() =
        runTest {
            var tapped = false
            val device =
                FakeDevice { d ->
                    tapped = d.actions.any { it.startsWith("tap:") }
                    if (tapped) screen(window(app, Node(text = "1km"))) else screen(window(app, Node(text = "附近")))
                }
            device.ocrHits = mapOf("1km" to OcrHit(708f, 656f, "1km"))
            val skill = Skill(name = "x", steps = listOf(SkillStep.Tap(NodeSelector(text = "1km"), ocr = true)))
            assertEquals(SkillRunStatus.DONE, runner(device).run(skill, emptyMap()).status)
            assertEquals(listOf("locate:1km", "tap:708,656"), device.actions)
        }

    @Test
    fun `an OCR tap on a payment text hands over and an unreadable one fails`() =
        runTest {
            val device = FakeDevice { screen(window(app, Node(text = "x"))) }
            device.ocrHits = mapOf("提交" to OcrHit(1f, 1f, "提交订单"))
            val pay =
                runner(
                    device,
                ).runSteps(SkillStep.Tap(NodeSelector(text = "提交"), ocr = true))
            assertEquals(SkillRunStatus.HANDED_OVER, pay.status)
            val missing =
                runner(
                    device,
                ).runSteps(SkillStep.Tap(NodeSelector(text = "1km"), ocr = true))
            assertEquals(SkillRunStatus.FAILED, missing.status)
            assertTrue(device.actions.none { it.startsWith("tap:") })
        }

    private fun listScreen(vararg shops: String) =
        screen(
            window(
                app,
                Node(
                    top = 0,
                    height = SkillTestTrees.SCREEN_HEIGHT,
                    children =
                        shops.mapIndexed { i, shop ->
                            Node(
                                top = i * 400,
                                height = 300,
                                children =
                                    listOf(
                                        Node(
                                            text = shop,
                                            top =
                                                i * 400,
                                        ),
                                        Node(text = "¥", top = i * 400 + 50),
                                        Node(text = "9", top = i * 400 + 50),
                                    ),
                            )
                        },
                ),
            ),
        )

    @Test
    fun `collect scrolls to the end text and keeps every entry once`() =
        runTest {
            val pages =
                listOf(listScreen("A", "B", "C"), listScreen("C", "D", "E"), listScreen("E", "F", "没有更多了"))
            val device = FakeDevice { d -> pages[minOf(d.scrolls, pages.size - 1)] }
            val result = runner(device).runSteps(SkillStep.Collect(pauseMs = 500))
            assertEquals(SkillRunStatus.DONE, result.status)
            assertEquals(
                listOf("A ¥9", "B ¥9", "C ¥9", "D ¥9", "E ¥9", "F ¥9", "没有更多了 ¥9"),
                result.collected.map { it.line },
            )
            assertEquals(2, device.scrolls)
        }

    @Test
    fun `collect stops when scrolling brings nothing new, and at the item cap`() =
        runTest {
            val device = FakeDevice { listScreen("A", "B") }
            val stale = runner(device).runSteps(SkillStep.Collect(pauseMs = 500))
            assertEquals(2, stale.collected.size)
            // One screen with news, then three in a row with nothing new.
            assertEquals(3, device.scrolls)
            val capped =
                runner(
                    FakeDevice {
                        listScreen("A", "B", "C")
                    },
                ).run(Skill(name = "x", steps = listOf(SkillStep.Collect(maxItems = 2, pauseMs = 500))), emptyMap())
            assertEquals(listOf("A ¥9", "B ¥9"), capped.collected.map { it.line })
        }

    @Test
    fun `a captcha in the middle of collecting keeps what was read`() =
        runTest {
            val slider = screen(window(app, Node(text = "请向右滑动滑块验证")))
            val device = FakeDevice { d -> if (d.scrolls == 0) listScreen("A", "B") else slider }
            val result = runner(device).runSteps(SkillStep.Collect(pauseMs = 500))
            assertEquals(SkillRunStatus.HANDED_OVER, result.status)
            assertEquals(2, result.collected.size)
        }

    @Test
    fun `a missing parameter fails before touching the phone`() =
        runTest {
            val device = FakeDevice { screen() }
            val skill = orderSkill.copy(params = listOf(SkillParam("店名", "")))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.FAILED, result.status)
            assertTrue(device.actions.isEmpty())
        }

    @Test
    fun `accessibility turned off is reported as a failure, not a crash`() =
        runTest {
            val device = FakeDevice { throw IllegalStateException("无障碍服务没有开启") }
            val skill = Skill(name = "x", steps = listOf(SkillStep.Verify("a")))
            val result = runner(device).run(skill, emptyMap())
            assertEquals(SkillRunStatus.FAILED, result.status)
            assertEquals("无障碍服务没有开启", result.message)
        }

    @Test
    fun `an app that never comes up fails the launch step`() =
        runTest {
            val device = FakeDevice { screen(window("launcher", Node(text = "桌面"))) }
            val result = runner(device).run(Skill(name = "x", steps = listOf(SkillStep.Launch(app))), emptyMap())
            assertEquals(SkillRunStatus.FAILED, result.status)
            assertEquals(1, result.stoppedAt)
        }
}
