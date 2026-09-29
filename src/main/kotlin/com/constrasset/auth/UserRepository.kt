package com.constrasset.auth

import com.constrasset.infra.ilike
import com.constrasset.rbac.OrgRoles
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * §1 用户仓库。唯一性规则：LOWER(username) 索引（DDL见DatabaseFactory）。
 * 列表接口 JOIN org_roles，禁止 N+1（Critical Note #1）。
 */
class UserRepository {

    private fun ResultRow.toEntity() = UserEntity(
        id = this[Users.id],
        username = this[Users.username],
        nickname = this[Users.nickname],
        passwordHash = this[Users.passwordHash],
        orgRoleId = this[Users.orgRoleId],
        status = UserStatus.valueOf(this[Users.status]),
        deletedUsername = this[Users.deletedUsername],
        createdAt = this[Users.createdAt],
        updatedAt = this[Users.updatedAt],
    )

    fun findById(id: Long): UserEntity? = transaction {
        Users.selectAll().where { Users.id eq id }.map { it.toEntity() }.firstOrNull()
    }

    /** 批量 IN 查询（Critical Note #1） */
    fun findByIds(ids: Collection<Long>): List<UserEntity> {
        if (ids.isEmpty()) return emptyList()
        return transaction { Users.selectAll().where { Users.id inList ids.toList() }.map { it.toEntity() } }
    }

    /** 用户名不区分大小写查找（登录/查重） */
    fun findByUsernameIgnoreCase(username: String): UserEntity? = transaction {
        Users.selectAll().where { Users.username.lowerCase() eq username.lowercase() }
            .map { it.toEntity() }.firstOrNull()
    }

    fun existsUsernameIgnoreCase(username: String, excludeId: Long? = null): Boolean = transaction {
        Users.selectAll().where { Users.username.lowerCase() eq username.lowercase() }
            .any { excludeId == null || it[Users.id] != excludeId }
    }

    fun orgRoleName(userId: Long): String = transaction {
        (Users innerJoin OrgRoles)
            .selectAll().where { (Users.id eq userId) and (Users.orgRoleId eq OrgRoles.id) }
            .firstOrNull()?.get(OrgRoles.name) ?: ""
    }

    data class UserListItem(
        val id: Long, val username: String, val nickname: String,
        val orgRoleName: String, val status: String, val createdAt: Instant,
    )

    fun listPaged(page: Int, pageSize: Int, keyword: String?, status: String?): Pair<List<UserListItem>, Long> =
        transaction {
            val conds = mutableListOf<Op<Boolean>>()
            if (!keyword.isNullOrBlank()) {
                val kw = "%${keyword.lowercase()}%"
                conds += (Users.username ilike kw) or (Users.nickname ilike kw)
            }
            if (!status.isNullOrBlank()) {
                conds += with(SqlExpressionBuilder) { Users.status eq status }
            }
            fun filtered() = (Users innerJoin OrgRoles).selectAll().where {
                conds.reduceOrNull { a, b -> a and b } ?: Op.TRUE
            }
            val total = filtered().count()
            val items = filtered()
                .orderBy(Users.id to SortOrder.DESC)
                .limit(pageSize, offset = (page - 1).toLong() * pageSize)
                .map {
                    UserListItem(
                        id = it[Users.id], username = it[Users.username], nickname = it[Users.nickname],
                        orgRoleName = it[OrgRoles.name], status = it[Users.status], createdAt = it[Users.createdAt],
                    )
                }
            items to total
        }
    fun create(username: String, nickname: String, passwordHash: String, orgRoleId: Long): Long = transaction {
        val now = Instant.now()
        Users.insert {
            it[Users.username] = username
            it[Users.nickname] = nickname
            it[Users.passwordHash] = passwordHash
            it[Users.orgRoleId] = orgRoleId
            it[Users.status] = UserStatus.ACTIVE.name
            it[Users.deletedUsername] = null
            it[createdAt] = now
            it[updatedAt] = now
        }[Users.id]
    }

    fun updateNickname(userId: Long, nickname: String) = transaction {
        Users.update({ Users.id eq userId }) {
            it[Users.nickname] = nickname
            it[Users.updatedAt] = Instant.now()
        }
    }

    fun updateOrgRole(userId: Long, orgRoleId: Long) = transaction {
        Users.update({ Users.id eq userId }) {
            it[Users.orgRoleId] = orgRoleId
            it[Users.updatedAt] = Instant.now()
        }
    }

    fun updatePasswordHash(userId: Long, hash: String) = transaction {
        Users.update({ Users.id eq userId }) {
            it[Users.passwordHash] = hash
            it[Users.updatedAt] = Instant.now()
        }
    }

    fun updateStatus(userId: Long, status: UserStatus) = transaction {
        Users.update({ Users.id eq userId }) {
            it[Users.status] = status.name
            it[Users.updatedAt] = Instant.now()
        }
    }

    /**
     * §1.2 注销流程①：username 改为 `{name}_{epochMs}`，status=DELETED，记录 deletedUsername。
     * ⚠️ 必须在外层 `transaction {}` 内调用（与日志/冻结同事务，Critical Note #2）。
     */
    fun renameForDeletion(userId: Long, originalUsername: String): String {
        val renamed = "${originalUsername}_${System.currentTimeMillis()}"
        Users.update({ Users.id eq userId }) {
            it[Users.username] = renamed      // users.username 实际为 VARCHAR(64)，容纳后缀
            it[Users.deletedUsername] = originalUsername
            it[Users.status] = UserStatus.DELETED.name
            it[Users.updatedAt] = Instant.now()
        }
        return renamed
    }

    fun allActive(): List<UserEntity> = transaction {
        Users.selectAll().where { Users.status eq UserStatus.ACTIVE.name }.map { it.toEntity() }
    }
}
