package com.ljyh.mei.ui.model

import androidx.annotation.StringRes
import com.ljyh.mei.R

enum class MoreAction(
    val id: String,
    @StringRes val labelRes: Int,
    val systemName: String,
    val frequency: Int, // 使用频率，数字越大表示越常用 (0-10)
    val riskLevel: Int, // 风险等级，数字越大表示误触风险越高 (0-10)
) {
    // --- 歌曲操作 ---
    ADD_TO_PLAYLIST("add", R.string.more_action_add_playlist, "text.badge.plus", 8, 2),

    SHARE("share", R.string.more_action_share, "square.and.arrow.up", 5, 3),

    DOWNLOAD("download", R.string.more_action_download, "arrow.down.circle", 6, 4),

    DELETE("delete", R.string.more_action_delete, "trash", 1, 9),

    // --- 播放列表操作 ---
    VIEW_PLAYLIST("view_playlist", R.string.more_action_view_playlist, "list.bullet", 7, 1),

    // --- 高级功能 ---
    SLEEP_TIMER("sleep", R.string.more_action_sleep_timer, "moon", 3, 2),
    PICTURE_IN_PICTURE("pip", R.string.more_action_floating_lyrics, "pip", 4, 1),
    // 播放界面底部功能
    BOTTOM_ACTION("bottom_action", R.string.more_action_bottom_actions, "switch.2", 4, 4),

    // --- 信息 ---
    SONG_INFO("song_info", R.string.more_action_song_info, "info.circle", 4, 1),

    SONG_WIKI("song_wiki", R.string.song_wiki, "book.pages", 5, 1),

    // --- 评论 ---
    COMMENT("comment", R.string.more_action_comments, "bubble.left.and.bubble.right", 7, 1);

    companion object {
        // 简单工厂方法
        fun fromId(id: String?): MoreAction? {
            return entries.find { it.id == id }
        }
    }
}

enum class SortOrder {
    FREQUENCY, // 常用的靠前
    RISK, // 低风险的靠前
    CUSTOM,
}

internal fun resolveMoreActionOrder(order: SortOrder, savedIds: List<String>): List<MoreAction> = when (order) {
    SortOrder.FREQUENCY -> MoreAction.entries.sortedByDescending { it.frequency }
    SortOrder.RISK -> MoreAction.entries.sortedBy { it.riskLevel }
    SortOrder.CUSTOM -> {
        val known = savedIds.mapNotNull(MoreAction::fromId).distinct()
        known + MoreAction.entries.filterNot(known::contains)
    }
}
