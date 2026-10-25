package ptq.mpga.ptqbookpageview.widget

/**
 * 程序化翻页入口：音量键等外部操作通过它复用翻页器内部的翻页动画。
 *
 * 由 [PTQBookPageView] 在组合时绑定；组件正在截屏或播放动画时调用会被忽略。
 */
class PTQBookPageViewRequest {
    internal var action: ((Boolean) -> Unit)? = null
    internal var refreshAction: (() -> Unit)? = null

    /** 请求翻到下一页 */
    fun next() {
        action?.invoke(true)
    }

    /** 请求翻到上一页 */
    fun previous() {
        action?.invoke(false)
    }

    /** 重新截取当前页位图（重新分页后调用） */
    fun refresh() {
        refreshAction?.invoke()
    }
}
