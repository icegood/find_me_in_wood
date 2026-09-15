package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.model.NetworkId

/**
 * Bridge between the UI and the running session's DiscoveryManager. The service owns the
 * manager; the UI only asks it to emit frames.
 */
object DiscoveryBridge {
    var onRequestJoin: ((NetworkId) -> Unit)? = null
    var onApprove: ((com.icegood.findmeinwood.core.model.MemberId) -> Unit)? = null
    var onReject: ((com.icegood.findmeinwood.core.model.MemberId) -> Unit)? = null

    fun requestJoin(netId: NetworkId) { onRequestJoin?.invoke(netId) }
    fun approve(member: com.icegood.findmeinwood.core.model.MemberId) { onApprove?.invoke(member) }
    fun reject(member: com.icegood.findmeinwood.core.model.MemberId) { onReject?.invoke(member) }
}
