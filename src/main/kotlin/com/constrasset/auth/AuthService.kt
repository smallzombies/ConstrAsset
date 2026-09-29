package com.constrasset.auth

import com.constrasset.audit.LogActions
import com.constrasset.audit.TargetTypes
import com.constrasset.common.BusinessException
import com.constrasset.common.Patterns
import com.constrasset.common.Validator
import com.constrasset.infra.PasswordHasher
import com.constrasset.project.ProjectRepository
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * §1.2 认证与用户业务逻辑。
 */
class AuthService(
    private val users: UserRepository,
    private val projects: ProjectRepository,
    private val auditSink: AuditRecorder,
) {

    @Serializable
    data class LoginRequest(val username: String, val password: String)

    @Serializable
    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String = "Bearer",
        val expiresIn: Long,
        val isGuest: Boolean = false,
        val projectId: Long? = null,
    )

    /**
     * 登录：
     * - 普通用户：Argon2id 校验 → ACTIVE 检查 → Access(15min)+Refresh(7d)
     * - 临时访客（§1.2）：识别 guest_ 前缀 → 校验项目角色有效性 → Token含projectId+projectRoleId → 无平台菜单权限
     */
    fun login(req: LoginRequest, ip: String): TokenPair {
        Validator()
            .required("username", req.username)
            .required("password", req.password)
            .finish()

        val user = users.findByUsernameIgnoreCase(req.username)
            ?: throw BusinessException("用户名或密码错误", httpStatus = 401, bizCode = 1010)

        if (!PasswordHasher.verify(req.password, user.passwordHash)) {
            // 统一错误信息，避免用户名枚举
            throw BusinessException("用户名或密码错误", httpStatus = 401, bizCode = 1010)
        }
        when (user.status) {
            UserStatus.DISABLED -> throw BusinessException("账号已被禁用", httpStatus = 403, bizCode = 1011)
            UserStatus.DELETED -> throw BusinessException("账号已注销", httpStatus = 403, bizCode = 1012)
            UserStatus.ACTIVE -> Unit
        }

        val orgRoleName = users.orgRoleName(user.id)

        return if (user.username.startsWith(Patterns.GUEST_PREFIX)) {
            loginAsGuest(user, orgRoleName, ip)
        } else {
            val claims = JwtService.Claims(
                userId = user.id, username = user.username, orgRoleName = orgRoleName,
            )
            val access = JwtService.issueAccess(claims)
            val (refresh, _) = JwtService.issueRefresh(user.id)
            auditSink.record(user.id, LogActions.USER_LOGIN, null, TargetTypes.USER, user.id, ip, null)
            TokenPair(access, refresh, expiresIn = JwtService.accessTtlSeconds)
        }
    }

    /** 临时访客登录：guest_{projectId}_{...}，Token必须携带 projectId + projectRoleId（Critical Note #7） */
    private fun loginAsGuest(user: UserEntity, orgRoleName: String, ip: String): TokenPair {
        // username 形如 guest_{projectId}_{suffix}
        val parts = user.username.split("_")
        val projectId = parts.getOrNull(1)?.toLongOrNull()
            ?: throw BusinessException("访客账号格式非法", httpStatus = 401, bizCode = 1013)
        // 校验项目角色有效性：成员关系必须仍存在且项目未删除
        val member = projects.findMember(user.id, projectId)
            ?: throw BusinessException("访客项目角色已失效", httpStatus = 403, bizCode = 1014)
        val project = projects.findById(projectId)
            ?: throw BusinessException("项目不存在", httpStatus = 404, bizCode = 3003)
        if (project.status == com.constrasset.project.ProjectStatus.DELETED.name) {
            throw BusinessException("项目已删除", httpStatus = 410, bizCode = 3002)
        }
        val claims = JwtService.Claims(
            userId = user.id, username = user.username, orgRoleName = orgRoleName,
            projectId = projectId, projectRoleId = member.projectRoleId, isGuest = true,
        )
        val access = JwtService.issueAccess(claims)
        val (refresh, _) = JwtService.issueRefresh(user.id)
        auditSink.record(user.id, LogActions.USER_LOGIN, projectId, TargetTypes.USER, user.id, ip,
            mapOf("guest" to true))
        return TokenPair(access, refresh, expiresIn = JwtService.accessTtlSeconds,
            isGuest = true, projectId = projectId)
    }

    /** POST /auth/refresh：旧 Refresh 吊销 + 新对签发（Rotation） */
    fun refresh(refreshToken: String): TokenPair {
        val oldClaims = JwtService.verifyRefreshAndGetClaims(refreshToken)
        val user = users.findById(oldClaims.userId)
            ?: throw BusinessException("用户不存在", httpStatus = 401, bizCode = 1010)
        if (user.status != UserStatus.ACTIVE)
            throw BusinessException("账号状态异常", httpStatus = 403, bizCode = 1011)
        // Rotation：删除旧 tokenId，防止重放
        JwtService.revokeAllRefreshTokens(user.id)

        val orgRoleName = users.orgRoleName(user.id)
        return if (user.username.startsWith(Patterns.GUEST_PREFIX)) {
            val parts = user.username.split("_")
            val projectId = parts[1].toLong()
            val member = projects.findMember(user.id, projectId)
                ?: throw BusinessException("访客项目角色已失效", httpStatus = 403, bizCode = 1014)
            val claims = JwtService.Claims(
                userId = user.id, username = user.username, orgRoleName = orgRoleName,
                projectId = projectId, projectRoleId = member.projectRoleId, isGuest = true,
            )
            val access = JwtService.issueAccess(claims)
            val (newRefresh, _) = JwtService.issueRefresh(user.id)
            TokenPair(accessToken = access, refreshToken = newRefresh, expiresIn = JwtService.accessTtlSeconds, isGuest = true, projectId = projectId)
        } else {
            val claims = JwtService.Claims(userId = user.id, username = user.username, orgRoleName = orgRoleName)
            val access = JwtService.issueAccess(claims)
            val (newRefresh, _) = JwtService.issueRefresh(user.id)
            TokenPair(access, newRefresh, expiresIn = JwtService.accessTtlSeconds)
        }
    }

    /** 登出：Access jti 加入黑名单（TTL=剩余有效期），清空 Refresh Hash（§1.2） */
    fun logout(claims: JwtService.Claims, ip: String) {
        val remaining = claims.expEpochSec - System.currentTimeMillis() / 1000
        JwtService.logout(claims.jti, remaining, claims.userId)
        auditSink.record(claims.userId, LogActions.USER_LOGOUT, null, TargetTypes.USER, claims.userId, ip, null)
    }

    // ------------------- 用户管理 -------------------

    @Serializable
    data class CreateUserRequest(
        val username: String,
        val nickname: String,
        val password: String,
        val orgRoleId: Long,
    )

    fun createUser(operatorId: Long, req: CreateUserRequest, ip: String): Long {
        Validator()
            .pattern("username", req.username, Patterns.USERNAME, "用户名需6-20位，字母/下划线开头，可含数字、中划线")
            .length("nickname", req.nickname, 1, 50, "昵称长度1-50位")
            .check(Patterns.PASSWORD.matches(req.password), "password",
                "密码需8-18位，仅字母数字，且同时包含大写、小写和数字")
            .finish()
        if (users.existsUsernameIgnoreCase(req.username)) {
            throw BusinessException("用户名已存在（不区分大小写）", httpStatus = 409, bizCode = 1020)
        }
        val id = users.create(req.username, req.nickname, PasswordHasher.hash(req.password), req.orgRoleId)
        auditSink.record(operatorId, LogActions.USER_CREATE, null, TargetTypes.USER, id, ip,
            mapOf("username" to req.username))
        return id
    }

    /** PUT /users/{id}/password-reset —— USER_RESET_PASSWORD；新密码同样执行强度校验 */
    fun resetPassword(operatorId: Long, targetUserId: Long, newPassword: String, ip: String) {
        Validator()
            .check(Patterns.PASSWORD.matches(newPassword), "password",
                "密码需8-18位，仅字母数字，且同时包含大写、小写和数字")
            .finish()
        val target = users.findById(targetUserId)
            ?: throw BusinessException("用户不存在", httpStatus = 404, bizCode = 1021)
        if (target.status != UserStatus.ACTIVE)
            throw BusinessException("只能对ACTIVE用户重置密码", httpStatus = 400, bizCode = 1022)
        users.updatePasswordHash(targetUserId, PasswordHasher.hash(newPassword))
        // 强制重新登录：吊销全部 Refresh（不记录任何密码信息，Critical Note #9）
        JwtService.revokeAllRefreshTokens(targetUserId)
        auditSink.record(operatorId, LogActions.USER_RESET_PASSWORD, null, TargetTypes.USER, targetUserId, ip, null)
    }

    /**
     * DELETE /users/{id} —— 注销流程（§1.2）。四步全部在一个事务内（Critical Note #2）：
     * ① username→{name}_{epochMs} ②操作日志 ③负责项目标记FROZEN ④不进回收站
     */
    fun deactivateUser(operatorId: Long, targetUserId: Long, ip: String) {
        val target = users.findById(targetUserId)
            ?: throw BusinessException("用户不存在", httpStatus = 404, bizCode = 1021)
        if (target.status == UserStatus.DELETED)
            throw BusinessException("用户已注销", httpStatus = 409, bizCode = 1023)
        if (target.username.startsWith(Patterns.GUEST_PREFIX))
            throw BusinessException("临时访客请使用成员移除接口", httpStatus = 400, bizCode = 1024)

        transaction {
            val renamed = users.renameForDeletion(targetUserId, target.username)
            // ③ 将其负责的项目标记 FROZEN
            val frozenIds = projects.freezeOwnedProjects(targetUserId)
            // ② 操作日志（同事务；metadata 为脱敏快照，§8示例格式）
            auditSink.recordInTx(
                operatorId, LogActions.USER_DELETE, null, TargetTypes.USER, targetUserId, ip,
                mapOf(
                    "originalUsername" to target.username,
                    "renamedTo" to renamed,
                    "frozenProjectIds" to frozenIds,
                ),
            )
        }
        // 事务外：吊销其所有会话
        JwtService.revokeAllRefreshTokens(targetUserId)
    }

    /**
     * POST /users/inherit/assign —— 继承-指定（§1.2）：
     * 有 USER_INHERIT_ASSIGN 权限 → 立即转移项目OWNER给指定用户 → 解冻项目。
     */
    @Serializable
    data class InheritAssignRequest(val frozenProjectId: Long, val successorUserId: Long)

    fun inheritAssign(operatorId: Long, req: InheritAssignRequest, ip: String) {
        val project = projects.findById(req.frozenProjectId)
            ?: throw BusinessException("项目不存在", httpStatus = 404, bizCode = 3003)
        if (project.status != com.constrasset.project.ProjectStatus.FROZEN.name)
            throw BusinessException("仅FROZEN项目可执行继承指定", httpStatus = 400, bizCode = 3010)
        val successor = users.findById(req.successorUserId)
            ?: throw BusinessException("继任者不存在", httpStatus = 404, bizCode = 1021)
        if (successor.status != UserStatus.ACTIVE)
            throw BusinessException("继任者必须为ACTIVE用户", httpStatus = 400, bizCode = 1025)

        transaction {
            // 目标必须是现有成员；否则先加入并赋予项目管理员角色（转移OWNER语义 §3.2）
            projects.transferOwnerInTx(req.frozenProjectId, successor.id, force = true)
            projects.setStatusInTx(req.frozenProjectId, com.constrasset.project.ProjectStatus.ACTIVE)
            auditSink.recordInTx(operatorId, LogActions.USER_INHERIT_ASSIGN, req.frozenProjectId,
                TargetTypes.PROJECT, req.frozenProjectId, ip,
                mapOf("successorUserId" to successor.id, "inheritanceMode" to "ASSIGNED"))
        }
    }

    /**
     * POST /users/inherit/claim —— 继承-认领（§1.2）：
     * 有 USER_INHERIT_CLAIM 权限 → 从冻结项目中认领（认领人成为OWNER）→ 解冻项目。
     */
    @Serializable
    data class InheritClaimRequest(val frozenProjectId: Long)

    fun inheritClaim(operatorId: Long, req: InheritClaimRequest, ip: String) {
        val project = projects.findById(req.frozenProjectId)
            ?: throw BusinessException("项目不存在", httpStatus = 404, bizCode = 3003)
        if (project.status != com.constrasset.project.ProjectStatus.FROZEN.name)
            throw BusinessException("仅FROZEN项目可认领", httpStatus = 400, bizCode = 3010)

        transaction {
            projects.transferOwnerInTx(req.frozenProjectId, operatorId, force = true)
            projects.setStatusInTx(req.frozenProjectId, com.constrasset.project.ProjectStatus.ACTIVE)
            auditSink.recordInTx(operatorId, LogActions.USER_INHERIT_CLAIM, req.frozenProjectId,
                TargetTypes.PROJECT, req.frozenProjectId, ip,
                mapOf("claimerUserId" to operatorId, "inheritanceMode" to "CLAIMED"))
        }
    }

    /** GET 当前登录用户信息 */
    fun me(userId: Long): UserView {
        val u = users.findById(userId) ?: throw BusinessException("用户不存在", httpStatus = 404, bizCode = 1021)
        return UserView(u.id, u.username, u.nickname, users.orgRoleName(u.id), u.status.name, u.createdAt.toString())
    }

    @Serializable
    data class UserView(
        val id: Long, val username: String, val nickname: String,
        val orgRole: String, val status: String, val createdAt: String,
    )
}

/**
 * 审计写入的轻量接口，由 audit 模块的 AuditRecorder 实现（禁止业务代码手写SQL，§8）。
 */
interface AuditRecorder {
    fun record(
        operatorId: Long, action: String, projectId: Long?, targetType: String?,
        targetId: Long?, ip: String, metadata: Map<String, Any?>?,
    )

    /** 在调用方事务内写入（多表一致性场景，Critical Note #2） */
    fun recordInTx(
        operatorId: Long, action: String, projectId: Long?, targetType: String?,
        targetId: Long?, ip: String, metadata: Map<String, Any?>?,
    )
}
