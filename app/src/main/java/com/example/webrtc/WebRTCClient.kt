package com.example.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.data.model.SignalingEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

enum class CallStatus {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED
}

data class CallUiState(
    val status: CallStatus = CallStatus.IDLE,
    val remoteUserId: String = "",
    val remoteUserName: String = "",
    val isVideoCall: Boolean = false,
    val isMuted: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val isFrontCamera: Boolean = true,
    val hasRemoteVideoFrame: Boolean = false,
    val iceStatusLabel: String = "Initializing…",
    val durationSeconds: Long = 0L,
    val pendingOfferSdp: String? = null
) {
    val isActive: Boolean get() = status != CallStatus.IDLE
}

/**
 * WhatsApp-grade global-internet WebRTC Audio & Video Calling engine:
 * - Google public STUN + OpenRelay/Render TURN servers for global NAT traversal
 * - Proper SetLocalDescription-before-Signaling ordering & thread-safe ICE buffering
 * - Connection watchdog with automatic ICE restart and TURN-Relay fallback
 * - Adaptive camera format selection (Camera2 -> Camera1 fallback) & frame keep-alive
 * - Automatic ICE restart on Wi-Fi <-> Cellular transitions via ConnectivityManager
 */
class WebRTCClient(
    private val context: Context,
    private val signalingClient: SignalingClient
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val eglBase: EglBase by lazy { EglBase.create() }
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null

    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var isFrontCameraActive: Boolean = true
    private var captureWidth = 640
    private var captureHeight = 480
    private var captureFps = 24

    private var currentUserId: String = ""
    private var currentUserName: String = ""
    private var callTimerJob: Job? = null
    private var cameraWatchdogJob: Job? = null
    private var connectionWatchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastLocalFrameAtMs: Long = 0L
    @Volatile
    private var lastIceRestartAtMs: Long = 0L
    private var hasReceivedInitialNetwork = false
    private var switchedToRelayFallback = false

    private val localFrameHeartbeatSink = VideoSink { _: VideoFrame ->
        lastLocalFrameAtMs = System.currentTimeMillis()
    }

    private val remoteFrameNotifierSink = VideoSink { _: VideoFrame ->
        if (!_callState.value.hasRemoteVideoFrame) {
            scope.launch {
                _callState.update { it.copy(hasRemoteVideoFrame = true) }
            }
        }
    }

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private val defaultIceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )

    @Volatile
    private var activeIceServers: List<PeerConnection.IceServer> = defaultIceServers

    fun setCurrentUser(userId: String, name: String) {
        currentUserId = userId
        currentUserName = name
    }

    private fun ensureFactory() {
        if (peerConnectionFactory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun attachRenderers(localView: SurfaceViewRenderer?, remoteView: SurfaceViewRenderer?) {
        if (localRenderer !== localView) {
            localRenderer?.let { old -> runCatching { localVideoTrack?.removeSink(old) } }
            localRenderer = localView
            localView?.let { view ->
                runCatching {
                    view.setMirror(isFrontCameraActive)
                    localVideoTrack?.addSink(view)
                }
            }
        }
        if (remoteRenderer !== remoteView) {
            remoteRenderer?.let { old -> runCatching { remoteVideoTrack?.removeSink(old) } }
            remoteRenderer = remoteView
            remoteView?.let { view ->
                runCatching { remoteVideoTrack?.addSink(view) }
            }
        }
    }

    fun detachRenderers() {
        localRenderer?.let { old -> runCatching { localVideoTrack?.removeSink(old) } }
        remoteRenderer?.let { old -> runCatching { remoteVideoTrack?.removeSink(old) } }
        localRenderer = null
        remoteRenderer = null
    }

    fun startOutgoingCall(targetUserId: String, targetUserName: String, isVideo: Boolean) {
        if (_callState.value.isActive || currentUserId.isBlank()) return
        ensureFactory()
        configureCallAudio(enableSpeaker = isVideo)
        isFrontCameraActive = true
        switchedToRelayFallback = false
        synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
        signalingClient.setActiveCallMode(true)

        _callState.value = CallUiState(
            status = CallStatus.OUTGOING_RINGING,
            remoteUserId = targetUserId,
            remoteUserName = targetUserName,
            isVideoCall = isVideo,
            isCameraEnabled = isVideo,
            isSpeakerOn = isVideo,
            isFrontCamera = true,
            iceStatusLabel = "Calling via Global STUN/TURN…"
        )
        ActiveCallService.syncService(appContext, isActive = true)

        scope.launch {
            activeIceServers = signalingClient.fetchIceServers(defaultIceServers)
            if (!_callState.value.isActive) return@launch

            createPeerConnectionAndMedia(targetUserId, isVideo)
            registerNetworkMonitor()
            if (isVideo) startCameraWatchdog()

            val pc = peerConnection ?: return@launch
            val constraints = buildOfferAnswerConstraints(isVideo = isVideo, iceRestart = false)
            pc.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val sdp = desc ?: return
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            scope.launch(Dispatchers.IO) {
                                signalingClient.sendSignal(
                                    SignalingEnvelope(
                                        signalId = "offer_${UUID.randomUUID()}",
                                        type = "offer",
                                        sender = currentUserId,
                                        senderName = currentUserName,
                                        receiver = targetUserId,
                                        sdp = sdp.description,
                                        isVideo = isVideo
                                    )
                                )
                            }
                        }

                        override fun onSetFailure(error: String?) {
                            scope.launch {
                                _callState.update { it.copy(iceStatusLabel = "SDP setup failed • Retrying") }
                            }
                        }
                    }, sdp)
                }
            }, constraints)
        }
    }

    fun onIncomingSignal(envelope: SignalingEnvelope) {
        when (envelope.type) {
            "offer" -> {
                val activeRemote = _callState.value.remoteUserId
                val isSamePeerRenegotiation = _callState.value.isActive &&
                    activeRemote.equals(envelope.sender, ignoreCase = true)

                if (isSamePeerRenegotiation && envelope.sdp != null) {
                    val pc = peerConnection
                    val signalingState = pc?.signalingState()
                    if (signalingState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                        val isPolite = currentUserId.lowercase() < envelope.sender.lowercase()
                        if (!isPolite && _callState.value.status == CallStatus.OUTGOING_RINGING) {
                            return
                        }
                        if (isPolite) {
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    handleIceRestartOffer(envelope.sdp, envelope.sender)
                                }

                                override fun onSetFailure(p0: String?) {
                                    handleIceRestartOffer(envelope.sdp, envelope.sender)
                                }
                            }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                            return
                        }
                    }
                    handleIceRestartOffer(envelope.sdp, envelope.sender)
                    return
                }

                if (_callState.value.isActive) {
                    scope.launch(Dispatchers.IO) {
                        signalingClient.sendSignal(
                            SignalingEnvelope(
                                signalId = "reject_${UUID.randomUUID()}",
                                type = "call_reject",
                                sender = currentUserId,
                                receiver = envelope.sender
                            )
                        )
                    }
                    return
                }

                synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }
                signalingClient.setActiveCallMode(true)
                _callState.value = CallUiState(
                    status = CallStatus.INCOMING_RINGING,
                    remoteUserId = envelope.sender,
                    remoteUserName = envelope.senderName ?: envelope.sender,
                    isVideoCall = envelope.isVideo,
                    isCameraEnabled = envelope.isVideo,
                    isSpeakerOn = envelope.isVideo,
                    isFrontCamera = true,
                    iceStatusLabel = if (envelope.isVideo) "Incoming WebRTC Video Call" else "Incoming WebRTC Audio Call",
                    pendingOfferSdp = envelope.sdp
                )
                ActiveCallService.syncService(appContext, isActive = true)
            }

            "answer" -> {
                val sdpDesc = envelope.sdp ?: return
                val pc = peerConnection ?: return
                val answerSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpDesc)
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        flushPendingIce()
                        scope.launch {
                            if (_callState.value.status != CallStatus.CONNECTED) {
                                _callState.update {
                                    it.copy(
                                        status = CallStatus.CONNECTING,
                                        iceStatusLabel = "Peer negotiated • Establishing ICE…"
                                    )
                                }
                                startConnectionWatchdog()
                            }
                        }
                    }
                }, answerSdp)
            }

            "ice" -> {
                val candStr = envelope.candidate ?: return
                val mid = envelope.sdpMid ?: "0"
                val ice = IceCandidate(mid, envelope.sdpMLineIndex, candStr)
                val pc = peerConnection
                if (pc?.remoteDescription != null) {
                    pc.addIceCandidate(ice)
                } else {
                    synchronized(pendingIceCandidates) {
                        pendingIceCandidates.add(ice)
                    }
                }
            }

            "call_end", "call_reject" -> {
                if (_callState.value.remoteUserId.equals(envelope.sender, ignoreCase = true)) {
                    cleanupCallResources()
                }
            }
        }
    }

    private fun handleIceRestartOffer(offerSdp: String, remoteUserId: String) {
        val pc = peerConnection ?: return
        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingIce()
                val constraints = buildOfferAnswerConstraints(
                    isVideo = _callState.value.isVideoCall,
                    iceRestart = false
                )
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        val answer = desc ?: return
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                scope.launch(Dispatchers.IO) {
                                    signalingClient.sendSignal(
                                        SignalingEnvelope(
                                            signalId = "ans_${UUID.randomUUID()}",
                                            type = "answer",
                                            sender = currentUserId,
                                            senderName = currentUserName,
                                            receiver = remoteUserId,
                                            sdp = answer.description,
                                            isVideo = _callState.value.isVideoCall
                                        )
                                    )
                                }
                            }
                        }, answer)
                    }
                }, constraints)
            }
        }, remoteOffer)
    }

    fun acceptIncomingCall(withVideo: Boolean = _callState.value.isVideoCall) {
        val state = _callState.value
        val offerSdp = state.pendingOfferSdp ?: return
        if (state.status != CallStatus.INCOMING_RINGING) return

        ensureFactory()
        configureCallAudio(enableSpeaker = withVideo)
        isFrontCameraActive = true
        switchedToRelayFallback = false
        signalingClient.setActiveCallMode(true)

        _callState.update {
            it.copy(
                status = CallStatus.CONNECTING,
                isVideoCall = withVideo,
                isCameraEnabled = withVideo,
                isSpeakerOn = withVideo,
                isFrontCamera = true,
                iceStatusLabel = "Connecting P2P…"
            )
        }
        ActiveCallService.syncService(appContext, isActive = true)

        scope.launch {
            activeIceServers = signalingClient.fetchIceServers(defaultIceServers)
            if (!_callState.value.isActive) return@launch

            createPeerConnectionAndMedia(state.remoteUserId, withVideo)
            registerNetworkMonitor()
            if (withVideo) startCameraWatchdog()
            startConnectionWatchdog()

            val pc = peerConnection ?: return@launch
            val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            pc.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    flushPendingIce()
                    val constraints = buildOfferAnswerConstraints(isVideo = withVideo, iceRestart = false)
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            val answer = desc ?: return
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    scope.launch(Dispatchers.IO) {
                                        signalingClient.sendSignal(
                                            SignalingEnvelope(
                                                signalId = "ans_${UUID.randomUUID()}",
                                                type = "answer",
                                                sender = currentUserId,
                                                senderName = currentUserName,
                                                receiver = state.remoteUserId,
                                                sdp = answer.description,
                                                isVideo = withVideo
                                            )
                                        )
                                    }
                                }
                            }, answer)
                        }
                    }, constraints)
                }
            }, remoteOffer)
        }
    }

    fun declineOrEndCall() {
        val remoteId = _callState.value.remoteUserId
        val wasRinging = _callState.value.status == CallStatus.INCOMING_RINGING
        if (remoteId.isNotBlank() && currentUserId.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                signalingClient.sendSignal(
                    SignalingEnvelope(
                        signalId = "end_${UUID.randomUUID()}",
                        type = if (wasRinging) "call_reject" else "call_end",
                        sender = currentUserId,
                        receiver = remoteId
                    )
                )
            }
        }
        cleanupCallResources()
    }

    fun toggleMute() {
        val next = !_callState.value.isMuted
        localAudioTrack?.setEnabled(!next)
        _callState.update { it.copy(isMuted = next) }
    }

    fun toggleCamera() {
        if (!_callState.value.isVideoCall) return
        val next = !_callState.value.isCameraEnabled
        localVideoTrack?.setEnabled(next)
        if (next) {
            runCatching { videoCapturer?.startCapture(captureWidth, captureHeight, captureFps) }
            lastLocalFrameAtMs = System.currentTimeMillis()
        }
        _callState.update { it.copy(isCameraEnabled = next) }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                isFrontCameraActive = isFrontCamera
                scope.launch {
                    localRenderer?.setMirror(isFrontCamera)
                    _callState.update { it.copy(isFrontCamera = isFrontCamera) }
                }
            }

            override fun onCameraSwitchError(errorDescription: String?) = Unit
        })
    }

    fun toggleSpeaker() {
        val next = !_callState.value.isSpeakerOn
        configureCallAudio(enableSpeaker = next)
        _callState.update { it.copy(isSpeakerOn = next) }
    }

    private fun buildOfferAnswerConstraints(isVideo: Boolean, iceRestart: Boolean): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false")
            )
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
    }

    private fun createPeerConnectionAndMedia(targetUserId: String, isVideo: Boolean) {
        val factory = peerConnectionFactory ?: return
        val rtcConfig = PeerConnection.RTCConfiguration(activeIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            audioJitterBufferMaxPackets = 120
            audioJitterBufferFastAccelerate = true
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                val ice = candidate ?: return
                scope.launch(Dispatchers.IO) {
                    signalingClient.sendSignal(
                        SignalingEnvelope(
                            signalId = "ice_${UUID.randomUUID()}",
                            type = "ice",
                            sender = currentUserId,
                            receiver = targetUserId,
                            candidate = ice.sdp,
                            sdpMid = ice.sdpMid,
                            sdpMLineIndex = ice.sdpMLineIndex
                        )
                    )
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> markCallConnected()
                        PeerConnection.PeerConnectionState.FAILED -> {
                            _callState.update { it.copy(iceStatusLabel = "Retrying via TURN Relay…") }
                            switchToRelayAndRestartIce()
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            _callState.update { it.copy(iceStatusLabel = "Weak Network • Reconnecting ICE…") }
                            triggerIceRestart(reason = "Peer disconnected")
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> cleanupCallResources()
                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                scope.launch {
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> markCallConnected()
                        PeerConnection.IceConnectionState.CHECKING -> {
                            _callState.update { it.copy(iceStatusLabel = "ICE Checking…") }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _callState.update { it.copy(iceStatusLabel = "Weak Network • Reconnecting ICE…") }
                            triggerIceRestart(reason = "ICE disconnected")
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _callState.update { it.copy(iceStatusLabel = "Switching to TURN Relay…") }
                            switchToRelayAndRestartIce()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> cleanupCallResources()
                        else -> Unit
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    attachRemoteVideoTrack(track)
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null) {
                    attachRemoteVideoTrack(track)
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        // Audio Track with Echo Cancellation, Noise Suppression, and prioritized bitrate
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("HS_AUDIO_0", audioSource).apply {
            setEnabled(true)
        }
        audioSender = localAudioTrack?.let { peerConnection?.addTrack(it, listOf("HS_STREAM")) }
        tuneAudioSenderPriority()

        // Permanent ON Camera Video Track with supported format negotiation
        if (isVideo) {
            createCameraCapturer()?.let { capturer ->
                videoCapturer = capturer
                surfaceTextureHelper = SurfaceTextureHelper.create("HSCaptureThread", eglBase.eglBaseContext)
                videoSource = factory.createVideoSource(capturer.isScreencast)
                capturer.initialize(surfaceTextureHelper, appContext, videoSource?.capturerObserver)
                runCatching { capturer.startCapture(captureWidth, captureHeight, captureFps) }
                lastLocalFrameAtMs = System.currentTimeMillis()
                localVideoTrack = factory.createVideoTrack("HS_VIDEO_0", videoSource).apply {
                    setEnabled(true)
                    addSink(localFrameHeartbeatSink)
                    localRenderer?.let { view ->
                        view.setMirror(isFrontCameraActive)
                        addSink(view)
                    }
                }
                videoSender = localVideoTrack?.let { peerConnection?.addTrack(it, listOf("HS_STREAM")) }
                tuneVideoSenderDegradation()
            }
        }
    }

    private fun attachRemoteVideoTrack(track: VideoTrack) {
        scope.launch {
            if (remoteVideoTrack !== track) {
                remoteVideoTrack?.let { old ->
                    runCatching { old.removeSink(remoteFrameNotifierSink) }
                    remoteRenderer?.let { view -> runCatching { old.removeSink(view) } }
                }
                remoteVideoTrack = track
                track.setEnabled(true)
                runCatching { track.addSink(remoteFrameNotifierSink) }
                remoteRenderer?.let { view -> runCatching { track.addSink(view) } }
            }
        }
    }

    private fun markCallConnected() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        val label = if (switchedToRelayFallback) {
            "Connected • Global TURN Relay"
        } else {
            "Connected • Direct P2P Media"
        }
        _callState.update {
            it.copy(
                status = CallStatus.CONNECTED,
                iceStatusLabel = label
            )
        }
        startCallTimer()
    }

    private fun startConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = scope.launch {
            delay(9_000L)
            if (isActive && _callState.value.status == CallStatus.CONNECTING) {
                _callState.update { it.copy(iceStatusLabel = "Optimizing P2P route…") }
                triggerIceRestart("Connecting timeout 9s")
            }
            delay(8_000L)
            if (isActive && _callState.value.status == CallStatus.CONNECTING) {
                _callState.update { it.copy(iceStatusLabel = "Switching to TURN Relay…") }
                switchToRelayAndRestartIce()
            }
        }
    }

    private fun switchToRelayAndRestartIce() {
        val pc = peerConnection ?: return
        if (!switchedToRelayFallback) {
            switchedToRelayFallback = true
            val relayConfig = PeerConnection.RTCConfiguration(activeIceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                audioJitterBufferMaxPackets = 120
                audioJitterBufferFastAccelerate = true
            }
            runCatching { pc.setConfiguration(relayConfig) }
        }
        triggerIceRestart("TURN relay fallback")
    }

    private fun tuneAudioSenderPriority() {
        val sender = audioSender ?: return
        runCatching {
            val params = sender.parameters
            params.encodings.forEach { enc ->
                enc.maxBitrateBps = 48_000
                enc.bitratePriority = 4.0
                enc.networkPriority = 3
            }
            sender.parameters = params
        }
    }

    private fun tuneVideoSenderDegradation() {
        val sender = videoSender ?: return
        runCatching {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            params.encodings.forEach { enc ->
                enc.minBitrateBps = 120_000
                enc.maxBitrateBps = 900_000
                enc.maxFramerate = captureFps
                enc.bitratePriority = 1.0
            }
            sender.parameters = params
        }
    }

    private fun startCameraWatchdog() {
        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = scope.launch {
            while (isActive && _callState.value.isActive && _callState.value.isVideoCall) {
                delay(3_000L)
                if (!_callState.value.isCameraEnabled) continue
                val elapsedSinceFrame = System.currentTimeMillis() - lastLocalFrameAtMs
                if (elapsedSinceFrame > 5_000L) {
                    recoverVideoCapture()
                }
            }
        }
    }

    private fun recoverVideoCapture() {
        val capturer = videoCapturer ?: return
        runCatching { capturer.stopCapture() }
        val restarted = runCatching {
            capturer.startCapture(captureWidth, captureHeight, captureFps)
            true
        }.getOrDefault(false)

        if (!restarted) {
            runCatching { capturer.dispose() }
            val fresh = createCameraCapturer() ?: return
            videoCapturer = fresh
            val helper = surfaceTextureHelper ?: SurfaceTextureHelper.create("HSCaptureThread", eglBase.eglBaseContext)
                .also { surfaceTextureHelper = it }
            fresh.initialize(helper, appContext, videoSource?.capturerObserver)
            runCatching { fresh.startCapture(captureWidth, captureHeight, captureFps) }
        }
        lastLocalFrameAtMs = System.currentTimeMillis()
    }

    private fun registerNetworkMonitor() {
        unregisterNetworkMonitor()
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        hasReceivedInitialNetwork = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!hasReceivedInitialNetwork) {
                    hasReceivedInitialNetwork = true
                    return
                }
                scope.launch {
                    if (_callState.value.isActive && _callState.value.status != CallStatus.INCOMING_RINGING) {
                        _callState.update { it.copy(iceStatusLabel = "Network changed • Restarting ICE…") }
                        triggerIceRestart(reason = "Network switched")
                    }
                }
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    private fun unregisterNetworkMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun triggerIceRestart(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastIceRestartAtMs < 4_500L) return
        val pc = peerConnection ?: return
        val remoteId = _callState.value.remoteUserId
        if (remoteId.isBlank() || _callState.value.status == CallStatus.INCOMING_RINGING) return

        lastIceRestartAtMs = now
        pc.restartIce()
        val constraints = buildOfferAnswerConstraints(
            isVideo = _callState.value.isVideoCall,
            iceRestart = true
        )
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                val sdp = desc ?: return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        scope.launch(Dispatchers.IO) {
                            signalingClient.sendSignal(
                                SignalingEnvelope(
                                    signalId = "ice_restart_${UUID.randomUUID()}",
                                    type = "offer",
                                    sender = currentUserId,
                                    senderName = currentUserName,
                                    receiver = remoteId,
                                    sdp = sdp.description,
                                    isVideo = _callState.value.isVideoCall
                                )
                            )
                        }
                    }
                }, sdp)
            }
        }, constraints)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerators: List<CameraEnumerator> = listOfNotNull(
            runCatching {
                if (Camera2Enumerator.isSupported(appContext)) Camera2Enumerator(appContext) else null
            }.getOrNull(),
            Camera1Enumerator(true)
        )
        for (enumerator in enumerators) {
            val deviceNames = runCatching { enumerator.deviceNames }.getOrNull().orEmpty()
            if (deviceNames.isEmpty()) continue
            val front = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            val chosen = front ?: deviceNames.firstOrNull() ?: continue
            isFrontCameraActive = enumerator.isFrontFacing(chosen)
            selectBestFormat(enumerator, chosen)
            val capturer = runCatching { enumerator.createCapturer(chosen, null) }.getOrNull()
            if (capturer != null) return capturer
        }
        return null
    }

    private fun selectBestFormat(enumerator: CameraEnumerator, deviceName: String) {
        val formats: List<CameraEnumerationAndroid.CaptureFormat> = runCatching {
            enumerator.getSupportedFormats(deviceName)
        }.getOrNull().orEmpty()
        if (formats.isEmpty()) {
            captureWidth = 640
            captureHeight = 480
            captureFps = 24
            return
        }
        val preferred = formats
            .filter { it.width in 480..1280 && it.height in 360..1280 }
            .minByOrNull { kotlin.math.abs(it.width - 720) + kotlin.math.abs(it.height - 720) }
            ?: formats.first()
        captureWidth = preferred.width
        captureHeight = preferred.height
        val maxFps = (preferred.framerate.max / 1000).coerceIn(15, 30)
        captureFps = maxFps
    }

    private fun flushPendingIce() {
        val pc = peerConnection ?: return
        val copy = synchronized(pendingIceCandidates) {
            val list = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
            list
        }
        copy.forEach { pc.addIceCandidate(it) }
    }

    private fun startCallTimer() {
        if (callTimerJob?.isActive == true) return
        callTimerJob = scope.launch {
            while (isActive && _callState.value.status == CallStatus.CONNECTED) {
                delay(1_000L)
                _callState.update { it.copy(durationSeconds = it.durationSeconds + 1L) }
            }
        }
    }

    private fun configureCallAudio(enableSpeaker: Boolean) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enableSpeaker
    }

    private fun releaseCallAudio() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        }
    }

    fun cleanupCallResources() {
        signalingClient.setActiveCallMode(false)
        callTimerJob?.cancel()
        callTimerJob = null
        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = null
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        unregisterNetworkMonitor()
        synchronized(pendingIceCandidates) { pendingIceCandidates.clear() }

        detachRenderers()
        runCatching { localVideoTrack?.removeSink(localFrameHeartbeatSink) }
        runCatching { remoteVideoTrack?.removeSink(remoteFrameNotifierSink) }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        remoteVideoTrack = null
        videoSender = null
        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        audioSender = null
        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        switchedToRelayFallback = false

        releaseCallAudio()
        _callState.value = CallUiState()
        ActiveCallService.syncService(appContext, isActive = false)
    }
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
