package com.constrasset.rbac

import com.constrasset.infra.textArray
import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §2.1 org_roles 组织角色表。
 * permissions TEXT[] NOT NULL DEFAULT '{}' —— 权限标识数组。
 * is_builtin=true 的角色（SUPER_ADMIN/ADMIN）不可删除/修改，仅可查看。
 */
object OrgRoles : Table("org_roles") {
    val id = long("id").autoIncrement()               // BIGSERIAL
    val name = varchar("name", 50).uniqueIndex()      // VARCHAR(50) NOT NULL UNIQUE
    val description = text("description").nullable()
    val permissions = textArray("permissions").default(emptyList())
    val isBuiltin = bool("is_builtin").default(false)

    override val primaryKey = PrimaryKey(id)
}

/**
 * §2.2 project_roles 项目角色表。
 */
object ProjectRoles : Table("project_roles") {
    val id = long("id").autoIncrement()               // BIGSERIAL
    val name = varchar("name", 50)
    val description = text("description").nullable()
    val permissions = textArray("permissions").default(emptyList())

    override val primaryKey = PrimaryKey(id)
}

/**
 * §2.2 project_members 用户-项目-角色关联表。
 * PRIMARY KEY (user_id, project_id)，is_owner 标识 OWNER。
 */
object ProjectMembers : Table("project_members") {
    val userId = long("user_id")
    val projectId = long("project_id")
    val projectRoleId = long("project_role_id")
    val isOwner = bool("is_owner").default(false)
    val joinedAt = timestamptz("joined_at").clientDefault { java.time.Instant.now() }

    override val primaryKey = PrimaryKey(userId, projectId)
}

// ---------- 权限枚举常量（§2.1 / §2.2 原文清单） ----------
object OrgPermissions {
    const val USER_READ = "USER_READ"
    const val USER_CREATE = "USER_CREATE"
    const val USER_UPDATE = "USER_UPDATE"
    const val USER_DELETE = "USER_DELETE"
    const val USER_RESET_PASSWORD = "USER_RESET_PASSWORD"
    const val USER_INHERIT_ASSIGN = "USER_INHERIT_ASSIGN"
    const val USER_INHERIT_CLAIM = "USER_INHERIT_CLAIM"
    const val ROLE_READ = "ROLE_READ"
    const val ROLE_CREATE = "ROLE_CREATE"
    const val ROLE_EDIT_PERMISSION = "ROLE_EDIT_PERMISSION"
    const val ROLE_DELETE = "ROLE_DELETE"
    const val PROJECT_CREATE = "PROJECT_CREATE"
    const val TAG_VIEW = "TAG_VIEW"
    const val TAG_MANAGE = "TAG_MANAGE"
    const val LOG_READ = "LOG_READ"

    val ALL = setOf(
        USER_READ, USER_CREATE, USER_UPDATE, USER_DELETE, USER_RESET_PASSWORD,
        USER_INHERIT_ASSIGN, USER_INHERIT_CLAIM,
        ROLE_READ, ROLE_CREATE, ROLE_EDIT_PERMISSION, ROLE_DELETE,
        PROJECT_CREATE, TAG_VIEW, TAG_MANAGE, LOG_READ,
    )
}

object ProjectPermissions {
    const val FILE_VIEW = "FILE_VIEW"
    const val FILE_DOWNLOAD = "FILE_DOWNLOAD"
    const val MEDIA_UPLOAD = "MEDIA_UPLOAD"
    const val FILE_TAG = "FILE_TAG"
    const val FILE_APPROVE = "FILE_APPROVE"
    const val FILE_VIEW_DETAIL = "FILE_VIEW_DETAIL"
    const val FILE_COMMENT = "FILE_COMMENT"
    const val META_VIEW = "META_VIEW"
    const val PROJECT_ARCHIVE = "PROJECT_ARCHIVE"
    const val PROJECT_DELETE = "PROJECT_DELETE"
    const val MEMBER_MANAGE = "MEMBER_MANAGE"

    val ALL = setOf(
        FILE_VIEW, FILE_DOWNLOAD, MEDIA_UPLOAD, FILE_TAG, FILE_APPROVE,
        FILE_VIEW_DETAIL, FILE_COMMENT, META_VIEW,
        PROJECT_ARCHIVE, PROJECT_DELETE, MEMBER_MANAGE,
    )
}
