package com.constrasset.approval

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §6.1 approval_records 表。
 * result: APPROVED / REJECTED；remark 在 REJECTED 时 NOT NULL、APPROVED 时 NULL（§6.2 校验逻辑保证）。
 */
object ApprovalRecords : Table("approval_records") {
    val id = long("id").autoIncrement()                       // BIGSERIAL
    val fileId = long("file_id")
    val approverId = long("approver_id")
    val result = varchar("result", 10)                        // APPROVED / REJECTED
    val remark = text("remark").nullable()
    val createdAt = timestamptz("created_at").clientDefault { java.time.Instant.now() }

    override val primaryKey = PrimaryKey(id)
}

/**
 * §6.1 comments 表。content 纯文本，支持 @username；parent_comment_id 实现楼中楼。
 */
object Comments : Table("comments") {
    val id = long("id").autoIncrement()                       // BIGSERIAL
    val fileId = long("file_id")
    val userId = long("user_id")
    val content = text("content")                             // 纯文本, 支持@username
    val parentCommentId = long("parent_comment_id").nullable()
    val createdAt = timestamptz("created_at").clientDefault { java.time.Instant.now() }

    override val primaryKey = PrimaryKey(id)
}

enum class ApprovalResult { APPROVED, REJECTED }
