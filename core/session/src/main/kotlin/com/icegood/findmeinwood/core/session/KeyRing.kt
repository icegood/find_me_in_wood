package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.crypto.EpochKeys

/**
 * The network's traffic keys: the base key (epoch 0, from the password) and the salt of
 * every later epoch this phone learned. Frames are sealed with the newest key; older ones
 * are kept only to recognise a member that missed a rekey and hand it the new one. A phone
 * that missed several rekeys jumps straight to the newest. Pure Kotlin, thread-safe.
 */
class KeyRing(
    private val base: ByteArray,
    /** Salt per epoch (epoch >= 1). */
    salts: Map<Int, ByteArray> = emptyMap(),
) {
    private val salts = java.util.TreeMap(salts.filterKeys { it >= 1 })
    private val keys = java.util.TreeMap<Int, ByteArray>().apply {
        put(0, base)
        this@KeyRing.salts.forEach { (e, s) -> put(e, EpochKeys.trafficKey(base, s)) }
    }

    @get:Synchronized
    val epoch: Int get() = keys.lastKey()

    @Synchronized
    fun current(): ByteArray = keys.lastEntry().value

    /** The salt of [epoch] (null for epoch 0 or one not known here). */
    @Synchronized
    fun salt(epoch: Int): ByteArray? = salts[epoch]

    @Synchronized
    fun salts(): Map<Int, ByteArray> = salts.toMap()

    /** Keys newest first, with their epochs. */
    @Synchronized
    fun newestFirst(): List<Pair<Int, ByteArray>> = keys.descendingMap().map { it.key to it.value }

    @Synchronized
    fun key(epoch: Int): ByteArray? = keys[epoch]

    /** Moves to [epoch] (newer than the current one) with its [salt]; false when not applied. */
    @Synchronized
    fun advance(epoch: Int, salt: ByteArray): Boolean {
        if (epoch <= this.epoch || salt.size != EpochKeys.SALT_LEN) return false
        salts[epoch] = salt
        keys[epoch] = EpochKeys.trafficKey(base, salt)
        return true
    }
}
