package com.constrasset.infra

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Schema
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import com.constrasset.auth.UserStatus
import com.constrasset.auth.Users
import com.constrasset.rbac.OrgRoles
import com.constrasset.rbac.ProjectRoles
import com.constrasset.rbac.ProjectMembers
import com.constrasset.project.Projects
import com.constrasset.file.Folders
import com.constrasset.file.Files
import com.constrasset.tag.Tags
import com.constrasset.tag.FileTags
import com.constrasset.approval.ApprovalRecords
import com.constrasset.approval.Comments
import com.constrasset.recycle.RecycleBin
import com.constrasset.audit.OperationLogs
import java.time.Instant

/**
 * 数据库启动装配：连接池 + DDL 迁移 + Seed（§1.2 初始化Seed）。
 */
object DatabaseFactory {

    fun init(config: AppConfig) {
        Database.connect(
            url = config.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = config.dbUser,
            password = config.dbPassword,
        )
        TransactionManager.defaultDatabase?.let { db ->
            transaction(db) {
                if (config.debugSql) addLogger(StdOutSqlLogger)
                SchemaUtils.create(
                    OrgRoles, Users, ProjectRoles, Projects, ProjectMembers,
                    Folders, Files, Tags, FileTags, ApprovalRecords, Comments,
                    RecycleBin, OperationLogs,
                )
                // §1.2: 用户名唯一索引建在 LOWER(username) 上（存储保留大小写）
                exec("CREATE UNIQUE INDEX IF NOT EXISTS ux_users_lower_username ON users (LOWER(username))")
                // §4.1: 同文件夹下文件名唯一（NULL folderId=项目根，需 NULLS DISTINCT 语义 → PG15+ 支持）
                exec("CREATE UNIQUE INDEX IF NOT EXISTS ux_files_folder_name ON files (project_id, COALESCE(parent_folder_id, 0), name)")
                // 回收站查询过滤 expire_at > NOW()（Critical Note #8）
                exec("CREATE INDEX IF NOT EXISTS ix_recycle_alive ON recycle_bin (restored, expire_at) WHERE restored = false")
            }
        }
        seed(config)
    }

    /**
     * 启动时检查：若无 SUPER_ADMIN 用户 → 创建 admin/admin123 + SUPER_ADMIN角色。
     * ADMIN 角色不可删除/修改，仅可查看（is_builtin=true 由业务层强制）。
     */
    private fun seed(config: AppConfig) {
        transaction {
            // 内置组织角色 seed（SUPER_ADMIN / ADMIN / MEMBER）
            fun ensureOrgRole(name: String, desc: String, perms: List<String>): Long {
                OrgRoles.selectAll().where { OrgRoles.name eq name }.firstOrNull()
                    ?.let { return it[OrgRoles.id] }
                return OrgRoles.insert {
                    it[OrgRoles.name] = name
                    it[OrgRoles.description] = desc
                    it[OrgRoles.permissions] = perms
                    it[OrgRoles.isBuiltin] = true
                }[OrgRoles.id]
            }
            val superAdminRoleId = ensureOrgRole(BuiltinOrgRole.SUPER_ADMIN, "超级管理员，拥有全部权限", OrgPermission.ALL)
            BuiltinOrgRole.builtins().forEach { (roleName, perms) ->
                ensureOrgRole(roleName, "内置角色", perms)
            }
            // 内置项目角色 seed（项目管理员 / 普通成员）
            ProjectRoleSeed.builtins().forEach { (roleName, perms) ->
                val exists = ProjectRoles.selectAll().where { ProjectRoles.name eq roleName }.any()
                if (!exists) {
                    ProjectRoles.insert {
                        it[ProjectRoles.name] = roleName
                        it[ProjectRoles.description] = "内置项目角色"
                        it[ProjectRoles.permissions] = perms
                    }
                }
            }
            // §1.2：若无 SUPER_ADMIN 用户 → 创建 admin/admin123
            val hasSuperAdminUser = Users.selectAll().any {
                it[Users.orgRoleId] == superAdminRoleId && it[Users.status] != "DELETED"
            }
            if (!hasSuperAdminUser) {
                Users.insert {
                    it[Users.username] = "admin"
                    it[Users.nickname] = "系统管理员"
                    it[Users.passwordHash] = PasswordHasher.hash("admin123")
                    it[Users.orgRoleId] = superAdminRoleId
                    it[Users.status] = UserStatusColumn.ACTIVE_NAME
                    it[Users.deletedUsername] = null
                    it[Users.createdAt] = Instant.now()
                    it[Users.updatedAt] = Instant.now()
                }
            }
        }
    }
}

object BuiltinOrgRole {
    const val SUPER_ADMIN = "SUPER_ADMIN"
    const val ADMIN = "ADMIN"
    const val MEMBER = "MEMBER"

    fun builtins(): Map<String, List<String>> = mapOf(
        ADMIN to listOf(
            "USER_READ", "USER_CREATE", "USER_UPDATE", "USER_RESET_PASSWORD",
            "USER_INHERIT_ASSIGN", "USER_INHERIT_CLAIM",
            "ROLE_READ", "PROJECT_CREATE",
            "TAG_VIEW", "TAG_MANAGE", "LOG_READ",
        ),
        MEMBER to listOf(
            "PROJECT_CREATE", "TAG_VIEW",
        ),
    )
}

object ProjectRoleSeed {
    const val PROJECT_ADMIN = "项目管理员"
    const val PROJECT_MEMBER = "普通成员"

    fun builtins(): Map<String, List<String>> = mapOf(
        PROJECT_ADMIN to ProjectPermission.ALL,
        PROJECT_MEMBER to listOf(
            "FILE_VIEW", "FILE_DOWNLOAD", "MEDIA_UPLOAD", "FILE_TAG", "FILE_VIEW_DETAIL", "FILE_COMMENT", "META_VIEW",
        ),
    )
}

object OrgPermission {
    val ALL = listOf(
        "USER_READ", "USER_CREATE", "USER_UPDATE", "USER_DELETE", "USER_RESET_PASSWORD",
        "USER_INHERIT_ASSIGN", "USER_INHERIT_CLAIM",
        "ROLE_READ", "ROLE_CREATE", "ROLE_EDIT_PERMISSION", "ROLE_DELETE",
        "PROJECT_CREATE",
        "TAG_VIEW", "TAG_MANAGE",
        "LOG_READ",
    )
}

object ProjectPermission {
    val ALL = listOf(
        "FILE_VIEW", "FILE_DOWNLOAD", "MEDIA_UPLOAD", "FILE_TAG", "FILE_APPROVE",
        "FILE_VIEW_DETAIL", "FILE_COMMENT", "META_VIEW",
        "PROJECT_ARCHIVE", "PROJECT_DELETE", "MEMBER_MANAGE",
    )
}

object UserStatusColumn {
    const val ACTIVE_NAME = "ACTIVE"
    const val DISABLED_NAME = "DISABLED"
    const val DELETED_NAME = "DELETED"
}

data class AppConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val debugSql: Boolean,
    val redisHost: String,
    val redisPort: Int,
    val minioEndpoint: String,
    val minioAccessKey: String,
    val minioSecretKey: String,
    val minioBucket: String,
    val jwtSecret: String,
    val accessTokenTtlSeconds: Long,   // 15min
    val refreshTokenTtlSeconds: Long,  // 7d
)
