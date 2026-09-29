package com.constrasset.recycle

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §7.1 recycle_bin 表。
 * expire_at = deleted_at + 30 days；查询时始终加 `expire_at > NOW()` 过滤（Critical Note #8）。
 */
object RecycleBin : Table("recycle_bin") {
    val id = long("id").autoIncrement()                        // BIGSERIAL
    val sourceType = varchar("source_type", 10)                // PROJECT / FILE
    val sourceId = long("source_id")
    val originalName = varchar("original_name", 255)
    val deletedBy = long("deleted_by")
    val deletedAt = timestamptz("deleted_at").clientDefault { java.time.Instant.now() }
    val expireAt = timestamptz("expire_at")                    // deleted_at + 30 days
    val restored = bool("restored").default(false)

    override val primaryKey = PrimaryKey(id)
}

enum class RecycleSourceType { PROJECT, FILE }
