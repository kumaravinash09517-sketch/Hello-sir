package com.example.webrtc

import android.net.Uri
import com.example.data.model.BLOCKED_MESSAGE
import com.example.data.model.SignalingEnvelope
import com.example.data.network.ApiService
import com.example.data.network.BackendJsonParser
import com.example.data.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.Collections

enum class SignalingTransportState {
    DISCONNECTED,
    CONNECTING,
    WEBSOCKET_CONNECTED,
    REST_BRIDGE_ACTIVE
}

/**
 * Dual-transport global internet signaling client via https://rendar2-o9gq.onrender.com.
 * Uses /api/webrtc/ice-servers, /api/calls/signal, /api/calls/poll, /api/calls/active,
 * and WSS WebSocket signaling simultaneously for reliable calling across Wi-Fi and mobile data.
 */
class SignalingClient(
    private val okHttpClient: OkHttpClient = RetrofitClient.okHttpClient,
    private val apiService: ApiService = RetrofitClient.apiService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val processedSignalIds = Collections.synchronizedSet(mutableSetOf<String>())

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pollJob: Job? = null
    private var currentUserId: String? = null
    private var isStopped = true

    @Volatile
    private var cachedIceServers: List<PeerConnection.IceServer> = emptyList()

    @Volatile
    private var highFrequencyCallMode: Boolean = false

    private val _transportState = MutableStateFlow(SignalingTransportState.DISCONNECTED)
    val transportState: StateFlow<SignalingTransportState> = _transportState.asStateFlow()

    private val _incomingSignals = MutableSharedFlow<SignalingEnvelope>(extraBufferCapacity = 128)
    val incomingSignals: SharedFlow<SignalingEnvelope> = _incomingSignals.asSharedFlow()

    private val _blockedSignals = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val blockedSignals: SharedFlow<String> = _blockedSignals.asSharedFlow()

    fun setActiveCallMode(active: Boolean) {
        val changed = highFrequencyCallMode != active
        highFrequencyCallMode = active
        if (changed && active && !isStopped) {
            currentUserId?.let { startCallPolling(it) }
        }
    }

    fun connect(userId: String) {
        if (currentUserId == userId && !isStopped && pollJob?.isActive == true) return
        currentUserId = userId
        isStopped = false
        openWebSocket(userId)
        startCallPolling(userId)
        scope.launch {
            fetchActiveCalls(userId)
            refreshIceServers()
        }
    }

    fun disconnect() {
        isStopped = true
        highFrequencyCallMode = false
        currentUserId = null
        reconnectJob?.cancel()
        reconnectJob = null
        pollJob?.cancel()
        pollJob = null
        webSocket?.close(1000, "Session ended")
        webSocket = null
        processedSignalIds.clear()
        _transportState.value = SignalingTransportState.DISCONNECTED
    }

    suspend fun fetchIceServers(fallback: List<PeerConnection.IceServer>): List<PeerConnection.IceServer> {
        val fetched = withTimeoutOrNull(4_000L) { refreshIceServers() }.orEmpty()
        val active = fetched.ifEmpty { cachedIceServers }
        return if (active.isNotEmpty()) {
            active + fallback
        } else {
            fallback
        }
    }

    private suspend fun refreshIceServers(): List<PeerConnection.IceServer> {
        return runCatching {
            val response = apiService.getIceServers()
            if (response.code() == 403) {
                _blockedSignals.tryEmit(BLOCKED_MESSAGE)
                return@runCatching emptyList()
            }
            if (!response.isSuccessful) return@runCatching emptyList()
            val body = response.body()?.string().orEmpty()
            val parsed = BackendJsonParser.parseIceServers(body)
            if (parsed.isNotEmpty()) {
                cachedIceServers = parsed
            }
            parsed
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchActiveCalls(userId: String) {
        runCatching {
            val response = apiService.getActiveCalls(userId)
            if (response.code() == 403) {
                _blockedSignals.tryEmit(BLOCKED_MESSAGE)
                return@runCatching
            }
            if (response.isSuccessful) {
                val raw = response.body()?.string().orEmpty()
                val signals = BackendJsonParser.parseCallSignals(raw)
                onRestSignalsReceived(signals)
            }
        }
    }

    private fun startCallPolling(userId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive && !isStopped && currentUserId == userId) {
                runCatching {
                    val response = apiService.pollCallSignals(userId)
                    if (response.code() == 403) {
                        _blockedSignals.tryEmit(BLOCKED_MESSAGE)
                    } else if (response.isSuccessful) {
                        if (_transportState.value == SignalingTransportState.CONNECTING) {
                            _transportState.value = SignalingTransportState.REST_BRIDGE_ACTIVE
                        }
                        val raw = response.body()?.string().orEmpty()
                        val signals = BackendJsonParser.parseCallSignals(raw)
                        onRestSignalsReceived(signals)
                    }
                }
                val activeModulo = if (highFrequencyCallMode) 4 else 3
                if (tick % activeModulo == 1) {
                    fetchActiveCalls(userId)
                }
                tick++
                delay(if (highFrequencyCallMode) 600L else 1_800L)
            }
        }
    }

    private fun openWebSocket(userId: String) {
        webSocket?.cancel()
        _transportState.value = SignalingTransportState.CONNECTING

        val url = "${RetrofitClient.WS_URL}?user_id=${Uri.encode(userId)}"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _transportState.value = SignalingTransportState.WEBSOCKET_CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val signals = BackendJsonParser.parseCallSignals(text)
                if (signals.isNotEmpty()) {
                    onRestSignalsReceived(signals)
                } else {
                    BackendJsonParser.tryParseSignalJson(text)?.let { env ->
                        val me = currentUserId
                        if (me != null && !env.sender.equals(me, ignoreCase = true) &&
                            (env.receiver.isBlank() || env.receiver.equals(me, ignoreCase = true))
                        ) {
                            dispatchIfNew(env)
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isStopped) {
                    _transportState.value = SignalingTransportState.REST_BRIDGE_ACTIVE
                    scheduleReconnect(userId)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isStopped) {
                    _transportState.value = SignalingTransportState.REST_BRIDGE_ACTIVE
                    scheduleReconnect(userId)
                }
            }
        })
    }

    private fun scheduleReconnect(userId: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5_000L)
            if (isActive && !isStopped && currentUserId == userId) {
                openWebSocket(userId)
            }
        }
    }

    fun onRestSignalsReceived(signals: List<SignalingEnvelope>) {
        val me = currentUserId ?: return
        for (env in signals) {
            val isForMe = env.receiver.isBlank() || env.receiver.equals(me, ignoreCase = true)
            val isNotMine = !env.sender.equals(me, ignoreCase = true)
            if (isForMe && isNotMine) {
                dispatchIfNew(env)
            }
        }
    }

    private fun dispatchIfNew(envelope: SignalingEnvelope) {
        val ageMs = if (envelope.timestampMs > 0L) {
            System.currentTimeMillis() - envelope.timestampMs
        } else {
            0L
        }
        if (envelope.type == "offer" && ageMs > 90_000L) {
            processedSignalIds.add(envelope.signalId)
            return
        }
        if (processedSignalIds.add(envelope.signalId)) {
            _incomingSignals.tryEmit(envelope)
        }
    }

    suspend fun sendSignal(envelope: SignalingEnvelope) {
        val encoded = BackendJsonParser.encodeSignal(envelope)
        val rawJsonPayload = encoded.removePrefix(BackendJsonParser.PREFIX_SIGNAL)

        // 1. Push via open WebSocket when available
        webSocket?.send(rawJsonPayload)

        // 2. Send directly to Render /api/calls/signal endpoint
        val signalBody = JSONObject().apply {
            put("signal_id", envelope.signalId)
            put("type", envelope.type)
            put("sender", envelope.sender)
            put("sender_id", envelope.sender)
            envelope.senderName?.let { put("sender_name", it) }
            put("receiver", envelope.receiver)
            put("receiver_id", envelope.receiver)
            envelope.sdp?.let { put("sdp", it) }
            put("is_video", envelope.isVideo)
            put("call_type", if (envelope.isVideo) "video" else "audio")
            envelope.candidate?.let { put("candidate", it) }
            envelope.sdpMid?.let { put("sdp_mid", it) }
            put("sdp_m_line_index", envelope.sdpMLineIndex)
            put("timestamp_ms", envelope.timestampMs)
        }.toString()

        var signalResp = runCatching {
            apiService.sendCallSignal(signalBody.toRequestBody(jsonMediaType))
        }.getOrNull()

        if (signalResp?.code() == 403) {
            _blockedSignals.tryEmit(BLOCKED_MESSAGE)
            return
        }

        if ((signalResp == null || !signalResp.isSuccessful) && envelope.type != "read_receipt") {
            delay(220L)
            signalResp = runCatching {
                apiService.sendCallSignal(signalBody.toRequestBody(jsonMediaType))
            }.getOrNull()
        }

        // Fallback/compat: also mirror via /api/messages if signal endpoint returned error
        if (signalResp == null || !signalResp.isSuccessful) {
            val msgFallback = JSONObject().apply {
                put("sender_id", envelope.sender)
                put("senderId", envelope.sender)
                put("sender", envelope.sender)
                put("receiver_id", envelope.receiver)
                put("receiverId", envelope.receiver)
                put("receiver", envelope.receiver)
                put("message", encoded)
                put("text", encoded)
                put("content", encoded)
                put("msg_type", "signal")
                put("type", "signal")
                put("timestamp", System.currentTimeMillis())
            }.toString()
            runCatching {
                apiService.sendMessage(msgFallback.toRequestBody(jsonMediaType))
            }
        }
    }
}
