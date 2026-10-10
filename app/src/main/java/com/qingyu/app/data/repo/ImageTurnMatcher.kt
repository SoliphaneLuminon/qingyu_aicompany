package com.qingyu.app.data.repo

import com.qingyu.app.remote.ChatTurn

/**
 * 把图片挂到正确的消息上喵。
 *
 * 单独抽出来是因为这里**挂错了不会报错**：图片发给了错误的一行喵，
 * 模型看到的图和用户的话对不上，表现只是"答非所问"喵。
 * 所以匹配规则要能被单元测试钉住喵。
 *
 * 规则：从后往前找**内容完全相同**的 user turn；找不到就退回最后一条 user turn喵；
 * 一条 user turn 都没有时原样返回（不改动，也不抛异常喵）。
 */
object ImageTurnMatcher {

    fun attach(turns: List<ChatTurn>, userText: String, imageDataUrls: List<String>): List<ChatTurn> {
        if (imageDataUrls.isEmpty()) return turns

        val target = userText.trim()
        val exact = turns.indexOfLast { it.role == "user" && it.content.trim() == target }
        val index = if (exact >= 0) exact else turns.indexOfLast { it.role == "user" }
        if (index < 0) return turns

        return turns.toMutableList().also { list ->
            list[index] = list[index].copy(images = imageDataUrls)
        }
    }
}
