package app.findmeinwood.core.session

import app.findmeinwood.core.model.MemberId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** T5.5: counters surfaced in network detail (FR-3.3/FR-8.6 visibility). */
data class Diagnostics(
    val sent: Int = 0,
    val received: Int = 0,
    val relayed: Int = 0,
    val authFailed: Int = 0,
    val dropped: Int = 0,
)

data class EdgeView(
    val peerId: MemberId,
    val transport: app.findmeinwood.core.model.TransportId?,
    val state: EdgeManager.State?,
    val lastSeenMs: Long,
)
