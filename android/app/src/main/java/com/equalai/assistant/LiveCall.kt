package com.equalai.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

// ============================================================================
// LIVE CALL: real two-way voice between two phones running Equal, over the internet (WebRTC).
//
// The same free Firebase database that carries app rings is used only to set the call up ("signaling").
// The voices themselves go straight between the phones (or through a TURN relay when a network blocks that).
// Either phone can call the other; the called phone shows the full-screen Attend / Cancel ring first.
// ============================================================================

object LiveCall {

    // ---- state shown on the call screen
    var active by mutableStateOf(false)
    var status by mutableStateOf("")
    var peerName by mutableStateOf("")
    var muted by mutableStateOf(false)
    var speakerOn by mutableStateOf(true)
    var seconds by mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var app: Context? = null
    private var callee = ""                    // the called phone's name: its mailbox carries the whole call's signaling
    private var isCaller = false
    @Volatile private var attended = false
    @Volatile private var ended = true
    @Volatile private var connectedAt = 0L
    private var startedAt = 0L

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: org.webrtc.AudioTrack? = null

    private val pendingIce = ArrayList<IceCandidate>()
    @Volatile private var remoteSet = false

    private var listener: Job? = null
    private var timer: Job? = null
    private var setup: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var oldMode = -1
    private var oldSpeaker = false
    private var libReady = false

    private fun main(block: () -> Unit) = AssistantBridge.onMain(block)

    private fun iceServers(): List<PeerConnection.IceServer> {
        val list = ArrayList<PeerConnection.IceServer>()
        list.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val urls = AppSettings.turnUrls.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isNotEmpty()) {
            list.add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(AppSettings.turnUser)
                    .setPassword(AppSettings.turnPass)
                    .createIceServer()
            )
        }
        return list
    }

    // ------------------------------------------------------------------ starting

    /** You call another phone. */
    fun startOutgoing(ctx: Context, to: String) {
        val a = ctx.applicationContext
        AppSettings.init(a)
        if (active) { AssistantBridge.callNote("A live call is already in progress."); return }
        if (!RelayClient.configured) {
            AssistantBridge.callNote("Live call: fill in the database link and pairing code in Setup first.")
            return
        }
        if (ContextCompat.checkSelfPermission(a, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AssistantBridge.callNote("Live call: allow the microphone for Equal first.")
            return
        }
        val name = RelayClient.cleanName(to)
        begin(a, name, caller = true, other = name)
        setup = scope.launch {
            val inbox = Channel<JSONObject>(Channel.UNLIMITED)
            RelayClient.delete(callee, "r2c")
            RelayClient.delete(callee, "c2r")
            listener = RelayClient.listen(scope, callee, "r2c") { key, msg ->
                scope.launch { RelayClient.delete(callee, "r2c/$key") }
                if (System.currentTimeMillis() - msg.optLong("ts") > 180_000) return@listen
                when (msg.optString("kind")) {
                    "ack", "attend", "declined", "missed" -> inbox.trySend(msg)
                    "answer" -> onAnswer(msg.optString("sdp"))
                    "ice" -> onIce(msg)
                    "bye", "hangup" -> end("They ended the call.", notify = false)
                }
            }
            if (!RelayClient.send(callee, "c2r", JSONObject().put("kind", "ring").put("mode", "live").put("from", AppSettings.receiverName))) {
                end("Could not reach the database. Check your internet.", notify = false)
                return@launch
            }
            var ack: JSONObject? = null
            withTimeoutOrNull(45_000) {
                while (ack == null) { val m = inbox.receive(); if (m.optString("kind") == "ack") ack = m }
            }
            if (ack == null) {
                end("$name's phone isn't reachable (Equal isn't running there, or it is offline).", notify = true)
                return@launch
            }
            main { status = "Ringing…" }
            var decision: JSONObject? = null
            withTimeoutOrNull(200_000) {
                while (decision == null) {
                    val m = inbox.receive()
                    if (m.optString("kind") in listOf("attend", "declined", "missed")) decision = m
                }
            }
            when (decision?.optString("kind")) {
                "attend" -> {
                    attended = true
                    main { status = "Connecting…" }
                    if (!createConnection()) { end("Could not start the audio.", notify = true); return@launch }
                    makeOffer()
                }
                "declined" -> end("$name cancelled the call.", notify = false)
                else -> end("$name didn't answer.", notify = true)
            }
        }
    }

    /** Someone called you and you tapped Attend (called from the receiver service). */
    fun beginIncoming(ctx: Context, myName: String, from: String) {
        val a = ctx.applicationContext
        AppSettings.init(a)
        if (active) return
        begin(a, myName, caller = false, other = from.ifBlank { "Equal" })
        attended = true
        main { status = "Connecting…" }
        if (!createConnection()) end("Could not start the audio.", notify = true)
    }

    private fun begin(a: Context, calleeName: String, caller: Boolean, other: String) {
        app = a
        callee = calleeName
        isCaller = caller
        attended = false
        ended = false
        connectedAt = 0L
        startedAt = System.currentTimeMillis()
        remoteSet = false
        synchronized(pendingIce) { pendingIce.clear() }
        main {
            active = true
            peerName = other
            status = if (caller) "Calling…" else "Connecting…"
            muted = false
            speakerOn = true
            seconds = 0
        }
        try {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "equalai:livecall").apply { acquire(2 * 60 * 60_000L) }
        } catch (e: Exception) { }
        RelayReceiverService.keepAlive(a)
        AssistantBridge.foregroundService?.let { svc -> main { svc.enterCallMode() } }
        try {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            oldMode = am.mode
            @Suppress("DEPRECATION") run { oldSpeaker = am.isSpeakerphoneOn }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
        } catch (e: Exception) { }
        showScreen(a)
    }

    private fun showScreen(a: Context) {
        try {
            a.startActivity(Intent(a, LiveCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AssistantBridge.log("Could not open the call screen: ${e.localizedMessage}")
        }
    }

    // ------------------------------------------------------------------ WebRTC

    private fun createConnection(): Boolean {
        val a = app ?: return false
        return try {
            if (!libReady) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(a).createInitializationOptions())
                libReady = true
            }
            val module = JavaAudioDeviceModule.builder(a)
                .setUseHardwareAcousticEchoCanceler(false)       // WebRTC's own echo canceller behaves the same on every phone
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            adm = module
            val f = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            factory = f
            val cfg = PeerConnection.RTCConfiguration(iceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val p = f.createPeerConnection(cfg, observer) ?: return false
            pc = p
            val src = f.createAudioSource(MediaConstraints())
            source = src
            val t = f.createAudioTrack("equal-audio", src)
            t.setEnabled(!muted)
            track = t
            p.addTrack(t, listOf("equal-stream"))
            true
        } catch (e: Throwable) {
            AssistantBridge.log("WebRTC setup failed: ${e.localizedMessage}")
            false
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected()
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    main { if (!ended) status = "Reconnecting…" }
                    scope.launch {
                        delay(15_000)
                        if (!ended && connectedAt > 0 && pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) {
                            end("The connection was lost.", notify = true)
                        }
                    }
                }
                PeerConnection.IceConnectionState.FAILED ->
                    end("Could not connect the two phones. A relay (TURN) server may be needed: see Setup > Live calls.", notify = true)
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {
            c ?: return
            signal(JSONObject().put("kind", "ice").put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("cand", c.sdp))
        }
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private class Sdp(
        private val onCreate: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
        private val onFail: (String) -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) { if (d != null) onCreate(d) }
        override fun onSetSuccess() { onSet() }
        override fun onCreateFailure(e: String?) { onFail(e ?: "failed") }
        override fun onSetFailure(e: String?) { onFail(e ?: "failed") }
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun makeOffer() {
        val p = pc ?: return
        p.createOffer(Sdp(
            onCreate = { d ->
                p.setLocalDescription(Sdp(), d)
                signal(JSONObject().put("kind", "offer").put("sdp", d.description))
            },
            onFail = { end("Could not start the call ($it).", notify = true) }
        ), audioConstraints())
    }

    /** The other phone's offer arrived (this phone is the one being called). */
    fun onOffer(sdp: String) {
        val p = pc ?: return
        p.setRemoteDescription(Sdp(
            onSet = {
                remoteSet = true
                flushIce()
                p.createAnswer(Sdp(
                    onCreate = { d ->
                        p.setLocalDescription(Sdp(), d)
                        signal(JSONObject().put("kind", "answer").put("sdp", d.description))
                    },
                    onFail = { end("Could not answer the call ($it).", notify = true) }
                ), audioConstraints())
            },
            onFail = { end("Could not read the call setup ($it).", notify = true) }
        ), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun onAnswer(sdp: String) {
        val p = pc ?: return
        p.setRemoteDescription(Sdp(
            onSet = { remoteSet = true; flushIce() },
            onFail = { end("Could not read the answer ($it).", notify = true) }
        ), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun onIce(msg: JSONObject) {
        val c = IceCandidate(msg.optString("mid"), msg.optInt("idx"), msg.optString("cand"))
        if (remoteSet && pc != null) pc?.addIceCandidate(c) else synchronized(pendingIce) { pendingIce.add(c) }
    }

    private fun flushIce() {
        val list = synchronized(pendingIce) { val l = ArrayList(pendingIce); pendingIce.clear(); l }
        list.forEach { pc?.addIceCandidate(it) }
    }

    fun onBye() = end("They ended the call.", notify = false)

    private fun signal(msg: JSONObject) {
        val box = if (isCaller) "c2r" else "r2c"
        val to = callee
        scope.launch { RelayClient.send(to, box, msg) }
    }

    private fun connected() {
        if (connectedAt > 0 || ended) return
        connectedAt = System.currentTimeMillis()
        main { status = "Connected" }
        AssistantBridge.callNote("Live call connected with $peerName.")
        timer?.cancel()
        timer = scope.launch {
            while (isActive && !ended) {
                val s = ((System.currentTimeMillis() - connectedAt) / 1000).toInt()
                main { seconds = s }
                delay(1_000)
            }
        }
    }

    // ------------------------------------------------------------------ controls

    fun toggleMute() {
        muted = !muted
        try { track?.setEnabled(!muted) } catch (e: Exception) { }
    }

    fun toggleSpeaker() {
        speakerOn = !speakerOn
        try {
            val am = app?.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = speakerOn }
        } catch (e: Exception) { }
    }

    fun hangUp() = end("Call ended.", notify = true)

    /** Ends the call on this phone; when [notify] is true the other phone is told too. Safe to call twice. */
    private fun end(reason: String, notify: Boolean) {
        if (ended) return
        ended = true
        val ctx = app
        val box = if (isCaller) "c2r" else "r2c"
        val to = callee
        // before the other phone tapped Attend, a caller's hang-up must be "abort" (the ring is waiting for it)
        val kind = if (isCaller && !attended) "abort" else "bye"
        val wasConnected = connectedAt > 0
        val length = if (wasConnected) ((System.currentTimeMillis() - connectedAt) / 1000).toInt() else 0
        val other = peerName
        val begun = startedAt
        main { status = reason }
        scope.launch {
            if (notify) RelayClient.send(to, box, JSONObject().put("kind", kind))
            setup?.cancel()
            listener?.cancel()
            timer?.cancel()
            try { pc?.close() } catch (e: Exception) { }
            try { pc?.dispose() } catch (e: Exception) { }
            try { track?.dispose() } catch (e: Exception) { }
            try { source?.dispose() } catch (e: Exception) { }
            try { factory?.dispose() } catch (e: Exception) { }
            try { adm?.release() } catch (e: Exception) { }
            pc = null; track = null; source = null; factory = null; adm = null
            if (ctx != null) {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = oldSpeaker }
                    if (oldMode >= 0) am.mode = oldMode
                } catch (e: Exception) { }
                try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
                AssistantBridge.foregroundService?.let { svc -> main { svc.exitCallMode() } }
                try {
                    CallStore.upsert(
                        ctx,
                        CallRecord(
                            id = "live_$begun", number = other, caller = "Live call with $other",
                            summary = if (wasConnected) "Live call, ${length / 60} min ${length % 60} s." else reason,
                            transcript = "", startedAt = begun, updatedAt = System.currentTimeMillis(),
                            durationSec = length, state = "DONE", audioOk = true
                        )
                    )
                } catch (e: Exception) { }
                RelayReceiverService.releaseKeepAlive(ctx)
            }
            AssistantBridge.callNote("Live call: $reason")
            delay(1_500)
            main { active = false }
        }
    }
}

// ============================================================================
// THE CALL SCREEN
// ============================================================================

class LiveCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val on = LiveCall.active
            LaunchedEffect(on) { if (!on) finish() }
            val s = LiveCall.seconds
            val timeText = if (LiveCall.status == "Connected") String.format("%02d:%02d", s / 60, s % 60) else LiveCall.status
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 72.dp)) {
                    Text(LiveCall.peerName, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(timeText, color = Color(0xFF94A3B8), fontSize = 20.sp, textAlign = TextAlign.Center)
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 32.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { LiveCall.toggleMute() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (LiveCall.muted) Color(0xFFF59E0B) else Color(0xFF334155)),
                            modifier = Modifier.weight(1f).height(80.dp)
                        ) { Text(if (LiveCall.muted) "Unmute" else "Mute", fontSize = 20.sp) }
                        Button(
                            onClick = { LiveCall.toggleSpeaker() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (LiveCall.speakerOn) Color(0xFF2563EB) else Color(0xFF334155)),
                            modifier = Modifier.weight(1f).height(80.dp)
                        ) { Text(if (LiveCall.speakerOn) "Speaker on" else "Speaker off", fontSize = 20.sp) }
                    }
                    Button(
                        onClick = { LiveCall.hangUp() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    ) { Text("END CALL", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
