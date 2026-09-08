/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * Ready.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package kizzy.gateway.entities


import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Ready(
    @SerialName("resume_gateway_url")
    val resumeGatewayUrl: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("user")
    val user: ReadyUser? = null,
    // Discord's per-session presence model: every active session of the account
    // (desktop, phone, … and this one) is listed here with the status/activities
    // Discord currently attributes to it. Logged at connect time — it shows what
    // Discord actually thinks of this session.
    @SerialName("sessions")
    val sessions: List<ReadySession>? = null,
)

@Serializable
data class ReadySession(
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("active")
    val active: Boolean? = null,
    @SerialName("client_info")
    val clientInfo: ReadySessionClientInfo? = null,
)

@Serializable
data class ReadySessionClientInfo(
    @SerialName("version")
    val version: Int? = null,
    @SerialName("client")
    val client: String? = null,
)

@Serializable
data class ReadyUser(
    @SerialName("id")
    val id: String? = null,
    @SerialName("bio")
    val bio: String? = null,
    @SerialName("premium_type")
    val premiumType: Int? = null,
)
