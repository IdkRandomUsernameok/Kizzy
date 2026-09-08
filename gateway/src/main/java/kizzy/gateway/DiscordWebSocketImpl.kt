package kizzy.gateway

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.interfaces.NoOpLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kizzy.gateway.entities.Heartbeat
import kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import kizzy.gateway.entities.OutgoingPayload
import kizzy.gateway.entities.Payload
import kizzy.gateway.entities.PayloadData
import kizzy.gateway.entities.Ready
import kizzy.gateway.entities.Resume
import kizzy.gateway.entities.op.OpCode
import kizzy.gateway.entities.op.OpCode.*
import kizzy.gateway.entities.presence.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger,
    // Invoked when Discord rejects the token (gateway close 4004), so the host can
    // clear the stored token and surface a re-login prompt instead of the service
    // silently "running" while Discord ignores every presence update.
    private val onAuthenticationFailed: () -> Unit = {},
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var selfUserId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var connectionJob: Job? = null
    private var connected = false
    private var explicitlyClosed = false
    private var awaitingHeartbeatAck = false
    private var reconnectAttempts = 0
    private var lastPresence: Presence? = null
    private val sendLock = Mutex()
    private val client: HttpClient = HttpClient(CIO) {
        install(WebSockets)
        // CIO's default requestTimeout is 15s and is applied to the whole request —
        // including a WebSocket session. A large account's READY payload (guild list,
        // presences, …) can take longer than that to fully arrive on a mobile
        // connection, and the engine then kills the connection with an abrupt 1006
        // right as it is waiting on READY — on every fresh IDENTIFY. The session never
        // becomes usable, so no presence is ever sent: the RPC looks "connected" from
        // the app's side but never shows up on Discord. A long-lived gateway session
        // has no natural request boundary to time out against, so disable the timeout.
        engine { requestTimeout = 0 }
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val supervisor = SupervisorJob()

    override val coroutineContext: CoroutineContext = supervisor + Dispatchers.IO

    override suspend fun connect() {
        if (connectionJob?.isActive == true) return
        explicitlyClosed = false
        connectionJob = launch { runConnectionLoop() }
    }

    private suspend fun runConnectionLoop() {
        while (isActive && !explicitlyClosed) {
            val url = resumeGatewayUrl ?: gatewayUrl
            var closeCode: Int? = null
            try {
                logger.i("Gateway", "Opening connection to $url")
                client.webSocket(url) {
                    websocket = this
                    incoming.receiveAsFlow().collect { frame ->
                        if (frame is Frame.Text) onMessage(frame.readText())
                    }
                    closeCode = closeReason.await()?.code?.toInt()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Gateway", e.message ?: e.toString())
            }
            heartbeatJob?.cancel()
            heartbeatJob = null
            connected = false
            websocket = null
            if (explicitlyClosed || !isActive) break
            handleDisconnect(closeCode)
        }
    }

    private suspend fun handleDisconnect(code: Int?) {
        logger.w("Gateway", "Disconnected with code: $code")
        if (code != null && code in NON_RESUMABLE_CODES) {
            logger.e("Gateway", "Session is not resumable, dropping session state")
            sessionId = null
            sequence = 0
            resumeGatewayUrl = null
        }
        if (code != null && code in FATAL_CODES) {
            logger.e("Gateway", "Gateway rejected this connection with fatal code $code, giving up")
            if (code == 4004) {
                logger.e("Gateway", "Discord rejected the login token - please log in again")
                runCatching { onAuthenticationFailed() }
            }
            explicitlyClosed = true
            return
        }
        // After a few failed attempts in a row, a stale session can never be resumed —
        // fall back to a fresh IDENTIFY instead of looping on RESUME forever.
        if (reconnectAttempts + 1 >= MAX_RECONNECT_ATTEMPTS_BEFORE_FRESH_IDENTIFY &&
            (sessionId != null || resumeGatewayUrl != null)
        ) {
            logger.e("Gateway", "$reconnectAttempts failed reconnects, dropping session state for a fresh IDENTIFY")
            sessionId = null
            sequence = 0
            resumeGatewayUrl = null
        }
        val delayMillis = backoffDelay()
        logger.i("Gateway", "Reconnecting in ${delayMillis}ms")
        delay(delayMillis)
    }

    private fun backoffDelay(): Long {
        reconnectAttempts++
        val base = min(MAX_BACKOFF_MILLIS, 1000L shl min(reconnectAttempts, 5))
        return base + Random.nextLong(250, 1250)
    }

    private suspend fun onMessage(jsonString: String) {
        val payload = runCatching { json.decodeFromString<Payload>(jsonString) }.getOrNull() ?: return
        logger.d("Gateway", "Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let { sequence = it }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch(jsonString)
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> handleHello(jsonString)
            HEARTBEAT_ACK -> awaitingHeartbeatAck = false
            else -> {}
        }
    }

    open fun Payload.handleDispatch(jsonString: String) {
        when (this.t.toString()) {
            "READY" -> {
                val ready = decodePayloadData<Ready>(jsonString) ?: return
                sessionId = ready.sessionId
                selfUserId = ready.user?.id
                resumeGatewayUrl = ready.resumeGatewayUrl?.let { "$it/?v=10&encoding=json" }
                logger.i("Gateway", "resume_gateway_url updated to $resumeGatewayUrl")
                logger.i("Gateway", "session_id updated to $sessionId")
                ready.sessions?.let { sessions ->
                    logger.i(
                        "Gateway",
                        "Account sessions per Discord: " + sessions.joinToString("; ") { s ->
                            "status=${s.status}, active=${s.active}, client=${s.clientInfo?.client}"
                        }
                    )
                }
                connected = true
                // Only a confirmed session counts as a successful connection — resetting
                // here (not when the socket merely opens) lets handleDisconnect's
                // "3 failed attempts -> fresh IDENTIFY" logic actually trigger when a
                // session keeps failing after the handshake.
                reconnectAttempts = 0
                replayPresence()
            }

            "PRESENCE_UPDATE" -> {
                // Discord echoes the account's own presence back when an update
                // registers; logging it makes acceptance vs silent-drop visible
                // in the in-app Logs screen.
                runCatching {
                    val d = json.parseToJsonElement(jsonString)
                        .jsonObject["d"]?.jsonObject ?: return@runCatching
                    val userId = d["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    if (userId != null && userId == selfUserId) {
                        val status = d["status"]?.jsonPrimitive?.contentOrNull
                        val activities = d["activities"]?.jsonArray
                            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                        logger.i("Gateway", "Discord confirmed our presence: status=$status, activities=$activities")
                    }
                }
            }

            "RESUMED" -> {
                logger.i("Gateway", "Session Resumed")
                connected = true
                reconnectAttempts = 0
                replayPresence()
            }

            else -> {}
        }
    }

    private fun replayPresence() {
        val presence = lastPresence ?: return
        launch {
            delay(500.milliseconds)
            dispatchPresence(presence)
        }
    }

    private suspend fun handleInvalidSession() {
        logger.i("Gateway", "Handling Invalid Session")
        sessionId = null
        sequence = 0
        resumeGatewayUrl = null
        delay(Random.nextLong(1000, 5000))
        sendIdentify()
    }

    private suspend fun handleHello(jsonString: String) {
        heartbeatInterval = decodePayloadData<Heartbeat>(jsonString)?.heartbeatInterval ?: return
        logger.i("Gateway", "Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
        if (sequence > 0 && !sessionId.isNullOrBlank()) sendResume() else sendIdentify()
    }

    protected fun decodeReady(jsonString: String): Ready? {
        return decodePayloadData(jsonString)
    }

    private inline fun <reified T> decodePayloadData(jsonString: String): T? {
        return runCatching { json.decodeFromString<PayloadData<T>>(jsonString).d }.getOrNull()
    }

    private suspend fun sendHeartBeat() {
        logger.i("Gateway", "Sending $HEARTBEAT with seq: $sequence")
        awaitingHeartbeatAck = true
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) null else sequence,
        )
    }

    private suspend fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway", "Sending $IDENTIFY (presence included: ${lastPresence != null})")
        // The official client carries its current presence in IDENTIFY; for user
        // accounts this is the payload that is reliably respected, unlike a bare
        // op-3 Status Update sent after READY.
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload(presence = lastPresence)
        )
    }

    private suspend fun sendResume() {
        logger.i("Gateway", "Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        awaitingHeartbeatAck = false
        heartbeatJob = launch {
            delay((interval * Random.nextDouble(0.1, 0.9)).toLong())
            while (isActive) {
                if (awaitingHeartbeatAck) {
                    logger.w("Gateway", "Heartbeat was never acknowledged, restarting connection")
                    reconnectWebSocket()
                    return@launch
                }
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return connected && websocket?.isActive == true
    }

    override fun isWebSocketConnected(): Boolean {
        return isSocketConnectedToAccount()
    }

    override fun currentSessionId(): String? = sessionId

    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        val socket = websocket ?: return
        if (!socket.isActive) return
        val payload = json.encodeToString(OutgoingPayload(op = op, d = d))
        sendLock.withLock {
            runCatching { socket.send(Frame.Text(payload)) }
                .onFailure { logger.e("Gateway", "Failed to send $op: ${it.message}") }
        }
    }

    override fun close() {
        explicitlyClosed = true
        connected = false
        lastPresence = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        resumeGatewayUrl = null
        sessionId = null
        sequence = 0
        val socket = websocket
        websocket = null
        val job = connectionJob
        connectionJob = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { socket?.close(CloseReason(CloseReason.Codes.NORMAL, "Kizzy stopped")) }
            job?.cancel()
            supervisor.cancelChildren()
            logger.i("Gateway", "Connection to gateway closed")
        }
    }

    override suspend fun sendActivity(presence: Presence) {
        lastPresence = presence
        val ready = withTimeoutOrNull(CONNECT_TIMEOUT) {
            while (!isSocketConnectedToAccount()) {
                delay(50.milliseconds)
            }
            true
        }
        if (ready != true) {
            logger.w("Gateway", "Timed out waiting for a ready session, presence will replay on connect")
            return
        }
        dispatchPresence(presence)
    }

    private suspend fun dispatchPresence(presence: Presence) {
        logger.i("Gateway", "Sending $PRESENCE_UPDATE: ${json.encodeToString(presence)}")
        send(
            op = PRESENCE_UPDATE,
            d = presence
        )
    }

    private companion object {
        val NON_RESUMABLE_CODES = setOf(4003, 4004, 4007, 4009, 4010, 4011, 4012, 4013, 4014)
        val FATAL_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)
        const val MAX_BACKOFF_MILLIS = 60_000L
        const val MAX_RECONNECT_ATTEMPTS_BEFORE_FRESH_IDENTIFY = 3
        val CONNECT_TIMEOUT = 30.seconds
    }
}
