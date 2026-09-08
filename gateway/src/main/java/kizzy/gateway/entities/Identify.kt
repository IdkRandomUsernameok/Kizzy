package kizzy.gateway.entities
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kizzy.gateway.entities.presence.Presence

/**
 * IDENTIFY payload for a user-account gateway session.
 *
 * The field set mirrors what the official client sends (as observed in 2026) and
 * what maintained user-account libraries send:
 *
 *  - `capabilities` is a bitfield declaring which gateway features this client
 *    supports. Discord treats sessions with ancient capability sets as ancient
 *    clients; the 2022-era value 65 (bits 0|6) made the session look like a
 *    client from years ago. 22541 is the current set used by maintained
 *    user-account clients (bits 0,2,3,4,5,6,7,8,9,10,12,14).
 *  - `client_state` (with client_state_v2, bit 10) is sent by every real client
 *    as `{guild_versions: {}}`.
 *  - `presence`: the official client includes its current presence in IDENTIFY —
 *    for user accounts this is the payload that is actually respected; a bare
 *    op-3 Status Update after READY is "only sometimes respected" for user
 *    sessions. Sending the activity here makes the session start with the RPC
 *    applied, and is what the official client does to keep RPC across
 *    re-identifies.
 */
@Serializable
data class Identify(
    @SerialName("capabilities")
    val capabilities: Int,
    @SerialName("compress")
    val compress: Boolean,
    @SerialName("largeThreshold")
    val largeThreshold: Int,
    @SerialName("properties")
    val properties: Properties,
    @SerialName("token")
    val token: String,
    @SerialName("presence")
    @EncodeDefault(NEVER)
    val presence: Presence? = null,
    @SerialName("client_state")
    val clientState: ClientState = ClientState()
){
    companion object {
        const val CAPABILITIES = 22541

        fun String.toIdentifyPayload(presence: Presence? = null) = Identify(
            capabilities = CAPABILITIES,
            compress = false,
            largeThreshold = 100,
            properties = Properties(
                browser = "Discord Client",
                device = "ktor",
                os = "Windows"
            ),
            token = this,
            presence = presence
        )
    }
}

@Serializable
data class Properties(
    @SerialName("browser")
    val browser: String,
    @SerialName("device")
    val device: String,
    @SerialName("os")
    val os: String
)

@Serializable
data class ClientState(
    @SerialName("guild_versions")
    val guildVersions: Map<String, String> = emptyMap()
)
