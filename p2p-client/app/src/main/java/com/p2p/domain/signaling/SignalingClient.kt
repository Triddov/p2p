package com.p2p.domain.signaling

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class SignalingClient(
    private val serverUrl: String,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    private var currentUserId: String? = null
    private var currentJwt: String? = null
    private var isManualDisconnect = false
    private var reconnectDelaySeconds = 1L

    private val _signalFlow = MutableSharedFlow<SignalMessage>()
    val signalFlow: SharedFlow<SignalMessage> = _signalFlow

    private val _connectionState = MutableSharedFlow<Boolean>()
    val connectionState: SharedFlow<Boolean> = _connectionState

    /**
     * Подключается к Signaling серверу
     */
    fun connect(userId: String, jwt: String) {
        currentUserId = userId
        currentJwt = jwt
        isManualDisconnect = false
        reconnectDelaySeconds = 1L
        openSocket()
    }

    private fun openSocket() {
        val userId = currentUserId ?: return
        val jwt = currentJwt ?: return

        val request = Request.Builder()
            .url("$serverUrl/ws?user_id=$userId&token=$jwt")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelaySeconds = 1L
                scope.launch { _connectionState.emit(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, SignalMessage::class.java)
                    scope.launch { _signalFlow.emit(message) }
                } catch (e: Exception) {
                    println("Failed to parse signal: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                scope.launch { _connectionState.emit(false) }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("Signaling failed: ${t.message}")
                scope.launch { _connectionState.emit(false) }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isManualDisconnect) return
        val delayMs = reconnectDelaySeconds * 1_000L
        reconnectDelaySeconds = (reconnectDelaySeconds * 2).coerceAtMost(60L)
        scope.launch {
            delay(delayMs)
            if (!isManualDisconnect) openSocket()
        }
    }

    /**
     * Отправляет Offer собеседнику
     */
    fun sendOffer(
        toPeerId: String,
        fromPeerId: String,
        offer: SessionDescription,
        iceCandidates: List<IceCandidate>
    ) {
        val message = SignalMessage(
            type = "offer",
            to = toPeerId,
            from = fromPeerId,
            sdp = offer.description,
            iceCandidates = iceCandidates.map { IceCandidateDto.from(it) }
        )

        send(message)
    }

    /**
     * Отправляет Answer собеседнику
     */
    fun sendAnswer(
        toPeerId: String,
        fromPeerId: String,
        answer: SessionDescription,
        iceCandidates: List<IceCandidate>
    ) {
        val message = SignalMessage(
            type = "answer",
            to = toPeerId,
            from = fromPeerId,
            sdp = answer.description,
            iceCandidates = iceCandidates.map { IceCandidateDto.from(it) }
        )

        send(message)
    }

    /**
     * Отправляет ICE candidate
     */
    fun sendIceCandidate(toPeerId: String, fromPeerId: String, candidate: IceCandidate) {
        val message = SignalMessage(
            type = "ice_candidate",
            to = toPeerId,
            from = fromPeerId,
            iceCandidate = IceCandidateDto.from(candidate)
        )

        send(message)
    }

    private fun send(message: SignalMessage) {
        val json = gson.toJson(message)
        webSocket?.send(json)
    }

    fun disconnect() {
        isManualDisconnect = true
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }
}

data class SignalMessage(
    val type: String, // "offer", "answer", "ice_candidate", "error"
    val to: String? = null,
    val from: String? = null,
    val sdp: String? = null,
    val iceCandidate: IceCandidateDto? = null,
    val iceCandidates: List<IceCandidateDto>? = null,
    val error: String? = null
)

data class IceCandidateDto(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
) {
    companion object {
        fun from(iceCandidate: IceCandidate) = IceCandidateDto(
            sdpMid = iceCandidate.sdpMid,
            sdpMLineIndex = iceCandidate.sdpMLineIndex,
            candidate = iceCandidate.sdp
        )
    }

    fun toIceCandidate() = IceCandidate(sdpMid, sdpMLineIndex, candidate)
}