package com.constrasset.project

import com.constrasset.auth.Users
import com.constrasset.rbac.ProjectMembers
import com.constrasset.rbac.ProjectRoles
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * §3 项目仓库。多表写入操作（创建/转移/冻结）必须在外层 `transaction {}` 内调用 *InTx 方法
 * （Critical Note #2）。
 */
class ProjectRepository {

    @Serializable
    data class ProjectView(
        val id: Long,
        val name: String,
        val code: String,
        val ownerUserId: Long,
        val status: String,
        val createdBy: Long,
        val createdAt: String,
        val updatedAt: String,
    )

    private fun ResultRow.toView() = ProjectView(
        id = this[Projects.id],
        name = this[Projects.name],
        code = this[Projects.code],
        ownerUserId = this[Projects.ownerUserId],
        status = this[Projects.status],
        createdBy = this[Projects.createdBy],
        createdAt = this[Projects.createdAt].toString(),
        updatedAt = this[Projects.updatedAt].toString(),
    )

    fun findById(id: Long): ProjectView? = transaction {
        Projects.selectAll().where { Projects.id eq id }.map { it.toView() }.firstOrNull()
    }

    fun existsCode(code: String): Boolean = transaction {
        Projects.selectAll().where { Projects.code eq code }.any { true }
    }

    /**
     * GET /projects —— 可见性过滤：
     * SUPER_ADMIN 全量；其他用户仅自己拥有或参与的、且未DELETED的项目。JOIN去重防N+1。
     */
    fun listVisible(userId: Long, isSuperAdmin: Boolean, page: Int, pageSize: Int): Pair<List<ProjectView>, Long> =
        transaction {
            val q = if (isSuperAdmin) {
                Projects.selectAll().where { Projects.status neq ProjectStatus.DELETED.name }
            } else {
                // 一次 JOIN project_members，避免逐项目查询
                val memberProjectIds = ProjectMembers.selectAll()
                    .where { ProjectMembers.userId eq userId }
                    .map { it[ProjectMembers.projectId] }
                    .distinct()
                Projects.selectAll().where {
                    (Projects.status neq ProjectStatus.DELETED.name) and
                        ((Projects.ownerUserId eq userId) or (Projects.id inList memberProjectIds))
                }
            }
            val total = q.count()
            val items = q.orderBy(Projects.id to SortOrder.DESC)
                .limit(pageSize, offset = (page - 1).toLong() * pageSize)
                .map { it.toView() }
            items to total
        }

    /** §3.2 创建：插入project + project_members(owner=true, role=项目管理员)。外层事务包裹。 */
    fun createInTx(name: String, code: String, ownerUserId: Long, adminProjectRoleId: Long): Long {
        val now = Instant.now()
        val pid = Projects.insert {
            it[Projects.name] = name
            it[Projects.code] = code
            it[Projects.ownerUserId] = ownerUserId
            it[Projects.status] = ProjectStatus.ACTIVE.name
            it[Projects.createdBy] = ownerUserId
            it[Projects.createdAt] = now
            it[Projects.updatedAt] = now
        }[Projects.id]
        ProjectMembers.insert {
            it[ProjectMembers.userId] = ownerUserId
            it[ProjectMembers.projectId] = pid
            it[ProjectMembers.projectRoleId] = adminProjectRoleId
            it[ProjectMembers.isOwner] = true
            it[ProjectMembers.joinedAt] = now
        }
        return pid
    }

    fun setStatusInTx(projectId: Long, status: ProjectStatus) {
        Projects.update({ Projects.id eq projectId }) {
            it[Projects.status] = status.name
            it[Projects.updatedAt] = Instant.now()
        }
    }

    /** 注销触发：将该用户负责的所有 ACTIVE 项目标记 FROZEN，返回被冻结的项目ID列表（§1.2③） */
    fun freezeOwnedProjects(userId: Long): List<Long> {
        val targets = Projects.selectAll().where {
            (Projects.ownerUserId eq userId) and (Projects.status eq ProjectStatus.ACTIVE.name)
        }.map { it[Projects.id] }
        targets.forEach { setStatusInTx(it, ProjectStatus.FROZEN) }
        return targets
    }

    data class MemberInfo(val userId: Long, val projectId: Long, val projectRoleId: Long, val isOwner: Boolean)

    private fun ResultRow.member() = MemberInfo(
        userId = this[ProjectMembers.userId],
        projectId = this[ProjectMembers.projectId],
        projectRoleId = this[ProjectMembers.projectRoleId],
        isOwner = this[ProjectMembers.isOwner],
    )

    fun findMember(userId: Long, projectId: Long): MemberInfo? = transaction {
        ProjectMembers.selectAll().where {
            (ProjectMembers.userId eq userId) and (ProjectMembers.projectId eq projectId)
        }.map { it.member() }.firstOrNull()
    }

    fun membersOf(projectId: Long): List<MemberInfo> = transaction {
        ProjectMembers.selectAll().where { ProjectMembers.projectId eq projectId }.map { it.member() }
    }

    fun addMemberInTx(userId: Long, projectId: Long, projectRoleId: Long, isOwner: Boolean = false) {
        val exists = ProjectMembers.selectAll().where {
            (ProjectMembers.userId eq userId) and (ProjectMembers.projectId eq projectId)
        }.any { true }
        if (!exists) {
            ProjectMembers.insert {
                it[ProjectMembers.userId] = userId
                it[ProjectMembers.projectId] = projectId
                it[ProjectMembers.projectRoleId] = projectRoleId
                it[ProjectMembers.isOwner] = isOwner
                it[ProjectMembers.joinedAt] = Instant.now()
            }
        }
    }

    /**
     * §3.2 转移OWNER（事务内）：
     * 原owner.is_owner=false → 新owner.is_owner=true + 赋予项目管理员角色。
     * force=true 用于继承流程（继任者允许不在成员表中，自动补入）。
     */
    fun transferOwnerInTx(projectId: Long, newOwnerUserId: Long, force: Boolean = false) {
        val current = Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
            ?: throw com.constrasset.common.BusinessException("项目不存在", httpStatus = 404, bizCode = 3003)
        val oldOwnerId = current[Projects.ownerUserId]
        val newMember = findMember(newOwnerUserId, projectId)
        if (newMember == null && !force) {
            throw com.constrasset.common.BusinessException(
                "目标必须是现有成员", httpStatus = 400, bizCode = 3020)
        }
        val adminRoleId = ProjectRoles.selectAll().where { ProjectRoles.name eq com.constrasset.infra.ProjectRoleSeed.PROJECT_ADMIN }
            .firstOrNull()?.get(ProjectRoles.id)
            ?: throw com.constrasset.common.BusinessException("项目管理员角色未初始化", httpStatus = 500, bizCode = 3021)

        if (newMember == null) {
            addMemberInTx(newOwnerUserId, projectId, adminRoleId, isOwner = true)
        } else {
            ProjectMembers.update({
                (ProjectMembers.userId eq newOwnerUserId) and (ProjectMembers.projectId eq projectId)
            }) {
                it[ProjectMembers.isOwner] = true
                it[ProjectMembers.projectRoleId] = adminRoleId // 赋予项目管理员角色
            }
        }
        if (oldOwnerId != newOwnerUserId) {
            ProjectMembers.update({
                (ProjectMembers.userId eq oldOwnerId) and (ProjectMembers.projectId eq projectId)
            }) {
                it[ProjectMembers.isOwner] = false
            }
        }
        Projects.update({ Projects.id eq projectId }) {
            it[Projects.ownerUserId] = newOwnerUserId
            it[Projects.updatedAt] = Instant.now()
        }
    }

    /** 是否存在处于FROZEN状态、可被认领的项目（继承-认领的候选列表） */
    fun frozenProjects(): List<ProjectView> = transaction {
        Projects.selectAll().where { Projects.status eq ProjectStatus.FROZEN.name }.map { it.toView() }
    }
}
