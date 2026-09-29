package com.constrasset.project

import com.constrasset.audit.LogActions
import com.constrasset.audit.TargetTypes
import com.constrasset.auth.AuditRecorder
import com.constrasset.common.BusinessException
import com.constrasset.common.Patterns
import com.constrasset.common.Validator
import com.constrasset.infra.ProjectRoleSeed as InfraProjectRoleSeed
import com.constrasset.recycle.RecycleBinRepository
import com.constrasset.recycle.RecycleSourceType
import com.constrasset.auth.UserStatus
import com.constrasset.auth.Users
import com.constrasset.infra.PasswordHasher
import com.constrasset.rbac.OrgRoles
import com.constrasset.rbac.ProjectRoles
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * §3.2 项目关键操作逻辑。
 */
class ProjectService(
    private val projects: ProjectRepository,
    private val recycle: RecycleBinRepository,
    private val audit: AuditRecorder,
) {

    @Serializable
    data class CreateProjectRequest(val name: String, val code: String)

    /** 创建：校验PROJECT_CREATE → 插入project → 插入project_members(owner=true, role=项目管理员) */
    fun create(operatorId: Long, req: CreateProjectRequest, ip: String): ProjectRepository.ProjectView {
        Validator()
            .required("name", req.name)
            .length("name", req.name, 1, 50, "项目名称1-50位")
            .pattern("code", req.code, Patterns.PROJECT_CODE, "项目编号需为^[a-zA-Z0-9]{2,50}$")
            .finish()
        if (projects.existsCode(req.code)) {
            throw BusinessException("项目编号已存在", httpStatus = 409, bizCode = 3030)
        }
        val adminRoleId = transaction {
            ProjectRoles.selectAll().firstOrNull { it[ProjectRoles.name] == com.constrasset.infra.ProjectRoleSeed.PROJECT_ADMIN }
                ?.get(ProjectRoles.id)
                ?: throw BusinessException("内置项目管理员角色缺失", httpStatus = 500, bizCode = 3031)
        }
        val pid = transaction {
            val id = projects.createInTx(req.name, req.code, operatorId, adminRoleId)
            audit.recordInTx(operatorId, LogActions.PROJECT_CREATE, id, TargetTypes.PROJECT, id, ip,
                mapOf("code" to req.code))
            id
        }
        return projects.findById(pid)!!
    }

    @Serializable
    data class ConfirmNameRequest(val confirmName: String)

    /**
     * 归档（§3.2）：PROJECT_ARCHIVE → 二次确认name完全匹配（区分大小写）→ status=ARCHIVED。
     */
    fun archive(operatorId: Long, projectId: Long, confirmName: String, ip: String) {
        val p = mustExist(projectId)
        requireWritable(p)
        if (p.status == ProjectStatus.ARCHIVED.name)
            throw BusinessException("项目已归档", httpStatus = 409, bizCode = 3032)
        // ⚠️ 区分大小写的完全匹配
        Validator().check(confirmName == p.name, "confirmName", "二次确认失败：名称必须与项目名完全一致（区分大小写）").finish()
        transaction {
            projects.setStatusInTx(projectId, ProjectStatus.ARCHIVED)
            audit.recordInTx(operatorId, LogActions.PROJECT_ARCHIVE, projectId, TargetTypes.PROJECT, projectId, ip, null)
        }
    }

    /**
     * 删除（§3.2）：PROJECT_DELETE → 二次确认name完全匹配 → status=DELETED → 写入recycle_bin。
     * （用户注销触发的冻结不进回收站；只有显式删除进回收站，§1.2④/§7）
     */
    fun delete(operatorId: Long, projectId: Long, confirmName: String, ip: String) {
        val p = mustExist(projectId)
        if (p.status == ProjectStatus.DELETED.name)
            throw BusinessException("项目已在回收站", httpStatus = 409, bizCode = 3033)
        Validator().check(confirmName == p.name, "confirmName", "二次确认失败：名称必须与项目名完全一致（区分大小写）").finish()
        transaction {
            projects.setStatusInTx(projectId, ProjectStatus.DELETED)
            recycle.insertInTx(RecycleSourceType.PROJECT, projectId, p.name, operatorId)
            audit.recordInTx(operatorId, LogActions.PROJECT_DELETE, projectId, TargetTypes.PROJECT, projectId, ip, null)
        }
    }

    /**
     * 转移OWNER（§3.2）：当前用户is_owner=true → 目标必须是现有member → 事务内双更新。
     */
    @Serializable
    data class TransferOwnerRequest(val newOwnerUserId: Long)

    fun transferOwner(operatorId: Long, projectId: Long, req: TransferOwnerRequest, ip: String) {
        val p = mustExist(projectId)
        requireWritable(p)
        val me = projects.findMember(operatorId, projectId)
        if (me == null || !me.isOwner) {
            throw BusinessException("仅项目OWNER可转移负责人", httpStatus = 403, bizCode = 3040)
        }
        transaction {
            projects.transferOwnerInTx(projectId, req.newOwnerUserId, force = false)
            audit.recordInTx(operatorId, LogActions.PROJECT_TRANSFER_OWNER, projectId,
                TargetTypes.PROJECT, projectId, ip, mapOf("newOwnerUserId" to req.newOwnerUserId))
        }
    }

    @Serializable
    data class AddMemberRequest(val userId: Long, val projectRoleId: Long)

    /** POST /projects/{id}/members —— MEMBER_MANAGE */
    fun addMember(operatorId: Long, projectId: Long, req: AddMemberRequest, ip: String) {
        val p = mustExist(projectId)
        requireWritable(p)
        Validator()
            .check(req.userId > 0, "userId", "非法用户ID")
            .check(req.projectRoleId > 0, "projectRoleId", "非法项目角色ID")
            .finish()
        transaction {
            projects.addMemberInTx(req.userId, projectId, req.projectRoleId)
            audit.recordInTx(operatorId, LogActions.MEMBER_ADD, projectId, TargetTypes.USER, req.userId, ip,
                mapOf("projectRoleId" to req.projectRoleId))
        }
    }

    /**
     * POST /projects/{id}/guests —— 创建临时访客（§1.2）。
     * username = guest_{projectId}_{随机后缀}（≤20位），初始密码由服务端生成并一次性返回。
     */
    @Serializable
    data class GuestResponse(val guestUserId: Long, val username: String, val initialPassword: String)

    fun createGuest(operatorId: Long, projectId: Long, nickname: String, projectRoleId: Long, ip: String): GuestResponse {
        val p = mustExist(projectId)
        requireWritable(p)
        Validator().length("nickname", nickname, 1, 50).finish()
        val suffix = ('a'..'z').random().toString() + ('0'..'9').random() + ('a'..'z').random()
        val guestName = "guest_${projectId}_$suffix".take(20)
        val rawPwd = "Gt${projectId}${suffix}@Aa1".replace("@", "") // 满足强度正则的随机初始密码
        val member = projects.findMember(operatorId, projectId)
        val roleId = projectRoleId.takeIf { it > 0 } ?: member?.projectRoleId
            ?: throw BusinessException("缺少项目角色", httpStatus = 400, bizCode = 3050)
        val guestId = transaction {
            val id = createGuestUser(guestName, nickname, rawPwd)
            projects.addMemberInTx(id, projectId, roleId)
            audit.recordInTx(operatorId, LogActions.GUEST_CREATE, projectId, TargetTypes.USER, id, ip,
                mapOf("username" to guestName))
            id
        }
        return GuestResponse(guestId, guestName, rawPwd)
    }

    fun list(operatorId: Long, isSuperAdmin: Boolean, page: Int, pageSize: Int) =
        projects.listVisible(operatorId, isSuperAdmin, page, pageSize)

    fun frozenProjects() = projects.frozenProjects()

    private fun mustExist(projectId: Long): ProjectRepository.ProjectView =
        projects.findById(projectId) ?: throw BusinessException("项目不存在", httpStatus = 404, bizCode = 3003)

    private fun requireWritable(p: ProjectRepository.ProjectView) {
        ProjectStatus.valueOf(p.status).guardWritable("项目操作")
    }

    /** 访客账号复用 users 表；单独小仓库避免污染 UserRepository 语义 */
    private fun createGuestUser(username: String, nickname: String, rawPwd: String): Long = transaction {
        val now = Instant.now()
        val memberRoleId = OrgRoles.selectAll()
            .first { it[OrgRoles.name] == "MEMBER" }[OrgRoles.id]
        Users.insert {
            it[Users.username] = username
            it[Users.nickname] = nickname
            it[Users.passwordHash] = PasswordHasher.hash(rawPwd)
            it[Users.orgRoleId] = memberRoleId
            it[Users.status] = UserStatus.ACTIVE.name
            it[Users.deletedUsername] = null
            it[Users.createdAt] = now
            it[Users.updatedAt] = now
        }[Users.id]
    }

}
