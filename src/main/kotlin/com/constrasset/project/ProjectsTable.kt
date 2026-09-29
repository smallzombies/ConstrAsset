package com.constrasset.project

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §3.1 projects 表。
 * status: ACTIVE / ARCHIVED / FROZEN / DELETED
 * code: 项目编号 ^[a-zA-Z0-9]{2,50}$ UNIQUE
 */
object Projects : Table("projects") {
    val id = long("id").autoIncrement()                        // BIGSERIAL
    val name = varchar("name", 50)
    val code = varchar("code", 50).uniqueIndex()               // VARCHAR(50) NOT NULL UNIQUE
    val ownerUserId = long("owner_user_id")
    val status = varchar("status", 10).default("ACTIVE")       // ProjectStatus.name
    val createdBy = long("created_by")
    val createdAt = timestamptz("created_at").clientDefault { java.time.Instant.now() }
    val updatedAt = timestamptz("updated_at").clientDefault { java.time.Instant.now() }

    override val primaryKey = PrimaryKey(id)
}

enum class ProjectStatus { ACTIVE, ARCHIVED, FROZEN, DELETED }

/**
 * §3.2 冻结规则：status=FROZEN 时所有写操作接口返回 403。
 * 在权限中间件之后、业务处理之前统一调用。
 */
fun ProjectStatus.guardWritable(action: String) {
    if (this == ProjectStatus.FROZEN) {
        throw com.constrasset.common.BusinessException(
            "项目已冻结（负责人注销触发继承流程），禁止执行: $action",
            httpStatus = 403, bizCode = 3001,
        )
    }
    if (this == ProjectStatus.DELETED) {
        throw com.constrasset.common.BusinessException("项目已在回收站", httpStatus = 410, bizCode = 3002)
    }
}
