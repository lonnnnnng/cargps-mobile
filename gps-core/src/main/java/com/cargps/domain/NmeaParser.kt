package com.cargps.domain

enum class NmeaSentenceType {
    GGA,
    RMC,
    GSA,
    GSV,
    VTG,
    ZDA,
}

data class NmeaFrame(
    val type: NmeaSentenceType,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val fixQuality: Int? = null,
    val validFix: Boolean? = null,
    val satellitesUsed: Int? = null,
    val satellitesInView: Int? = null,
    val hdop: Double? = null,
    val vdop: Double? = null,
    val pdop: Double? = null,
    val altitudeMeters: Double? = null,
    val speedMps: Double? = null,
    val bearingDegrees: Double? = null,
)

object NmeaParser {
    private const val KNOTS_TO_METERS_PER_SECOND = 0.514444

    fun parse(sentence: String): NmeaFrame? {
        val normalized = sentence.trim().removeSuffix("\r").removeSuffix("\n")
        if (!normalized.startsWith("$") || normalized.length < 7) return null

        val checksumSeparator = normalized.indexOf('*')
        if (checksumSeparator <= 1 || checksumSeparator + 3 > normalized.length) return null
        val body = normalized.substring(1, checksumSeparator)
        val expectedChecksum = normalized.substring(checksumSeparator + 1).take(2).toIntOrNull(16)
            ?: return null
        val actualChecksum = body.fold(0) { checksum, character -> checksum xor character.code }
        if (actualChecksum != expectedChecksum) return null

        val fields = body.split(',')
        val talkerAndType = fields.firstOrNull() ?: return null
        if (talkerAndType.length < 5) return null
        val type = runCatching { NmeaSentenceType.valueOf(talkerAndType.takeLast(3)) }.getOrNull()
            ?: return null

        return when (type) {
            NmeaSentenceType.GGA -> parseGga(fields)
            NmeaSentenceType.RMC -> parseRmc(fields)
            NmeaSentenceType.GSA -> parseGsa(fields)
            NmeaSentenceType.GSV -> parseGsv(fields)
            NmeaSentenceType.VTG -> parseVtg(fields)
            NmeaSentenceType.ZDA -> NmeaFrame(type)
        }
    }

    private fun parseGga(fields: List<String>): NmeaFrame = NmeaFrame(
        type = NmeaSentenceType.GGA,
        latitude = parseCoordinate(fields.getOrNull(2), fields.getOrNull(3), isLatitude = true),
        longitude = parseCoordinate(fields.getOrNull(4), fields.getOrNull(5), isLatitude = false),
        fixQuality = fields.getOrNull(6)?.toIntOrNull(),
        satellitesUsed = fields.getOrNull(7)?.toIntOrNull(),
        hdop = fields.getOrNull(8)?.toDoubleOrNull(),
        altitudeMeters = fields.getOrNull(9)?.toDoubleOrNull(),
    )

    private fun parseRmc(fields: List<String>): NmeaFrame = NmeaFrame(
        type = NmeaSentenceType.RMC,
        validFix = fields.getOrNull(2) == "A",
        latitude = parseCoordinate(fields.getOrNull(3), fields.getOrNull(4), isLatitude = true),
        longitude = parseCoordinate(fields.getOrNull(5), fields.getOrNull(6), isLatitude = false),
        speedMps = fields.getOrNull(7)?.toDoubleOrNull()?.times(KNOTS_TO_METERS_PER_SECOND),
        bearingDegrees = fields.getOrNull(8)?.toDoubleOrNull(),
    )

    private fun parseGsa(fields: List<String>): NmeaFrame = NmeaFrame(
        type = NmeaSentenceType.GSA,
        pdop = fields.getOrNull(15)?.toDoubleOrNull(),
        hdop = fields.getOrNull(16)?.toDoubleOrNull(),
        vdop = fields.getOrNull(17)?.toDoubleOrNull(),
    )

    private fun parseGsv(fields: List<String>): NmeaFrame = NmeaFrame(
        type = NmeaSentenceType.GSV,
        satellitesInView = fields.getOrNull(3)?.toIntOrNull(),
    )

    private fun parseVtg(fields: List<String>): NmeaFrame = NmeaFrame(
        type = NmeaSentenceType.VTG,
        bearingDegrees = fields.getOrNull(1)?.toDoubleOrNull(),
        speedMps = fields.getOrNull(5)?.toDoubleOrNull()?.times(KNOTS_TO_METERS_PER_SECOND),
    )

    private fun parseCoordinate(value: String?, hemisphere: String?, isLatitude: Boolean): Double? {
        val raw = value?.toDoubleOrNull() ?: return null
        val degreeDigits = if (isLatitude) 2 else 3
        val degrees = value.take(degreeDigits).toDoubleOrNull() ?: return null
        val minutes = raw - degrees * 100.0
        if (minutes !in 0.0..<60.0) return null
        val coordinate = degrees + minutes / 60.0
        return when (hemisphere) {
            "S", "W" -> -coordinate
            "N", "E" -> coordinate
            else -> null
        }
    }
}
