package com.qingyu.app.data.repo

import com.qingyu.app.core.memory.RecallDecision
import com.qingyu.app.core.memory.RefineReason
import com.qingyu.app.core.memory.RefineSource
import com.qingyu.app.core.memory.RerankOutcome
import com.qingyu.app.data.MemoryItem
import com.qingyu.app.remote.ChatTurn

/**
 * 一轮请求"真正发出去的东西"的留档，供上下文自检页原样展示喵。
 *
 * 为什么必须在生成时留档，而不是让自检页自己重算一遍喵：
 * 那一页的用途是回答"模型为什么这么答"，内容一旦不忠实就完全失去意义喵。
 * 此前的重算版至少漏了三样喵：
 * 1. 联网检索到的资料（[searchHitCount喵]）；
 * 2. 多模态图片（挂在 [ChatTurn.images] 上，`preview` 也不打印喵）；
 * 3. 经小模型润色后的系统提示词（[refineSource] / [refineReason喵]）。
 * 记忆更麻烦——重算不推进轮次、也不回写冷却，选中的可能是压根另一批喵。
 *
 * [turns] 是最终形态：润色结果已经替换进 system 那一条、图片已挂好、联网资料已拼进去喵，
 * 直接就是 [com.qingyu.app.remote.ChatPayload.build] 的输入，所以它就是"最终提示词"本身喵。
 */
data class ContextSnapshot(
    /** 最终发送的全部消息喵。 */
    val turns: List<ChatTurn>,
    /** 本轮真正注入系统提示词的记忆，人设在前喵。 */
    val memories: List<MemoryItem>,
    /** 全部候选项的判定明细（含被屏蔽的原因），自检页据此回答"为什么没想起来"喵。 */
    val decisions: List<RecallDecision>,
    /** 注入的时空提示；空串表示这轮没有喵。 */
    val temporalHint: String = "",
    /** 本轮联网检索到的资料条数喵。 */
    val searchHitCount: Int = 0,
    /** 系统提示词来源：小模型润色 / 代码原生喵。 */
    val refineSource: RefineSource = RefineSource.NATIVE,
    /** 没走润色时的原因，例如超时熔断喵。 */
    val refineReason: RefineReason = RefineReason.DISABLED,
    /** 留档时刻，界面上用来标明"这是哪一轮"喵。 */
    val at: Long = 0L,
    /**
     * 本轮精排的结论喵。
     *
     * 用户报过「记忆注入太严苛，几乎没有记忆可以注入」喵。
     * 而"记忆是空的"有好几种完全不同的原因：没有相关候选、
     * 精排开关没开、精排超时、模型全判无关喵。
     * 不把这几种在自检页上分开，调参就只能靠猜喵。
     */
    val rerankOutcome: RerankOutcome = RerankOutcome.DISABLED,
    /** 靠模型判定**越过骰子**被救回来的条数喵。 */
    val rescuedCount: Int = 0,
) {
    /** 挂了图片的消息条数。图片本体是 base64，界面只报数量，不把它铺出来喵。 */
    val imageTurnCount: Int get() = turns.count { it.hasImages }
}
