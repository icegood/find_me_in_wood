package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkProfile
import com.icegood.findmeinwood.core.session.Diagnostics
import com.icegood.findmeinwood.core.session.JoinOutcome
import com.icegood.findmeinwood.core.session.PeerState
import com.icegood.findmeinwood.core.model.DiscoveredNetwork
import com.icegood.findmeinwood.core.model.PendingJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** App-level glue between SessionService and UI screens. */
object SessionBus {
    private val _profile = MutableStateFlow<NetworkProfile?>(null)
    val profile: StateFlow<NetworkProfile?> = _profile

    private val _peers = MutableStateFlow<Map<MemberId, PeerState>>(emptyMap())
    val peers: StateFlow<Map<MemberId, PeerState>> = _peers

    private val _lastWireFrame = MutableStateFlow<ByteArray?>(null)
    val lastWireFrame: StateFlow<ByteArray?> = _lastWireFrame

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics: StateFlow<Diagnostics> = _diagnostics

    /** True while a network session service is running (UI needs an observable flag). */
    private val _sessionRunning = MutableStateFlow(false)
    val sessionRunning: StateFlow<Boolean> = _sessionRunning

    /** US-8 discovery: networks heard around us (never member counts). */
    private val _discovered = MutableStateFlow<List<DiscoveredNetwork>>(emptyList())
    val discovered: StateFlow<List<DiscoveredNetwork>> = _discovered

    /** US-8: join requests awaiting the owner's approval. */
    private val _pendingJoins = MutableStateFlow<List<PendingJoin>>(emptyList())
    val pendingJoins: StateFlow<List<PendingJoin>> = _pendingJoins

    private val _ownerSas = MutableStateFlow<String?>(null)
    val ownerSas: StateFlow<String?> = _ownerSas

    /** Short note for the owner about the last approval attempt. */
    /** Per-transport radio state, e.g. WIFI_DIRECT=READY (status line in the UI). */
    private val _radioStates = MutableStateFlow<Map<com.icegood.findmeinwood.core.model.TransportId, com.icegood.findmeinwood.transport.api.RadioState>>(emptyMap())
    val radioStates: StateFlow<Map<com.icegood.findmeinwood.core.model.TransportId, com.icegood.findmeinwood.transport.api.RadioState>> = _radioStates

    private val _ownerNote = MutableStateFlow<String?>(null)
    val ownerNote: StateFlow<String?> = _ownerNote

    private val _joinOutcome = MutableStateFlow<JoinOutcome?>(null)
    val joinOutcome: StateFlow<JoinOutcome?> = _joinOutcome

    fun publishDiscovered(list: List<DiscoveredNetwork>) { _discovered.value = list }
    fun publishPending(list: List<PendingJoin>) { _pendingJoins.value = list }
    fun publishOwnerSas(sas: String?) { _ownerSas.value = sas }
    fun publishRadioStates(states: Map<com.icegood.findmeinwood.core.model.TransportId, com.icegood.findmeinwood.transport.api.RadioState>) {
        _radioStates.value = states
    }

    fun publishOwnerNote(note: String?) { _ownerNote.value = note }
    fun publishJoinOutcome(outcome: JoinOutcome?) { _joinOutcome.value = outcome }

    /** Decrypted incoming payloads (chat etc.), replayed to late collectors. */
    private val _incomingPayloads = MutableSharedFlow<Pair<ByteArray, MemberId>>(replay = 64)
    val incomingPayloads: SharedFlow<Pair<ByteArray, MemberId>> = _incomingPayloads

    fun publish(profile: NetworkProfile?, peers: Map<MemberId, PeerState>, lastWire: ByteArray?) {
        _profile.value = profile
        _peers.value = peers
        _lastWireFrame.value = lastWire
    }

    fun publishDiagnostics(d: Diagnostics) { _diagnostics.value = d }

    fun setSessionRunning(running: Boolean) { _sessionRunning.value = running }

    suspend fun publishIncoming(payload: ByteArray, sender: MemberId) {
        _incomingPayloads.emit(payload to sender)
    }

    fun clear() {
        _profile.value = null
        _peers.value = emptyMap()
        _lastWireFrame.value = null
        _diagnostics.value = Diagnostics()
        _sessionRunning.value = false
        _discovered.value = emptyList()
        _pendingJoins.value = emptyList()
        _ownerSas.value = null
        _ownerNote.value = null
        _joinOutcome.value = null
    }
}
