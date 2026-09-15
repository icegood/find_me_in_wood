package com.icegood.findmeinwood.app

import com.icegood.findmeinwood.core.crypto.Identity

/** Process-wide device identity (T3.5 will persist per-network identities in Room). */
object IdentityHolder {
    val keyPair = Identity.generate()
    val memberId: ByteArray = Identity.memberIdOf(keyPair)
}
