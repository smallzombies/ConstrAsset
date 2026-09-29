package com.constrasset.auth

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §1.1 users 表。
 * - id: BIGSERIAL 主键（Global Constraint）
 * - username 唯一性：存储保留大小写；唯一索引建在 LOWER(username)（见 DatabaseFactory DDL）
 * - status: ACTIVE / DISABLED / DELETED
 * - deletedUsername: 注销后存 "{name}_{epochMs}"
 */
object Users : Table("users") {
    val id = long("id").autoIncrement()                       // BIGSERIAL
    // 输入校验限制6-20位；物理列放宽到64以容纳注销后的 "{name}_{epochMs}"（§1.2）
    val username = varchar("username", 64)
    val nickname = varchar("nickname", 50)                    // 任意字符, 1-50位
    val passwordHash = varchar("password_hash", 255)          // Argon2id hash
    val orgRoleId = long("org_role_id") // FK -> org_roles.id
    val status = varchar("status", 10)                        // UserStatus.name
    val deletedUsername = varchar("deleted_username", 64).nullable()
    val createdAt = timestamptz("created_at")                 // TIMESTAMPTZ, UTC
    val updatedAt = timestamptz("updated_at")

    override val primaryKey = PrimaryKey(id)
}

enum class UserStatus { ACTIVE, DISABLED, DELETED }

/**
 * §1.1 领域实体（与表中字段一一对应）。
 */
data class UserEntity(
    val id: Long,
    val username: String,
    val nickname: String,
    val passwordHash: String,
    val orgRoleId: Long,
    val status: UserStatus,
    val deletedUsername: String?,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)
