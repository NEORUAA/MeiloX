package com.ljyh.mei.ui.model

import androidx.annotation.StringRes
import com.ljyh.mei.R

enum class PlayerAction(
    val id: String,
    @StringRes val labelRes: Int,
    val systemName: String,
) {
    PLAY_MODE("mode", R.string.player_action_play_mode, "repeat"),
    QUEUE("queue", R.string.player_action_queue, "list.bullet"),
    LYRICS("lyrics", R.string.player_action_lyrics, "quote.bubble"),
    SLEEP_TIMER("sleep", R.string.more_action_sleep_timer, "moon"),
    ADD_TO_PLAYLIST("add", R.string.more_action_add_playlist, "text.badge.plus"),
    DOWNLOAD("download", R.string.more_action_download, "arrow.down.circle"),
    MORE("more", R.string.more_actions_title, "ellipsis"),
    ;

    companion object {
        fun fromSettings(settingString: String): List<PlayerAction> {
            if (settingString.isBlank()) return defaultActions
            return settingString.split(',').mapNotNull { id -> entries.find { it.id == id } }
        }

        fun toSettings(actions: List<PlayerAction>): String = actions.joinToString(",") { it.id }

        val defaultActions = listOf(LYRICS, PLAY_MODE, QUEUE)
    }
}
