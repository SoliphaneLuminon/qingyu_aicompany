package com.qingyu.app.data.repo

/**
 * 回复前的"等一等"规则喵。
 *
 * 用户发完消息后，如果他**又点开了输入框**，说明多半还想补一句——
 * 这时立刻回复会把他正打的字打断在中间，看起来像"抢话"喵。
 *
 * 规则喵：
 * 1. **基础窗口必须等满**（与发送合并窗口一致）喵。这一段是为了把连发的几条
 *    合成一批，无论输入框开着还是关着都要等——否则关着键盘发一条就会立刻抢答喵
 * 2. 若在基础窗口内他又点开了输入框，就再等到**点开之后 [EXTENDED_WAIT_MS]** 喵；
 *    到点仍没有新消息就回复——**即使他还在打字也回**，不能无限期等下去喵
 * 3. 这期间他把输入框关掉了就立刻回：关掉＝放弃继续打，不该再让他干等喵
 *
 * 抽成纯函数是因为它同时受三个量影响（发送时刻、点开时刻、此刻开没开），
 * 少判一个条件就会变成"永远不回"或"根本不等"，在真机上极难复现喵。
 */
object ReplyGate {

    /** 发完消息后的基础等待喵，与发送合并窗口一致。 */
    const val BASE_WAIT_MS = 3_000L

    /** 点开输入框后再给多久；到点仍没有新消息就回复喵。 */
    const val EXTENDED_WAIT_MS = 5_000L

    /**
     * 现在可以回复了吗喵。
     *
     * @param now 此刻喵
     * @param sentAt 这一批第一条消息落库的时刻喵
     * @param inputOpenedAt 发完消息**之后**输入框被重新点开的时刻；
     *   一次都没重新点开就传 null喵
     * @param inputFocused 输入框此刻是否开着喵
     */
    fun isReady(
        now: Long,
        sentAt: Long,
        inputOpenedAt: Long?,
        inputFocused: Boolean,
    ): Boolean {
        // 1. 基础窗口永远要等满——这段时间是用来合并连发消息的喵。
        if (now < sentAt + BASE_WAIT_MS) return false

        // 2. 没重新点开过输入框：到点就回喵。
        val openedAt = inputOpenedAt ?: return true

        // 3. 点开过、但现在关着了：他放弃了，立刻回喵。
        if (!inputFocused) return true

        // 4. 点开过且还开着：等到"点开之后 5 秒"喵。
        //    到点就回，**不等他打完**——否则碰上一直在改字的人就永远不回了喵。
        return now >= openedAt + EXTENDED_WAIT_MS
    }

    /**
     * 还要等多少毫秒才能再问一次喵。
     *
     * 等待期间输入框可能被关掉（那时条件 3 会让 [isReady] 立刻为真），
     * 所以不能一口气睡到截止时刻，得**分段醒来重判**喵。
     */
    fun nextCheckDelayMs(
        now: Long,
        sentAt: Long,
        inputOpenedAt: Long?,
        inputFocused: Boolean,
    ): Long {
        // 关着的输入框只可能被"重新点开"改变，但那要先经过基础窗口，
        // 所以最多睡到基础窗口结束即可，不必频繁轮询喵。
        val probes = ArrayList<Long>(2)
        probes += sentAt + BASE_WAIT_MS
        if (inputFocused && inputOpenedAt != null) probes += inputOpenedAt + EXTENDED_WAIT_MS
        val next = probes.filter { it > now }.minOrNull() ?: return 0L
        return next - now
    }
}
