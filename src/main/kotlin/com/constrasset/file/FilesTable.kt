package com.constrasset.file

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * 文件夹表（规格书 §4.1 引用了 parent_folder_id -> folders.id，故补充此支撑表）。
 */
object Folders : Table("folders") {
    val id = long("id").autoIncrement()          // BIGSERIAL
    val projectId = long("project_id")
    val parentFolderId = long("parent_folder_id").nullable()
    val name = varchar("name", 255)

    override val primaryKey = PrimaryKey(id)
}

/**
 * §4.1 files 表。
 * UNIQUE(project_id, parent_folder_id, name) —— 同文件夹下文件名唯一；
 * 实际 DDL 使用 COALESCE(parent_folder_id,0) 表达式索引（NULL 代表项目根目录）。
 */
object Files : Table("files") {
    val id = long("id").autoIncrement()                            // BIGSERIAL
    val projectId = long("project_id")
    val parentFolderId = long("parent_folder_id").nullable()       // FK -> folders.id
    val name = varchar("name", 255)
    val size = long("size")
    val mimeType = varchar("mime_type", 100)
    val minioKey = varchar("minio_key", 500)
    val thumbnailKey = varchar("thumbnail_key", 500).nullable()    // 缩略图MinIO路径
    val exifOrientation = integer("exif_orientation").default(0)   // EXIF方向值
    val uploadedBy = long("uploaded_by")
    val approvalStatus = varchar("approval_status", 10).default("PENDING") // PENDING/APPROVED/REJECTED
    val deleted = bool("deleted").default(false)                   // 软删除标记（§7回收站还原需翻转）
    val createdAt = timestamptz("created_at").clientDefault { java.time.Instant.now() }
    val updatedAt = timestamptz("updated_at").clientDefault { java.time.Instant.now() }

    override val primaryKey = PrimaryKey(id)
}

enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

data class FileEntity(
    val id: Long,
    val projectId: Long,
    val parentFolderId: Long?,
    val name: String,
    val size: Long,
    val mimeType: String,
    val minioKey: String,
    val thumbnailKey: String?,
    val exifOrientation: Int,
    val uploadedBy: Long,
    val approvalStatus: ApprovalStatus,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)

/** §4.2 上传重名处理协议 —— X-Conflict-Strategy 头解析 */
sealed class ConflictStrategy {
    data object ReplaceAll : ConflictStrategy()
    data object SkipAll : ConflictStrategy()
    data class ReplaceSet(val names: Set<String>) : ConflictStrategy()
    data object FailFast : ConflictStrategy() // 未携带策略头时的默认行为：遇冲突报 409

    companion object {
        private val NAME_LIST = Regex("^REPLACE:(.+)$")
        fun parse(header: String?): ConflictStrategy = when {
            header == null -> FailFast
            header.equals("REPLACE_ALL", true) -> ReplaceAll
            header.equals("SKIP_ALL", true) -> SkipAll
            NAME_LIST.matches(header) ->
                ReplaceSet(NAME_LIST.find(header)!!.groupValues[1].split(",").map { it.trim() }.toSet())
            else -> throw com.constrasset.common.BusinessException(
                "非法的 X-Conflict-Strategy: $header", httpStatus = 400, bizCode = 4001)
        }
    }
}
