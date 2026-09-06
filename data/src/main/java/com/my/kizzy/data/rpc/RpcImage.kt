/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * RpcImage.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.data.rpc

import android.content.Context
import android.graphics.Bitmap
import com.my.kizzy.domain.repository.KizzyRepository
import com.my.kizzy.preference.Prefs
import com.my.kizzy.data.utils.getAppInfo
import com.my.kizzy.data.utils.toBitmap
import com.my.kizzy.data.utils.toFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class RpcImage {
    abstract suspend fun resolveImage(repository: KizzyRepository): String?

    protected fun cachedLink(key: String): String? {
        val cache: HashMap<String, String> = runCatching {
            Json.decodeFromString<HashMap<String, String>>(Prefs[Prefs.SAVED_LINKS, "{}"])
        }.getOrElse { hashMapOf() }
        return cache[key]
    }

    protected fun cacheLink(key: String, value: String) {
        val cache: HashMap<String, String> = runCatching {
            Json.decodeFromString<HashMap<String, String>>(Prefs[Prefs.SAVED_LINKS, "{}"])
        }.getOrElse { hashMapOf() }
        cache[key] = value
        Prefs[Prefs.SAVED_LINKS] = Json.encodeToString(cache)
    }

    class DiscordImage(val image: String) : RpcImage() {
        override suspend fun resolveImage(repository: KizzyRepository): String {
            return "mp:${image}"
        }
    }

    class ExternalImage(val image: String) : RpcImage() {
        override suspend fun resolveImage(repository: KizzyRepository): String? {
            cachedLink(image)?.let { return it }
            return repository.getImage(image)?.also { cacheLink(image, it) }
        }
    }

    class YoutubeThumbnail(
        val videoUrl: String,
        private val fallbacks: List<String> = emptyList(),
    ) : RpcImage() {
        override suspend fun resolveImage(repository: KizzyRepository): String? {
            val candidates = listOf(videoUrl) + fallbacks
            candidates.forEach { candidate ->
                cachedLink(candidate)?.let { return it }
            }
            candidates.forEach { candidate ->
                val resolved = repository.getImage(candidate)
                if (!resolved.isNullOrBlank()) {
                    cacheLink(candidate, resolved)
                    return resolved
                }
            }
            return null
        }
    }

    class ApplicationIcon(val packageName: String, private val context: Context) : RpcImage() {
        val data = Prefs[Prefs.SAVED_IMAGES, "{}"]
        private val savedImages: HashMap<String, String> = Json.decodeFromString(data)

        override suspend fun resolveImage(repository: KizzyRepository): String? {
            return if (savedImages.containsKey(packageName))
                savedImages[packageName]
            else
                retrieveImageFromApi(packageName, context, repository)
        }

        private suspend fun retrieveImageFromApi(
            packageName: String,
            context: Context,
            repository: KizzyRepository,
        ): String? {
            val applicationInfo = context.getAppInfo(packageName)
            val bitmap = applicationInfo.toBitmap(context)
            val response = repository.uploadImage(bitmap.toFile(context, "image"))
            response?.let {
                savedImages[packageName] = it
                Prefs[Prefs.SAVED_IMAGES] = Json.encodeToString(savedImages)
            }
            return response
        }
    }

    class BitmapImage(
        private val context: Context,
        val bitmap: Bitmap?,
        private val packageName: String,
        val title: String,
    ) : RpcImage() {
        override suspend fun resolveImage(repository: KizzyRepository): String? {
            val data = Prefs[Prefs.SAVED_ARTWORK, "{}"]
            val schema = "${this.packageName}:${this.title}"
            val savedImages = Json.decodeFromString<HashMap<String, String>>(data)
            return if (savedImages.containsKey(schema))
                savedImages[schema]
            else {
                val result = repository.uploadImage(bitmap.toFile(this.context, "art"))
                result?.let {
                    savedImages[schema] = it
                    Prefs[Prefs.SAVED_ARTWORK] = Json.encodeToString(savedImages)
                }
                result
            }
        }
    }
}
