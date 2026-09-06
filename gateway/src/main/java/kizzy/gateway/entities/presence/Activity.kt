package kizzy.gateway.entities.presence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Activity(
    @SerialName("name")
    val name: String?,
    @SerialName("id")
    val id: String? = null,
    @SerialName("state")
    val state: String? = null,
    @SerialName("details")
    val details: String? = null,
    @SerialName("party")
    val party: Party? = null,
    @SerialName("type")
    val type: Int? = 0,
    @SerialName("platform")
    val platform: String? = null,
    @SerialName("timestamps")
    val timestamps: Timestamps? = null,
    @SerialName("assets")
    val assets: Assets? = null,
    @SerialName("buttons")
    val buttons: List<String?>? = null,
    @SerialName("metadata")
    val metadata: Metadata? = null,
    @SerialName("application_id")
    val applicationId: String? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("flags")
    val flags: Int? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("created_at")
    val createdAt: Long? = null,
    @SerialName("sync_id")
    val syncId: String? = null,
    @SerialName("supported_platforms")
    val supportedPlatforms: List<String>? = null
)
