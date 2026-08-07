package com.cargps.domain

data class LocationSample(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Float? = null,
    val speedMps: Float? = null,
)
