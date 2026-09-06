package com.my.kizzy.data.get_current_data.media

import android.media.MediaMetadata
import android.media.session.MediaController
import javax.inject.Inject

class YoutubeThumbnailResolver @Inject constructor() {

    fun isYoutubePackage(packageName: String?): Boolean {
        return packageName != null && YOUTUBE_PACKAGES.any { packageName.startsWith(it) }
    }

    fun resolve(controller: MediaController?): String? {
        val metadata = controller?.metadata ?: return null
        val videoId = findVideoId(controller, metadata) ?: return null
        return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
    }

    fun fallbackFor(thumbnailUrl: String): String? {
        val videoId = VIDEO_ID_PATTERN.find(thumbnailUrl)?.groupValues?.getOrNull(1) ?: return null
        return when {
            thumbnailUrl.contains("maxresdefault") -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            thumbnailUrl.contains("hqdefault") -> "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
            else -> null
        }
    }

    private fun findVideoId(controller: MediaController, metadata: MediaMetadata): String? {
        val candidates = buildList {
            METADATA_URI_KEYS.forEach { key ->
                runCatching { metadata.getString(key) }.getOrNull()?.let { add(it) }
            }
            controller.extras?.let { extras ->
                extras.keySet().forEach { key ->
                    (extras.get(key) as? CharSequence)?.let { add(it.toString()) }
                }
            }
            metadata.keySet().forEach { key ->
                runCatching { metadata.getString(key) }.getOrNull()?.let { add(it) }
            }
        }
        return candidates.firstNotNullOfOrNull { extractVideoId(it) }
    }

    private fun extractVideoId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        VIDEO_ID_PATTERN.find(raw)?.let { return it.groupValues[1] }
        WATCH_PATTERN.find(raw)?.let { return it.groupValues[1] }
        SHORT_LINK_PATTERN.find(raw)?.let { return it.groupValues[1] }
        return if (raw.length == 11 && BARE_ID_PATTERN.matches(raw)) raw else null
    }

    private companion object {
        val YOUTUBE_PACKAGES = listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube",
            "app.revanced.android.youtube",
            "com.vanced.android.youtube",
            "org.schabi.newpipe",
            "com.google.android.apps.searchlite"
        )

        val METADATA_URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            MediaMetadata.METADATA_KEY_MEDIA_URI,
            MediaMetadata.METADATA_KEY_MEDIA_ID
        )

        val VIDEO_ID_PATTERN = Regex("""(?:i\d?\.ytimg\.com|img\.youtube\.com)/vi(?:_webp)?/([\w-]{11})""")
        val WATCH_PATTERN = Regex("""[?&]v=([\w-]{11})""")
        val SHORT_LINK_PATTERN = Regex("""youtu\.be/([\w-]{11})""")
        val BARE_ID_PATTERN = Regex("""[\w-]{11}""")
    }
}
