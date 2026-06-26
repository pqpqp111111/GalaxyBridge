package com.example.galaxybridge.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncResponse(
    val schema: Int = 1,
    val generatedAt: Long,
    val sleep: List<SleepDto>
)

@Serializable
data class SleepDto(
    val id: String,
    val start: Long,
    val end: Long,
    val source: String,
    val startISO: String,
    val endISO: String,
    val stages: List<StageDto>
)

@Serializable
data class StageDto(
    val start: Long,
    val end: Long,
    val stage: Int,
    val startISO: String,
    val endISO: String
)
