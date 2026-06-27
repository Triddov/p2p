package com.p2p.domain.signaling

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.p2p.util.LogTags
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
                android.util.Log.i(LogTags.SIGNALING, "WebSocket connected")
                reconnectDelaySeconds = 1L
                scope.launch { _connectionState.emit(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, SignalMessage::class.java)
                    scope.launch { _signalFlow.emit(message) }
                } catch (e: Exception) {
                    android.util.Log.e(LogTags.SIGNALING, "Failed to parse signal: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.i(LogTags.SIGNALING, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
                scope.launch { _connectionState.emit(false) }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e(LogTags.SIGNALING, "WebSocket failed: ${t.message}")
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
            type = SignalType.OFFER,
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
            type = SignalType.ANSWER,
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
            type = SignalType.ICE_CANDIDATE,
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

enum class SignalType {
    @SerializedName("offer") OFFER,
    @SerializedName("answer") ANSWER,
    @SerializedName("ice_candidate") ICE_CANDIDATE,
    @SerializedName("error") ERROR
}

data class SignalMessage(
    val type: SignalType?, // null — если пришёл неизвестный тип
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