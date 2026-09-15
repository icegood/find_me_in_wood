package com.icegood.findmeinwood.transport.wifidirect

import android.net.wifi.p2p.WifiP2pManager
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Records what the transport asked the platform to do and lets a test answer
 * with peers / group state (the real wiring is device-verified).
 */
internal open class FakeWiring : WifiDirectWiring(
    org.mockito.Mockito.mock(android.content.Context::class.java),
) {
    val peerCallbacks = mutableListOf<(List<String>) -> Unit>()
    val infoCallbacks = mutableListOf<(GroupInfo) -> Unit>()
    val connectAddresses = mutableListOf<String>()
    val connectIntents = mutableListOf<Int>()
    var createGroupCalls = 0
    var removeGroupCalls = 0
    var discoverCalls = 0
    var boundPort: Int? = null
    var failBind = false
    var socketToReturn: Socket? = null
    var failConnect = false

    override fun initialize(): WifiP2pManager.Channel? =
        org.mockito.Mockito.mock(WifiP2pManager.Channel::class.java)

    override fun discoverPeers(channel: WifiP2pManager.Channel?, done: (P2pResult) -> Unit) {
        discoverCalls++
        done(P2pResult.OK)
    }

    override fun requestPeers(channel: WifiP2pManager.Channel?, peers: (List<String>) -> Unit) {
        peerCallbacks += peers
    }

    override fun requestConnectionInfo(
        channel: WifiP2pManager.Channel?,
        info: (GroupInfo) -> Unit,
    ) {
        infoCallbacks += info
    }

    override fun connect(
        channel: WifiP2pManager.Channel?,
        address: String,
        ownerIntent: Int,
        done: (P2pResult) -> Unit,
    ) {
        connectAddresses += address
        connectIntents += ownerIntent
        done(if (failConnect) P2pResult.FAILED else P2pResult.OK)
    }

    override fun createGroup(channel: WifiP2pManager.Channel?, done: (P2pResult) -> Unit) {
        createGroupCalls++
        done(P2pResult.OK)
    }

    override fun removeGroup(channel: WifiP2pManager.Channel?) {
        removeGroupCalls++
    }

    override fun bind(port: Int): ServerSocket? {
        if (failBind) return null
        boundPort = port
        return ServerSocket().apply { bind(InetSocketAddress(port)) }
    }

    override fun connectSocket(host: String, port: Int, timeoutMs: Int): Socket? = socketToReturn

    /** Deliver a peer list to the most recent request. */
    fun answerPeers(addresses: List<String>) {
        peerCallbacks.last()(addresses)
    }

    fun answerInfo(formed: Boolean, owner: Boolean, host: String?) {
        infoCallbacks.last()(GroupInfo(formed, owner, host))
    }
}
