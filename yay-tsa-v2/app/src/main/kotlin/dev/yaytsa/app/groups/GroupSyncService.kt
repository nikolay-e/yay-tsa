package dev.yaytsa.app.groups

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

data class PlaybackScheduleDto(
    val groupId: String,
    val trackId: String?,
    val anchorServerMs: Long,
    val anchorPositionMs: Long,
    val isPaused: Boolean,
    val scheduleEpoch: Long,
)

data class GroupMemberDto(
    val deviceId: String,
    val userId: String,
    val stale: Boolean,
    val reportedLatencyMs: Int,
)

data class GroupSnapshotDto(
    val id: String,
    val ownerId: String,
    val joinCode: String,
    val name: String,
    val controlMode: String,
    val schedule: PlaybackScheduleDto,
    val members: List<GroupMemberDto>,
)

data class CreateGroupResult(
    val id: String,
    val joinCode: String,
)

data class ScheduleUpdateResponseDto(
    val scheduleEpoch: Long,
    val schedule: PlaybackScheduleDto,
    val serverTimeMs: Long,
)

sealed interface ScheduleOutcome {
    data class Updated(
        val response: ScheduleUpdateResponseDto,
    ) : ScheduleOutcome

    data object Conflict : ScheduleOutcome

    data object NotFound : ScheduleOutcome
}

@Service
class GroupSyncService(
    private val jdbc: JdbcTemplate,
) {
    private val random = SecureRandom()

    companion object {
        private const val STALE_AFTER_MS = 30_000L
        private const val JOIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val JOIN_CODE_LENGTH = 6
        private val PAUSED_ACTIONS = setOf("PAUSE")
    }

    private fun newJoinCode(): String = (1..JOIN_CODE_LENGTH).map { JOIN_CODE_ALPHABET[random.nextInt(JOIN_CODE_ALPHABET.length)] }.joinToString("")

    @Transactional
    fun createGroup(
        ownerId: UUID,
        deviceId: String,
        name: String,
        trackId: String?,
    ): CreateGroupResult {
        val id = UUID.randomUUID()
        val joinCode = newJoinCode()
        val now = Instant.now().toEpochMilli()
        jdbc.update("INSERT INTO core_v2_groups.playback_group (id, owner_id, name, join_code) VALUES (?,?,?,?)", id, ownerId, name, joinCode)
        jdbc.update(
            "INSERT INTO core_v2_groups.playback_schedule (group_id, track_id, anchor_server_ms, anchor_position_ms, is_paused, schedule_epoch) " +
                "VALUES (?,?,?,0,TRUE,0)",
            id,
            trackId?.let(UUID::fromString),
            now,
        )
        addMember(id, ownerId, deviceId, 0)
        return CreateGroupResult(id.toString(), joinCode)
    }

    @Transactional
    fun joinGroup(
        userId: UUID,
        deviceId: String,
        joinCode: String,
    ): GroupSnapshotDto? {
        val groupId =
            jdbc
                .query("SELECT id FROM core_v2_groups.playback_group WHERE join_code = ?", { rs, _ -> rs.getObject("id", UUID::class.java) }, joinCode)
                .firstOrNull() ?: return null
        addMember(groupId, userId, deviceId, 0)
        return snapshot(groupId)
    }

    private fun addMember(
        groupId: UUID,
        userId: UUID,
        deviceId: String,
        rttMs: Int,
    ) {
        jdbc.update(
            "INSERT INTO core_v2_groups.playback_group_member (group_id, device_id, user_id, reported_latency_ms, last_seen_at) " +
                "VALUES (?,?,?,?, now()) " +
                "ON CONFLICT (group_id, device_id) DO UPDATE SET reported_latency_ms = EXCLUDED.reported_latency_ms, last_seen_at = now()",
            groupId,
            deviceId,
            userId,
            rttMs,
        )
    }

    fun snapshot(groupId: UUID): GroupSnapshotDto? {
        val group =
            jdbc
                .query(
                    "SELECT owner_id, name, join_code, control_mode FROM core_v2_groups.playback_group WHERE id = ?",
                    { rs, _ ->
                        GroupRow(
                            ownerId = rs.getObject("owner_id", UUID::class.java).toString(),
                            name = rs.getString("name"),
                            joinCode = rs.getString("join_code"),
                            controlMode = rs.getString("control_mode"),
                        )
                    },
                    groupId,
                ).firstOrNull() ?: return null
        val schedule = readSchedule(groupId) ?: return null
        val staleCutoff = Instant.now().toEpochMilli() - STALE_AFTER_MS
        val members =
            jdbc.query(
                "SELECT device_id, user_id, reported_latency_ms, last_seen_at FROM core_v2_groups.playback_group_member WHERE group_id = ?",
                { rs, _ ->
                    GroupMemberDto(
                        deviceId = rs.getString("device_id"),
                        userId = rs.getObject("user_id", UUID::class.java).toString(),
                        stale = rs.getTimestamp("last_seen_at").toInstant().toEpochMilli() < staleCutoff,
                        reportedLatencyMs = rs.getInt("reported_latency_ms"),
                    )
                },
                groupId,
            )
        return GroupSnapshotDto(
            groupId.toString(),
            group.ownerId,
            group.joinCode,
            group.name,
            group.controlMode,
            schedule,
            members,
        )
    }

    private data class GroupRow(
        val ownerId: String,
        val name: String,
        val joinCode: String,
        val controlMode: String,
    )

    private fun readSchedule(groupId: UUID): PlaybackScheduleDto? =
        jdbc
            .query(
                "SELECT track_id, anchor_server_ms, anchor_position_ms, is_paused, schedule_epoch " +
                    "FROM core_v2_groups.playback_schedule WHERE group_id = ?",
                { rs, _ ->
                    PlaybackScheduleDto(
                        groupId = groupId.toString(),
                        trackId = rs.getObject("track_id", UUID::class.java)?.toString(),
                        anchorServerMs = rs.getLong("anchor_server_ms"),
                        anchorPositionMs = rs.getLong("anchor_position_ms"),
                        isPaused = rs.getBoolean("is_paused"),
                        scheduleEpoch = rs.getLong("schedule_epoch"),
                    )
                },
                groupId,
            ).firstOrNull()

    @Transactional
    fun updateSchedule(
        groupId: UUID,
        expectedEpoch: Long,
        action: String,
        trackId: String?,
        positionMs: Long?,
        paused: Boolean?,
    ): ScheduleOutcome {
        val current = readSchedule(groupId) ?: return ScheduleOutcome.NotFound
        val now = Instant.now().toEpochMilli()
        val newPaused = paused ?: (action.uppercase() in PAUSED_ACTIONS)
        val newTrack = (trackId ?: current.trackId)?.let(UUID::fromString)
        val newPosition = positionMs ?: current.anchorPositionMs
        val updated =
            jdbc.update(
                "UPDATE core_v2_groups.playback_schedule " +
                    "SET track_id = ?, anchor_server_ms = ?, anchor_position_ms = ?, is_paused = ?, schedule_epoch = schedule_epoch + 1 " +
                    "WHERE group_id = ? AND schedule_epoch = ?",
                newTrack,
                now,
                newPosition,
                newPaused,
                groupId,
                expectedEpoch,
            )
        if (updated == 0) return ScheduleOutcome.Conflict
        val schedule = readSchedule(groupId) ?: return ScheduleOutcome.NotFound
        return ScheduleOutcome.Updated(ScheduleUpdateResponseDto(schedule.scheduleEpoch, schedule, now))
    }

    fun heartbeat(
        groupId: UUID,
        userId: UUID,
        deviceId: String,
        rttMs: Int?,
    ): Boolean {
        val rows =
            jdbc.update(
                "UPDATE core_v2_groups.playback_group_member SET last_seen_at = now(), reported_latency_ms = COALESCE(?, reported_latency_ms) " +
                    "WHERE group_id = ? AND device_id = ?",
                rttMs,
                groupId,
                deviceId,
            )
        if (rows == 0) addMember(groupId, userId, deviceId, rttMs ?: 0)
        return true
    }

    fun leave(
        groupId: UUID,
        deviceId: String,
    ) {
        jdbc.update("DELETE FROM core_v2_groups.playback_group_member WHERE group_id = ? AND device_id = ?", groupId, deviceId)
    }

    fun end(groupId: UUID) {
        jdbc.update("DELETE FROM core_v2_groups.playback_group WHERE id = ?", groupId)
    }

    fun groupExists(groupId: UUID): Boolean =
        jdbc
            .queryForObject("SELECT count(*) FROM core_v2_groups.playback_group WHERE id = ?", Long::class.java, groupId)
            ?.let { it > 0 } ?: false

    fun isOwner(
        groupId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc
            .queryForObject("SELECT count(*) FROM core_v2_groups.playback_group WHERE id = ? AND owner_id = ?", Long::class.java, groupId, userId)
            ?.let { it > 0 } ?: false

    @Transactional
    fun setControlMode(
        groupId: UUID,
        mode: String,
    ): Boolean {
        val normalized = if (mode == "everyone") "everyone" else "host"
        return jdbc.update("UPDATE core_v2_groups.playback_group SET control_mode = ? WHERE id = ?", normalized, groupId) > 0
    }

    fun controlMode(groupId: UUID): String? =
        jdbc
            .query(
                "SELECT control_mode FROM core_v2_groups.playback_group WHERE id = ?",
                { rs, _ -> rs.getString("control_mode") },
                groupId,
            ).firstOrNull()

    fun canControlSchedule(
        groupId: UUID,
        userId: UUID,
    ): Boolean =
        when (controlMode(groupId)) {
            "everyone" -> isMember(groupId, userId)
            else -> isOwner(groupId, userId)
        }

    fun isMember(
        groupId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc
            .queryForObject(
                "SELECT count(*) FROM core_v2_groups.playback_group_member WHERE group_id = ? AND user_id = ?",
                Long::class.java,
                groupId,
                userId,
            )?.let { it > 0 } ?: false

    fun memberDeviceOwner(
        groupId: UUID,
        deviceId: String,
    ): UUID? =
        jdbc
            .query(
                "SELECT user_id FROM core_v2_groups.playback_group_member WHERE group_id = ? AND device_id = ?",
                { rs, _ -> rs.getObject("user_id", UUID::class.java) },
                groupId,
                deviceId,
            ).firstOrNull()
}
