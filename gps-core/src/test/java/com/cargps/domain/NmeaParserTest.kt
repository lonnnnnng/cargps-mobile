package com.cargps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaParserTest {
    @Test
    fun `gga exposes fix quality satellites hdop and altitude`() {
        val frame = NmeaParser.parse(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47",
        )

        assertEquals(NmeaSentenceType.GGA, frame?.type)
        assertEquals(1, frame?.fixQuality)
        assertEquals(8, frame?.satellitesUsed)
        assertEquals(0.9, frame?.hdop ?: 0.0, 0.001)
        assertEquals(545.4, frame?.altitudeMeters ?: 0.0, 0.001)
        assertEquals(48.1173, frame?.latitude ?: 0.0, 0.00001)
        assertEquals(11.516666, frame?.longitude ?: 0.0, 0.00001)
    }

    @Test
    fun `rmc exposes movement status speed and bearing`() {
        val frame = NmeaParser.parse(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A",
        )

        assertEquals(NmeaSentenceType.RMC, frame?.type)
        assertEquals(true, frame?.validFix)
        assertEquals(22.4 * 0.514444, frame?.speedMps ?: 0.0, 0.0001)
        assertEquals(84.4, frame?.bearingDegrees ?: 0.0, 0.001)
    }

    @Test
    fun `bad checksum is ignored without crashing`() {
        assertNull(
            NmeaParser.parse(
                "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00",
            ),
        )
    }
}
