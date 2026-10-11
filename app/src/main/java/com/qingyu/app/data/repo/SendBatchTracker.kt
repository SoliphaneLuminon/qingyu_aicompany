package com.qingyu.app.data.repo

/**
 * 发送合并的规则喵。
 *
 * 用户连发几条消息时，不该让模型对着第一条就抢答——那会让后面几条变成"打断"喵。
 * 规则是：从**第一条**未被处理的消息起算一个窗口，窗口内的所有消息算同一批，
 * 窗口关闭后一起交给模型；窗口关闭时若上一轮还在生成，就排队等它结束喵。
 *
 * 抽成纯状态机而不是散在 ViewModel 里的几个布尔量，是因为这套规则的分支很密喵：
 * 窗口开着时又发一条、窗口刚关就来了新消息、上一轮还没结束、上一轮刚结束……
 * 这些组合在真机上只能靠手速复现，写成纯函数才能一条条钉住喵。
 *
 * 注意「前一轮处理完」指的是**生成完毕**，不是打字机播完喵。
 * 打字机只是本地回放，让它挡住下一轮会让用户觉得"我发了半天它不理我"喵。
 *
 * 这个类**不管计时**：3 秒的 `delay` 由调用方（ViewModel）负责，
 * 这里只记"窗口开没开、欠了几批"。把计时也塞进来的话，
 * 这个纯函数就得依赖时钟，测试会变得又慢又不稳喵。
 */
class SendBatchTracker {

    /** 当前窗口是什么时候开的；null 表示没有开着的窗口喵。 */
    var windowOpenedAt: Long? = null
        private set

    /** 已经关闭、但还没轮到生成的批次数量喵。 */
    var readyBatches: Int = 0
        private set

    /**
     * 用户刚发出一条消息喵。
     *
     * @return true 表示需要**新开一个窗口**（调用方据此启动 3 秒计时）；
     *   false 表示窗口已经开着，这条消息直接并进当前批次喵
     */
    fun onSent(now: Long): Boolean {
        if (windowOpenedAt != null) return false
        windowOpenedAt = now
        return true
    }

    /** 窗口到点：这批封口，等引擎空出来喵。 */
    fun onWindowClosed() {
        // 窗口已经因为别的原因关掉时不要重复计数，否则会凭空多生成一轮喵。
        if (windowOpenedAt == null) return
        windowOpenedAt = null
        readyBatches++
    }

    /**
     * 引擎空出来了，问一句"现在该生成吗"喵。
     *
     * @return true 表示调用方应当立刻生成一轮（并已消耗掉一个批次）喵
     */
    fun tryTakeReady(): Boolean {
        if (readyBatches <= 0) return false
        readyBatches--
        return true
    }

    /** 还有没有"关着但没生成"的批次喵。 */
    val hasReady: Boolean get() = readyBatches > 0

    /** 有没有开着的窗口喵。 */
    val hasOpenWindow: Boolean get() = windowOpenedAt != null

    /** 换角色 / 关对话时清空：上一个角色的待办不该跟到下一个角色去喵。 */
    fun reset() {
        windowOpenedAt = null
        readyBatches = 0
    }

    companion object {
        /**
         * 合并窗口长度喵。
         *
         * 3 秒是取舍的结果：太短则连发时仍会被拆成两轮（用户还在打字），
         * 太长则单发一条时等得明显、像卡了喵。3 秒大约是一句话打完的自然停顿喵。
         */
        const val WINDOW_MS = 3_000L
    }
}
