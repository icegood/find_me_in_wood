package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.GnssFix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

data class BeaconCommand(
    val fix: GnssFix?,
    val atMs: Long,
)

/**
 * Emits a send command on every GNSS fix plus a keep-alive every [keepAliveMs]
 * (FR-5.2). Pure Kotlin; clock injectable for tests.
 */
class BeaconScheduler(
    private val keepAliveMs: Long = 60_000,
    private val nowMs: () -> Long,
) {
    fun commands(fixes: Flow<GnssFix>): Flow<BeaconCommand> {
        val onFix = fixes.map { BeaconCommand(fix = it, atMs = nowMs()) }
        val keepAlive = flow {
            while (true) {
                delay(keepAliveMs)
                emit(BeaconCommand(fix = null, atMs = nowMs()))
            }
        }
        return merge(onFix, keepAlive)
    }
}
