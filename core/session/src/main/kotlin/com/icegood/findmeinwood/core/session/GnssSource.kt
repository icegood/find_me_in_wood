package com.icegood.findmeinwood.core.session

import com.icegood.findmeinwood.core.model.GnssFix
import kotlinx.coroutines.flow.Flow

/** GNSS fix source (FR-4.1); Android impl in app module, fake in tests. */
interface GnssSource {
    fun fixes(): Flow<GnssFix>
}
