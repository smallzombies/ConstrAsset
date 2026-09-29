package com.constrasset.audit

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §8 operation_logs 表。
 * 写入方式：AOP切面/插件自动拦截，禁止手动调用（见 AuditLogPlugin）。
 * metadata JSONB —— 额外上下文快照；禁止记录密码、Token等敏感信息（Critical Note #9）。
 */
object OperationLogs : Table("operation_logs") {
    val id = long("id").autoIncrement()                        // BIGSERIAL
    val operatorId = long("operator_id")                       // NOT NULL（不加FK：用户注销后日志仍需保留）
    val action = varchar("action", 50)                         // USER_DELETE, FILE_APPROVE, etc.
    val projectId = long("project_id").nullable()
    val targetType = varchar("target_type", 20).nullable()     // USER/PROJECT/FILE/TAG
    val targetId = long("target_id").nullable()
    val ipAddress = varchar("ip_address", 45)                  // IPv6 最大45字符
    val metadata = text("metadata").nullable()                 // JSONB（DDL中 ALTER 为 jsonb）
    val createdAt = timestamptz("created_at").clientDefault { java.time.Instant.now() }

    override val primaryKey = PrimaryKey(id)
}

/** 操作动作常量（§8 示例 + §9 端点对应动作） */
object LogActions {
    const val USER_CREATE = "USER_CREATE"
    const val USER_UPDATE = "USER_UPDATE"
    const val USER_DELETE = "USER_DELETE"          // 注销
    const val USER_RESET_PASSWORD = "USER_RESET_PASSWORD"
    const val USER_INHERIT_ASSIGN = "USER_INHERIT_ASSIGN"
    const val USER_INHERIT_CLAIM = "USER_INHERIT_CLAIM"
    const val USER_LOGIN = "USER_LOGIN"
    const val USER_LOGOUT = "USER_LOGOUT"
    const val PROJECT_CREATE = "PROJECT_CREATE"
    const val PROJECT_ARCHIVE = "PROJECT_ARCHIVE"
    const val PROJECT_DELETE = "PROJECT_DELETE"
    const val PROJECT_FREEZE = "PROJECT_FREEZE"
    const val PROJECT_UNFREEZE = "PROJECT_UNFREEZE"
    const val PROJECT_TRANSFER_OWNER = "PROJECT_TRANSFER_OWNER"
    const val MEMBER_ADD = "MEMBER_ADD"
    const val GUEST_CREATE = "GUEST_CREATE"
    const val FILE_UPLOAD = "FILE_UPLOAD"
    const val FILE_DELETE = "FILE_DELETE"
    const val FILE_APPROVE = "FILE_APPROVE"
    const val FILE_TAG = "FILE_TAG"
    const val FILE_PACK = "FILE_PACK"
    const val TAG_CREATE = "TAG_CREATE"
    const val TAG_UPDATE = "TAG_UPDATE"
    const val TAG_DELETE = "TAG_DELETE"
    const val RECYCLE_RESTORE = "RECYCLE_RESTORE"
    const val RECYCLE_PURGE = "RECYCLE_PURGE"
    const val RECYCLE_CLEAR_ALL = "RECYCLE_CLEAR_ALL"
}

object TargetTypes {
    const val USER = "USER"
    const val PROJECT = "PROJECT"
    const val FILE = "FILE"
    const val TAG = "TAG"
    const val FOLDER = "FOLDER"
    const val ROLE = "ROLE"
}
