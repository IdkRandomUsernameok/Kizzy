package com.my.kizzy.data.remote

import com.my.kizzy.data.rpc.Constants
import com.my.kizzy.domain.model.Game
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DetectableGameResponse(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("icon_hash")
    val iconHash: String? = null,
    @SerialName("cover_image_hash")
    val coverImageHash: String? = null,
    @SerialName("aliases")
    val aliases: List<String> = emptyList(),
    @SerialName("themes")
    val themes: List<String> = emptyList(),
    @SerialName("executables")
    val executables: List<DetectableExecutable> = emptyList()
)

@Serializable
data class DetectableExecutable(
    @SerialName("name")
    val name: String? = null,
    @SerialName("os")
    val os: String? = null,
    @SerialName("is_launcher")
    val isLauncher: Boolean = false
)

fun DetectableGameResponse.toGame(): Game? {
    val artwork = coverImageHash?.let { "https://cdn.discordapp.com/app-assets/$id/store/$it.png" }
        ?: iconHash?.let { "https://cdn.discordapp.com/app-icons/$id/$it.png?size=512" }
        ?: return null
    return Game(
        platform = platformFor(this),
        small_image = platformIconFor(this),
        large_image = artwork,
        game_title = name,
        application_id = id,
        release_year = snowflakeYear(id)
    )
}

private fun platformFor(game: DetectableGameResponse): String {
    val executables = game.executables.mapNotNull { it.os }
    return when {
        executables.any { it == "win32" } -> Constants.PC
        executables.any { it == "darwin" } -> Constants.MAC
        executables.any { it == "linux" } -> Constants.LINUX
        else -> Constants.PC
    }
}

private fun platformIconFor(game: DetectableGameResponse): String {
    return when (platformFor(game)) {
        Constants.MAC -> Constants.MAC_LINK
        Constants.LINUX -> Constants.LINUX_LINK
        else -> Constants.PC_LINK
    }
}

private fun snowflakeYear(id: String): Int? {
    val snowflake = id.toLongOrNull() ?: return null
    val millis = (snowflake shr 22) + DISCORD_EPOCH
    return java.util.Calendar.getInstance().apply { timeInMillis = millis }
        .get(java.util.Calendar.YEAR)
}

private const val DISCORD_EPOCH = 1420070400000L
