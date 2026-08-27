package app.findmeinwood.app

import app.findmeinwood.core.model.MemberId
import app.findmeinwood.core.model.NetworkProfile
import app.findmeinwood.core.session.Diagnostics
import app.findmeinwood.core.session.PeerState
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

    /** Decrypted incoming payloads (chat etc.), replayed to late collectors. */
    private val _incomingPayloads = MutableSharedFlow<Pair<ByteArray, MemberId>>(replay = 64)
    val incomingPayloads: SharedFlow<Pair<ByteArray, MemberId>> = _incomingPayloads

    fun publish(profile: NetworkProfile?, peers: Map<MemberId, PeerState>, lastWire: ByteArray?) {
        _profile.value = profile
        _peers.value = peers
        _lastWireFrame.value = lastWire
    }

    fun publishDiagnostics(d: Diagnostics) { _diagnostics.value = d }

    suspend fun publishIncoming(payload: ByteArray, sender: MemberId) {
        _incomingPayloads.emit(payload to sender)
    }

    fun clear() {
        _profile.value = null
        _peers.value = emptyMap()
        _lastWireFrame.value = null
        _diagnostics.value = Diagnostics()
    }
}
