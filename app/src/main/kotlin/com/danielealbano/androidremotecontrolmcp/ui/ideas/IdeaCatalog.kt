package com.danielealbano.androidremotecontrolmcp.ui.ideas

/** A ready-to-send request. [packageName] is the app it needs, or null when it needs none. */
data class Idea(
    val text: String,
    val packageName: String? = null,
)

data class IdeaCategory(
    val title: String,
    val ideas: List<Idea>,
)

/**
 * Ready-made requests shown as suggestion chips on an empty chat. Requests that need an app are
 * only offered when that app is installed, so a tap never leads to a dead end.
 */
object IdeaCatalog {
    private const val MEITUAN = "com.sankuai.meituan"
    private const val TAOBAO = "com.taobao.taobao"
    private const val XIANYU = "com.taobao.idlefish"
    private const val KFC = "com.yek.android.kfc.activitys"
    private const val MCDONALDS = "com.mcdonalds.gma.cn"

    val categories: List<IdeaCategory> =
        listOf(
            IdeaCategory(
                title = "外卖美食",
                ideas =
                    listOf(
                        Idea("打开美团，帮我找附近评分高的外卖", MEITUAN),
                        Idea("打开美团，搜索附近的奶茶", MEITUAN),
                        Idea("打开美团，查看我的外卖订单", MEITUAN),
                    ),
            ),
            IdeaCategory(
                title = "快餐点单",
                ideas =
                    listOf(
                        Idea("打开肯德基，看看今天有什么优惠", KFC),
                        Idea("打开肯德基，帮我找最近的门店", KFC),
                        Idea("打开麦当劳，看看有什么套餐", MCDONALDS),
                    ),
            ),
            IdeaCategory(
                title = "网上购物",
                ideas =
                    listOf(
                        Idea("打开淘宝，搜索老人手机支架", TAOBAO),
                        Idea("打开淘宝，查看我的物流", TAOBAO),
                        Idea("打开淘宝购物车", TAOBAO),
                    ),
            ),
            IdeaCategory(
                title = "闲置二手",
                ideas =
                    listOf(
                        Idea("打开闲鱼，搜索二手轮椅", XIANYU),
                        Idea("打开闲鱼，看看我的消息", XIANYU),
                    ),
            ),
            IdeaCategory(
                title = "手机操作",
                ideas =
                    listOf(
                        Idea("打开相机，用后置摄像头拍张照"),
                        Idea("打开图库，看看最近的照片"),
                        Idea("打开设置，把屏幕亮度调高一点"),
                        Idea("打开设置里的 WLAN"),
                    ),
            ),
        )

    /** Categories reduced to ideas whose app is installed; categories left empty are dropped. */
    fun available(isInstalled: (String) -> Boolean): List<IdeaCategory> =
        categories
            .map { category -> category.copy(ideas = category.ideas.filter { it.isAvailable(isInstalled) }) }
            .filter { it.ideas.isNotEmpty() }

    /** First idea of each available category, for the empty-chat suggestion chips. */
    fun quickStarts(
        isInstalled: (String) -> Boolean,
        limit: Int = DEFAULT_QUICK_STARTS,
    ): List<Idea> = available(isInstalled).map { it.ideas.first() }.take(limit)

    private fun Idea.isAvailable(isInstalled: (String) -> Boolean): Boolean = packageName?.let(isInstalled) ?: true

    private const val DEFAULT_QUICK_STARTS = 4
}
