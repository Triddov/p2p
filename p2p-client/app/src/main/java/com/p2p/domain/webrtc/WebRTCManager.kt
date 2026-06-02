package com.p2p.domain.webrtc

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        // 1. инициализация нативной библиотеки WebRTC (один раз)
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // 2. фабрики
        PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = true
            })
            .createPeerConnectionFactory()
    }

    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val dataChannels   = mutableMapOf<String, DataChannel>()

    // Буфер для offer/answer: заполняется при onCreateSuccess,
    // сбрасывается (вызывает callback) только когда ICE gathering завершён.
    private val pendingCallbacks = mutableMapOf<String, Pair<SessionDescription, (SessionDescription) -> Unit>>()

    //  Flows

    private val _messageFlow = MutableSharedFlow<Pair<String, String>>()
    val messageFlow: SharedFlow<Pair<String, String>> = _messageFlow

    private val _connectionStateFlow = MutableSharedFlow<Pair<String, Boolean>>()
    val connectionStateFlow: SharedFlow<Pair<String, Boolean>> = _connectionStateFlow

    // ICE сервера

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    // RTCConfiguration
    private fun buildRtcConfig() = PeerConnection.RTCConfiguration(iceServers).apply {
        sdpSemantics             = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        bundlePolicy             = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy            = PeerConnection.RtcpMuxPolicy.REQUIRE
        tcpCandidatePolicy       = PeerConnection.TcpCandidatePolicy.DISABLED
    }

    // Создание соединения (инициатор)
    fun createPeerConnection(
        peerId: String,
        onIceCandidate: (IceCandidate) -> Unit,
        onOffer: (SessionDescription) -> Unit
    ) {
        val pc = peerConnectionFactory.createPeerConnection(
            buildRtcConfig(),
            buildObserver(peerId, onIceCandidate)
        ) ?: run {
            android.util.Log.e("WebRTC", "createPeerConnection returned null")
            return
        }

        peerConnections[peerId] = pc

        // созданик DataChannel на стороне инициатора
        val dcInit = DataChannel.Init().apply { ordered = true }
        val dc = pc.createDataChannel("messages", dcInit)
        setupDataChannel(peerId, dc)

        // генерация Offer; отправка откладывается до завершения ICE gathering
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                pendingCallbacks[peerId] = sdp to onOffer
            }
            override fun onCreateFailure(error: String) {
                android.util.Log.e("WebRTC", "createOffer failed: $error")
            }
        }, MediaConstraints())
    }

    // Обработка входящего Offer (получатель)

    fun handleOffer(
        peerId: String,
        offer: SessionDescription,
        onIceCandidate: (IceCandidate) -> Unit,
        onAnswer: (SessionDescription) -> Unit
    ) {
        val pc = peerConnectionFactory.createPeerConnection(
            buildRtcConfig(),
            buildObserver(peerId, onIceCandidate)
        ) ?: run {
            android.util.Log.e("WebRTC", "createPeerConnection returned null")
            return
        }

        peerConnections[peerId] = pc

        pc.setRemoteDescription(SdpObserverAdapter(), offer)

        // генерация Answer; отправка откладывается до завершения ICE gathering
        pc.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                pendingCallbacks[peerId] = sdp to onAnswer
            }
            override fun onCreateFailure(error: String) {
                android.util.Log.e("WebRTC", "createAnswer failed: $error")
            }
        }, MediaConstraints())
    }

    // Установка Answer от удалённого пира

    fun handleAnswer(peerId: String, answer: SessionDescription) {
        peerConnections[peerId]?.setRemoteDescription(SdpObserverAdapter(), answer)
    }

    //  Добавление ICE candidate

    fun addIceCandidate(peerId: String, candidate: IceCandidate) {
        peerConnections[peerId]?.addIceCandidate(candidate)
    }

    // Отправка сообщения через DataChannel
    fun sendMessage(peerId: String, message: String): Boolean {
        val dc = dataChannels[peerId] ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        val bytes  = message.toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(bytes),
            false // binary = false -> текст
        )
        return dc.send(buffer)
    }

    // Проверка состояния P2P

    fun isConnected(peerId: String): Boolean =
        peerConnections[peerId]?.connectionState() ==
                PeerConnection.PeerConnectionState.CONNECTED

    // Закрытие соединения

    fun closePeerConnection(peerId: String) {
        pendingCallbacks.remove(peerId)
        dataChannels[peerId]?.close()
        dataChannels.remove(peerId)
        peerConnections[peerId]?.close()
        peerConnections.remove(peerId)
    }

    fun dispose() {
        pendingCallbacks.clear()
        dataChannels.values.forEach { it.close() }
        dataChannels.clear()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        peerConnectionFactory.dispose()
    }


    // todo helpers

    /**
     * Строит PeerConnection.Observer с нужными колбеками.
     * Все методы интерфейса реализованы через PeerConnectionObserver (no-op base).
     */
    private fun buildObserver(
        peerId: String,
        onIceCandidate: (IceCandidate) -> Unit
    ): PeerConnection.Observer = object : PeerConnectionObserver() {

        override fun onIceCandidate(candidate: IceCandidate) {
            onIceCandidate(candidate)
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                pendingCallbacks.remove(peerId)?.let { (sdp, callback) -> callback(sdp) }
            }
        }

        // Входящий DataChannel — со стороны получателя
        override fun onDataChannel(dc: DataChannel) {
            setupDataChannel(peerId, dc)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val connected = newState == PeerConnection.PeerConnectionState.CONNECTED
            scope.launch { _connectionStateFlow.emit(peerId to connected) }
        }
    }

    /**
     * Настраивает DataChannel: слушает входящие сообщения.
     */
    private fun setupDataChannel(peerId: String, dc: DataChannel) {
        dataChannels[peerId] = dc

        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                scope.launch { _messageFlow.emit(peerId to text) }
            }

            override fun onStateChange() {
                android.util.Log.d("WebRTC", "DataChannel[$peerId] → ${dc.state()}")
            }

            override fun onBufferedAmountChange(amount: Long) {}
        })
    }
}

//  Базовый Observer (заглушки для всех методов интерфейса)

/**
 * PeerConnection.Observer содержит ~10 абстрактных методов.
 * Наследуемся от этого класса и переопределяем только нужные.
 */
open class PeerConnectionObserver : PeerConnection.Observer {
    override fun onIceCandidate(candidate: IceCandidate)                         {}
    override fun onDataChannel(dc: DataChannel)                                   {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState)      {}
    override fun onConnectionChange(s: PeerConnection.PeerConnectionState)        {}
    override fun onIceConnectionReceivingChange(receiving: Boolean)               {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState)        {}
    override fun onAddStream(stream: MediaStream)                                  {}
    override fun onRemoveStream(stream: MediaStream)                               {}
    override fun onSignalingChange(s: PeerConnection.SignalingState)               {}
    override fun onRenegotiationNeeded()                                           {}
    override fun onAddTrack(r: RtpReceiver, streams: Array<out MediaStream>)       {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>)      {}
}

// SdpObserver с заглушками

/**
 * SdpObserver тоже содержит несколько методов.
 * Наследуемся и переопределяем только onCreateSuccess / onCreateFailure там,
 * где это нужно.
 */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess()                           {}
    override fun onCreateFailure(error: String)           {}
    override fun onSetFailure(error: String)              {
        android.util.Log.e("WebRTC", "setDescription failed: $error")
    }
}