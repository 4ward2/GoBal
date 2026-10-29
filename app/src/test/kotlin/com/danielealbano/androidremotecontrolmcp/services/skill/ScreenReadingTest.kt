package com.danielealbano.androidremotecontrolmcp.services.skill

import com.danielealbano.androidremotecontrolmcp.services.memory.OcrBox
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.Node
import com.danielealbano.androidremotecontrolmcp.services.skill.SkillTestTrees.window
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Reading the screen")
class ScreenReadingTest {
    @Nested
    @DisplayName("OcrMatch")
    inner class Ocr {
        @Test
        fun `a text inside one box is placed proportionally within it`() {
            // RapidOCR read the whole chip row as one run; "1km" is the 4th of 5 chips.
            val row = OcrBox("附近100m500m1km3km", 0, 600, 1500, 100)
            val hit = OcrMatch.find(listOf(row), "1km")!!
            assertEquals(650f, hit.y)
            // 16 characters; "1km" starts at index 10, so its centre is at 11.5 / 16 of the width.
            assertEquals(1500f * 11.5f / 16, hit.x, 0.5f)
        }

        @Test
        fun `the shortest box containing the text wins`() {
            val boxes = listOf(OcrBox("3km以内1km优先", 0, 0, 900, 50), OcrBox("1km", 700, 600, 100, 60))
            val hit = OcrMatch.find(boxes, "1 km")!!
            assertEquals(750f, hit.x)
            assertEquals(630f, hit.y)
        }

        @Test
        fun `single-character boxes on one line are joined`() {
            // Tesseract: one box per Chinese character.
            val chars = "附近商场".mapIndexed { i, c -> OcrBox(c.toString(), 100 + i * 60, 800 + i % 2, 50, 50) }
            val hit = OcrMatch.find(chars + OcrBox("别的", 0, 1200, 100, 50), "商场")!!
            assertEquals((220 + 330) / 2f, hit.x)
            assertEquals("商场", hit.read)
        }

        @Test
        fun `nothing read means no hit`() {
            assertNull(OcrMatch.find(listOf(OcrBox("100m", 0, 0, 10, 10)), "1km"))
            assertNull(OcrMatch.find(emptyList(), "1km"))
            assertNull(OcrMatch.find(listOf(OcrBox("1km", 0, 0, 10, 10)), " "))
        }
    }

    @Nested
    @DisplayName("ListItems")
    inner class Items {
        private fun card(
            top: Int,
            vararg texts: String,
        ) = Node(
            className = "android.view.ViewGroup",
            top = top,
            height = 300,
            children = texts.mapIndexed { i, t -> Node(text = t, top = top + i * 40, height = 40) },
        )

        private val carousel =
            Node(
                className = "android.widget.HorizontalScrollView",
                top = 0,
                height = 200,
                children =
                    (0 until 6).map { i ->
                        Node(
                            left = i * 90,
                            right = i * 90 + 80,
                            top = 0,
                            height = 200,
                            children = listOf(Node(text = "小卡$i", top = 10), Node(text = "¥$i", top = 60)),
                        )
                    },
            )

        @Test
        fun `the wide labelled siblings are the list, narrow carousel tiles are not`() {
            val list =
                Node(
                    className = "android.widget.ScrollView",
                    top = 200,
                    height = 1800,
                    children =
                        listOf(
                            card(200, "塔斯汀", "195m", "¥", "3", ".3"),
                            card(520, "库迪咖啡", "190m", "¥", "4", ".3"),
                        ),
                )
            val items = ListItems.extract(listOf(window("mt", carousel, list)))
            assertEquals(listOf("塔斯汀 195m ¥3.3", "库迪咖啡 190m ¥4.3"), items.map { it.line })
        }

        @Test
        fun `an entry cut by the screen edge is left for the next screen`() {
            val list =
                Node(
                    top = 0,
                    height = 2000,
                    children = listOf(card(-100, "半张", "卡片"), card(400, "完整", "卡片"), card(1900, "下面", "露头")),
                )
            assertEquals(listOf("完整 卡片"), ListItems.extract(listOf(window("mt", list))).map { it.line })
        }

        @Test
        fun `a label repeated as text and description is kept once`() {
            val repeated = Node(text = "蜜雪冰城(东区店)", desc = "蜜雪冰城(东区店)", top = 300)
            val list =
                Node(
                    top = 0,
                    height = 2000,
                    children =
                        listOf(
                            card(300, "a", "b").copy(children = listOf(repeated, Node(text = "月售600+", top = 340))),
                            card(700, "c", "d"),
                        ),
                )
            assertEquals("蜜雪冰城(东区店) 月售600+", ListItems.extract(listOf(window("mt", list))).first().line)
        }

        @Test
        fun `no list on screen gives nothing`() {
            assertEquals(emptyList<CollectedItem>(), ListItems.extract(listOf(window("mt", Node(text = "只有一行")))))
            assertEquals(emptyList<CollectedItem>(), ListItems.extract(emptyList()))
        }
    }
}
