package com.cargps

import com.cargps.domain.NmeaFrame
import com.cargps.domain.NmeaSentenceType
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationEngineNmeaMergeTest {
    @Test
    fun `同一刷新窗口保留不同报文提供的诊断字段`() {
        val gga = NmeaFrame(
            type = NmeaSentenceType.GGA,
            satellitesUsed = 9,
            hdop = 0.8,
            altitudeMeters = 42.0,
        )
        val gsa = NmeaFrame(
            type = NmeaSentenceType.GSA,
            pdop = 1.2,
            vdop = 1.5,
        )

        val merged = mergeNmeaFrames(gga, gsa)

        assertEquals(NmeaSentenceType.GSA, merged.type)
        assertEquals(9, merged.satellitesUsed)
        assertEquals(0.8, merged.hdop ?: 0.0, 0.001)
        assertEquals(1.2, merged.pdop ?: 0.0, 0.001)
        assertEquals(1.5, merged.vdop ?: 0.0, 0.001)
        assertEquals(42.0, merged.altitudeMeters ?: 0.0, 0.001)
    }
}
