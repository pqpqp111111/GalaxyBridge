package com.example.galaxybridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.galaxybridge.dto.SleepDto
import com.example.galaxybridge.dto.StageDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HealthConnectReader(private val context: Context) {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val zone: ZoneId = ZoneId.systemDefault()
    private val tsFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
    private val isoFmt: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(readPermissions)

    suspend fun missingPermissions(): Set<String> =
        readPermissions - client.permissionController.getGrantedPermissions()

    companion object {
        fun availability(context: Context): Int =
            HealthConnectClient.getSdkStatus(context)
    }

    suspend fun dumpInventory(daysBack: Long = 14): String {
        val end = Instant.now()
        val start = end.minus(daysBack, ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        val sb = StringBuilder()
        sb.appendLine("== Health Connect 库存 (最近 ${daysBack} 天) ==")

        suspend fun <T : Record> count(cls: kotlin.reflect.KClass<T>, label: String) {
            val n = runCatching {
                client.readRecords(ReadRecordsRequest(cls, range)).records.size
            }.getOrElse { -1 }
            sb.appendLine("%-14s : %s".format(label, if (n < 0) "无权限/错误" else "$n 条"))
        }

        count(SleepSessionRecord::class, "睡眠")
        count(HeartRateRecord::class, "心率")
        count(RestingHeartRateRecord::class, "静息心率")
        count(HeartRateVariabilityRmssdRecord::class, "HRV")
        count(StepsRecord::class, "步数")
        count(OxygenSaturationRecord::class, "血氧")
        count(RespiratoryRateRecord::class, "呼吸频率")
        count(SkinTemperatureRecord::class, "皮肤温度")
        count(ExerciseSessionRecord::class, "运动")
        count(TotalCaloriesBurnedRecord::class, "总热量")

        sb.appendLine()
        sb.append(dumpLatestSleep(range))
        return sb.toString()
    }

    private suspend fun dumpLatestSleep(range: TimeRangeFilter): String {
        val sb = StringBuilder()
        val sessions = runCatching {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
        }.getOrElse {
            return "睡眠读取失败：${it.message}\n"
        }
        if (sessions.isEmpty()) {
            return "睡眠：0 条 —— 源里没有睡眠。\n"
        }
        val last = sessions.maxByOrNull { it.startTime }!!
        sb.appendLine("最近一夜睡眠：${tsFmt.format(last.startTime)} ~ ${tsFmt.format(last.endTime)}")
        sb.appendLine("来源：${last.metadata.dataOrigin.packageName}")
        if (last.stages.isEmpty()) {
            sb.appendLine("⚠ 无分段")
        } else {
            sb.appendLine("分段 ${last.stages.size} 段：")
            for (s in last.stages) {
                sb.appendLine("  ${tsFmt.format(s.startTime)}-${tsFmt.format(s.endTime)}  ${stageName(s.stage)}")
            }
        }
        return sb.toString()
    }

    private fun stageName(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "清醒"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "浅睡"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "深睡"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "睡眠(未细分)"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "离床"
        else -> "未知($stage)"
    }

    private fun iso(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(isoFmt)

    private fun normalizeStage(stage: Int): Int = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepSessionRecord.STAGE_TYPE_AWAKE
        SleepSessionRecord.STAGE_TYPE_UNKNOWN -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        else -> stage
    }

    suspend fun readSleepWithStages(start: Instant, end: Instant): List<SleepDto> {
        val req = ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end))
        return client.readRecords(req).records.map { rec ->
            val sessionStart = rec.startTime.toEpochMilli()
            val sessionEnd = rec.endTime.toEpochMilli()
            SleepDto(
                id = "hc:${rec.metadata.id}",
                start = sessionStart,
                end = sessionEnd,
                source = rec.metadata.dataOrigin.packageName,
                startISO = iso(sessionStart),
                endISO = iso(sessionEnd),
                stages = rec.stages
                    .filter { it.endTime.toEpochMilli() > it.startTime.toEpochMilli() }
                    .map { st ->
                        val stageStart = st.startTime.toEpochMilli()
                        val stageEnd = st.endTime.toEpochMilli()
                        StageDto(
                            start = stageStart,
                            end = stageEnd,
                            stage = normalizeStage(st.stage),
                            startISO = iso(stageStart),
                            endISO = iso(stageEnd)
                        )
                    }
            )
        }
    }
}
