package com.p2p.data.repository

import com.p2p.domain.signaling.SignalType
import com.p2p.domain.signaling.SignalingClient
import com.p2p.domain.webrtc.WebRTCManager
import com.p2p.util.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRTCRepository @Inject constructor(
    private val webRTCManager: WebRTCManager,
    private val signalingClient: SignalingClient,
    private val scope: CoroutineScope
) {

    val messageFlow: SharedFlow<Pair<String, String>> = webRTCManager.messageFlow
    val connectionStateFlow: SharedFlow<Pair<String, Boolean>> = webRTCManager.connectionStateFlow

    /**
     * Подключается к signaling серверу
     */
    fun connectSignaling(userId: String, jwt: String) {
        signalingClient.connect(userId, jwt)

        // обработка входящих сигналов
        scope.launch {
            signalingClient.signalFlow.collect { signal ->
                handleSignal(signal.from ?: return@collect, signal)
            }
        }
    }

    /**
     * Инициирует P2P соединение с собеседником
     */
    fun initiateConnection(myUserId: String, peerUserId: String) {
        android.util.Log.i(LogTags.WEBRTC, "[$peerUserId] initiating P2P connection")
        val iceCandidates = mutableListOf<IceCandidate>()

        webRTCManager.createPeerConnection(
            peerId = peerUserId,
            onIceCandidate = { candidate ->
                iceCandidates.add(candidate)
            },
            onOffer = { offer ->
                // отправка offer через signaling
                signalingClient.sendOffer(
                    toPeerId = peerUserId,
                    fromPeerId = myUserId,
                    offer = offer,
                    iceCandidates = iceCandidates
                )
            }
        )
    }

    /**
     * Обрабатывает входящие сигналы
     */
    private fun handleSignal(fromPeerId: String, signal: com.p2p.domain.signaling.SignalMessage) {
        android.util.Log.d(LogTags.WEBRTC, "[$fromPeerId] signal received: ${signal.type}")
        when (signal.type) {
            SignalType.OFFER -> {
                if (signal.sdp == null) return

                val offer = SessionDescription(SessionDescription.Type.OFFER, signal.sdp)
                val iceCandidates = mutableListOf<IceCandidate>()

                webRTCManager.handleOffer(
                    peerId = fromPeerId,
                    offer = offer,
                    onIceCandidate = { candidate ->
                        iceCandidates.add(candidate)
                    },
                    onAnswer = { answer ->
                        // отправка answer
                        signalingClient.sendAnswer(
                            toPeerId = fromPeerId,
                            fromPeerId = signal.to ?: "",
                            answer = answer,
                            iceCandidates = iceCandidates
                        )
                    }
                )

                // добавление ICE candidates из offer
                signal.iceCandidates?.forEach { candidateDto ->
                    webRTCManager.addIceCandidate(fromPeerId, candidateDto.toIceCandidate())
                }
            }

            SignalType.ANSWER -> {
                if (signal.sdp == null) return

                val answer = SessionDescription(SessionDescription.Type.ANSWER, signal.sdp)
                webRTCManager.handleAnswer(fromPeerId, answer)

                // добавление ICE candidates из answer
                signal.iceCandidates?.forEach { candidateDto ->
                    webRTCManager.addIceCandidate(fromPeerId, candidateDto.toIceCandidate())
                }
            }

            SignalType.ICE_CANDIDATE -> {
                signal.iceCandidate?.let { candidateDto ->
                    webRTCManager.addIceCandidate(fromPeerId, candidateDto.toIceCandidate())
                }
            }

            else -> {} // ERROR / неизвестный тип - игнорировать
        }
    }

    fun sendMessage(peerId: String, json: String): Boolean {
        return webRTCManager.sendMessage(peerId, json)
    }

    fun disconnectSignaling() {
        signalingClient.disconnect()
    }

    fun closePeerConnection(peerUserId: String) {
        webRTCManager.closePeerConnection(peerUserId)
    }

    fun isConnected(peerUserId: String): Boolean {
        return webRTCManager.isConnected(peerUserId)
    }
}