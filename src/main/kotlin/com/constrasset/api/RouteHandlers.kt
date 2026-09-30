package com.constrasset.api

import com.constrasset.approval.ApprovalService
import com.constrasset.audit.OperationLogs
import com.constrasset.auth.AuthService
import com.constrasset.auth.UserRepository
import com.constrasset.common.ApiResponse
import com.constrasset.common.BusinessException
import com.constrasset.common.Page
import com.constrasset.common.ok
import com.constrasset.file.FileService
import com.constrasset.project.ProjectService
import com.constrasset.rbac.OrgPermissions
import com.constrasset.rbac.ProjectMembers
import com.constrasset.rbac.ProjectPermissions
import com.constrasset.rbac.ProjectRoles
import com.constrasset.recycle.RecycleBinService
import com.constrasset.tag.TagService
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * §9 RESTful 端点处理器。
 * 权限校验顺序遵循 §2.3 checkPermission：SUPER_ADMIN兜底 → 项目级 → 组织级。
 * 每个 Handler 顶部显式 requirePerm(...)，即"权限注解"的落地形式（Note #5）。
 */
class RouteHandlers(
    private val auth: AuthService,
    private val users: UserRepository,
    private val projects: ProjectService,
    private val files: FileService,
    private val tags: TagService,
    private val approvals: ApprovalService,
    private val recycle: RecycleBinService,
) {

    // ---------- 公共 DTO ----------

    @Serializable data class RefreshRequest(val refreshToken: String)
    @Serializable data class ResetPasswordRequest(val newPassword: String)
    @Serializable data class ConfirmNameRequest(val confirmName: String)
    @Serializable data class ConflictCheckRequest(val folderId: Long?, val fileNames: List<String>)
    @Serializable data class BatchTagRequest(val fileIds: List<Long>, val tagIds: List<Long>, val mode: String)
    @Serializable data class SetTagsRequest(val tagIds: List<Long>)
    @Serializable data class PackRequest(val fileIds: List<Long>, val folderIds: List<Long>)
    @Serializable data class CreateTagRequest(val name: String, val parentId: Long?)
    @Serializable data class UpdateTagRequest(val name: String? = null, val parentId: Long? = null)
    @Serializable data class AddMemberBody(val userId: Long, val projectRoleId: Long)
    @Serializable data class CreateGuestBody(val nickname: String, val projectRoleId: Long)
    @Serializable data class TransferOwnerBody(val newOwnerUserId: Long)
    @Serializable data class LogView(
        val id: Long, val operatorId: Long, val action: String, val projectId: Long?,
        val targetType: String?, val targetId: Long?, val ipAddress: String, val createdAt: String,
    )
    @Serializable data class UserRowDto(
        val id: Long, val username: String, val nickname: String,
        val orgRole: String, val status: String, val createdAt: String,
    )
    @Serializable data class RecycleRowDto(
        val id: Long, val sourceType: String, val sourceId: Long, val originalName: String,
        val deletedBy: Long, val deletedAt: String, val expireAt: String,
    )
    @Serializable data class FileDetailDto(
        val id: Long, val projectId: Long, val parentFolderId: Long?, val name: String,
        val size: Long, val mimeType: String, val hasThumbnail: Boolean, val exifOrientation: Int,
        val uploadedBy: Long, val approvalStatus: String, val tagIds: List<Long>,
        val approvals: List<ApprovalService.ApprovalRecordView>,
    )
    data class ProjectMembersInfo(val projectRoleId: Long, val isOwner: Boolean)

    // ---------- /auth ----------

    fun login(req: AuthService.LoginRequest, ip: String) = ok(auth.login(req, ip))

    fun refresh(req: RefreshRequest) = ok(auth.refresh(req.refreshToken))

    fun logout(principal: Principal, ip: String): ApiResponse<Unit?> {
        auth.logout(principal.claims, ip)
        return ok(null, "已登出")
    }

    fun me(principal: Principal) = ok(auth.me(principal.userId))

    // ---------- /users ----------

    fun listUsers(principal: Principal, page: Int, pageSize: Int, keyword: String?, status: String?): ApiResponse<Page<UserRowDto>> {
        requireOrgPerm(principal, OrgPermissions.USER_READ)
        val p = page.coerceAtLeast(1); val ps = pageSize.coerceIn(1, 100)
        val (items, total) = users.listPaged(p, ps, keyword, status)
        return ok(Page(items.map { UserRowDto(it.id, it.username, it.nickname, it.orgRoleName, it.status, it.createdAt.toString()) }, total, p, ps))
    }

    fun createUser(principal: Principal, req: AuthService.CreateUserRequest, ip: String): ApiResponse<Long> {
        requireOrgPerm(principal, OrgPermissions.USER_CREATE)
        return ok(auth.createUser(principal.userId, req, ip))
    }

    fun resetPassword(principal: Principal, targetId: Long, req: ResetPasswordRequest, ip: String): ApiResponse<Unit?> {
        requireOrgPerm(principal, OrgPermissions.USER_RESET_PASSWORD)
        auth.resetPassword(principal.userId, targetId, req.newPassword, ip)
        return ok(null, "密码已重置")
    }

    fun deactivateUser(principal: Principal, targetId: Long, ip: String): ApiResponse<Unit?> {
        requireOrgPerm(principal, OrgPermissions.USER_DELETE)
        auth.deactivateUser(principal.userId, targetId, ip)
        return ok(null, "用户已注销")
    }

    fun inheritAssign(principal: Principal, req: AuthService.InheritAssignRequest, ip: String): ApiResponse<Unit?> {
        requireOrgPerm(principal, OrgPermissions.USER_INHERIT_ASSIGN)
        auth.inheritAssign(principal.userId, req, ip)
        return ok(null, "已指定继承")
    }

    fun inheritClaim(principal: Principal, req: AuthService.InheritClaimRequest, ip: String): ApiResponse<Unit?> {
        requireOrgPerm(principal, OrgPermissions.USER_INHERIT_CLAIM)
        auth.inheritClaim(principal.userId, req, ip)
        return ok(null, "已认领并解冻项目")
    }

    // ---------- /projects ----------

    fun listProjects(principal: Principal, page: Int, pageSize: Int): ApiResponse<Any?> {
        val (items, total) = projects.list(principal.userId, principal.isSuperAdmin,
            page.coerceAtLeast(1), pageSize.coerceIn(1, 100))
        return ok(mapOf("items" to items, "total" to total))
    }

    fun createProject(principal: Principal, req: ProjectService.CreateProjectRequest, ip: String): ApiResponse<Any?> {
        requireOrgPerm(principal, OrgPermissions.PROJECT_CREATE)
        return ok(projects.create(principal.userId, req, ip))
    }

    /** 归档/删除需要 PROJECT_ARCHIVE/PROJECT_DELETE —— 属于项目级权限（§2.2），走项目检查分支。 */
    fun archiveProject(principal: Principal, projectId: Long, req: ConfirmNameRequest, ip: String): ApiResponse<Unit?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.PROJECT_ARCHIVE)
        projects.archive(principal.userId, projectId, req.confirmName, ip)
        return ok(null, "项目已归档")
    }

    fun deleteProject(principal: Principal, projectId: Long, req: ConfirmNameRequest, ip: String): ApiResponse<Unit?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.PROJECT_DELETE)
        projects.delete(principal.userId, projectId, req.confirmName, ip)
        return ok(null, "项目已删除并进入回收站")
    }

    fun transferOwner(principal: Principal, projectId: Long, req: TransferOwnerBody, ip: String): ApiResponse<Unit?> {
        // §3.2：转移OWNER仅要求当前用户 is_owner=true（SUPER_ADMIN 兜底放行）
        if (!principal.isSuperAdmin) {
            val member = membershipOf(principal.userId, projectId)
                ?: throw BusinessException("不是该项目成员", httpStatus = 403, bizCode = 2003)
            if (!member.isOwner) throw BusinessException("仅项目OWNER可转移负责人", httpStatus = 403, bizCode = 2004)
        }
        projects.transferOwner(principal.userId, projectId, ProjectService.TransferOwnerRequest(req.newOwnerUserId), ip)
        return ok(null, "OWNER已转移")
    }

    fun addMember(principal: Principal, projectId: Long, req: AddMemberBody, ip: String): ApiResponse<Unit?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.MEMBER_MANAGE)
        projects.addMember(principal.userId, projectId, ProjectService.AddMemberRequest(req.userId, req.projectRoleId), ip)
        return ok(null, "成员已添加")
    }

    fun createGuest(principal: Principal, projectId: Long, req: CreateGuestBody, ip: String): ApiResponse<Any?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.MEMBER_MANAGE)
        return ok(projects.createGuest(principal.userId, projectId, req.nickname, req.projectRoleId, ip))
    }

    // ---------- 文件工作台 ----------

    fun checkConflict(principal: Principal, projectId: Long, req: ConflictCheckRequest): ApiResponse<Any?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.MEDIA_UPLOAD)
        return ok(mapOf("conflicts" to files.checkConflicts(projectId, req.folderId, req.fileNames.take(50))))
    }

    /** multipart 上传由 Routing 层解析后调用（策略头 X-Conflict-Strategy 透传，§4.2）。 */
    suspend fun upload(principal: Principal, projectId: Long, folderId: Long?,
                       items: List<FileService.UploadItem>, strategyHeader: String?, ip: String): ApiResponse<Any?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.MEDIA_UPLOAD)
        return ok(files.upload(projectId, folderId, principal.userId, ip, items, strategyHeader))
    }

    fun pack(principal: Principal, projectId: Long, req: PackRequest, ip: String): ApiResponse<Any?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.FILE_DOWNLOAD)
        return ok(mapOf("packId" to files.startPack(principal.userId, projectId, req.fileIds, req.folderIds, ip)))
    }

    fun packStatus(principal: Principal, projectId: Long, packId: String): ApiResponse<Any?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.FILE_DOWNLOAD)
        return ok(files.packStatus(projectId, packId))
    }

    fun downloadUrl(principal: Principal, fileId: Long): ApiResponse<Any?> {
        requireFilePerm(principal, fileId, ProjectPermissions.FILE_DOWNLOAD)
        return ok(mapOf("url" to files.downloadUrl(fileId)))
    }

    fun fileDetail(principal: Principal, fileId: Long): ApiResponse<FileDetailDto> {
        requireFilePerm(principal, fileId, ProjectPermissions.FILE_VIEW_DETAIL)
        val f = files.filesRepo().findById(fileId)
            ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)
        return ok(FileDetailDto(
            f.id, f.projectId, f.parentFolderId, f.name, f.size, f.mimeType,
            f.thumbnailKey != null, f.exifOrientation, f.uploadedBy, f.approvalStatus.name,
            tags.tagsOfFile(f.id), approvals.historyOf(f.id),
        ))
    }

    fun imageNav(principal: Principal, projectId: Long, folderId: Long?, currentFileId: Long?): ApiResponse<Any?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.FILE_VIEW)
        return ok(approvals.folderImageNav(projectId, folderId, currentFileId))
    }

    fun setFileTags(principal: Principal, fileId: Long, req: SetTagsRequest, ip: String): ApiResponse<Unit?> {
        requireFilePerm(principal, fileId, ProjectPermissions.FILE_TAG)
        tags.setFileTags(fileId, req.tagIds, principal.userId, ip)
        return ok(null, "标签已更新")
    }

    fun batchTag(principal: Principal, projectId: Long, req: BatchTagRequest, ip: String): ApiResponse<Unit?> {
        requireProjectPerm(principal, projectId, ProjectPermissions.FILE_TAG)
        tags.batchTag(projectId, req.fileIds, req.tagIds, req.mode, principal.userId, ip)
        return ok(null, "批量打标完成")
    }

    fun approve(principal: Principal, fileId: Long, req: ApprovalService.ApprovalRequest, ip: String): ApiResponse<Unit?> {
        requireFilePerm(principal, fileId, ProjectPermissions.FILE_APPROVE)
        approvals.submit(fileId, req, principal.userId, ip)
        return ok(null, "审批已提交")
    }

    fun listComments(principal: Principal, fileId: Long): ApiResponse<Any?> {
        requireFilePerm(principal, fileId, ProjectPermissions.FILE_COMMENT)
        return ok(approvals.listComments(fileId))
    }

    fun addComment(principal: Principal, fileId: Long, req: ApprovalService.CommentRequest, ip: String): ApiResponse<Long> {
        requireFilePerm(principal, fileId, ProjectPermissions.FILE_COMMENT)
        return ok(approvals.addComment(fileId, req, principal.userId, ip))
    }

    fun deleteFile(principal: Principal, fileId: Long, ip: String): ApiResponse<Unit?> {
        requireFilePerm(principal, fileId, ProjectPermissions.PROJECT_DELETE)
        files.deleteFile(principal.userId, fileId, ip)
        return ok(null, "文件已移入回收站")
    }

    // ---------- /tags ----------

    fun tagTree(principal: Principal): ApiResponse<Any?> {
        requireOrgPerm(principal, OrgPermissions.TAG_VIEW)
        return ok(tags.flatTree())
    }

    fun createTag(principal: Principal, req: CreateTagRequest, ip: String): ApiResponse<Long> {
        requireOrgPerm(principal, OrgPermissions.TAG_MANAGE)
        return ok(tags.createTag(req.name, req.parentId, principal.userId, ip))
    }

    fun updateTag(principal: Principal, tagId: Long, req: UpdateTagRequest, ip: String): ApiResponse<Unit?> {
        requireOrgPerm(principal, OrgPermissions.TAG_MANAGE)
        tags.updateTag(tagId, req.name, req.parentId, principal.userId, ip)
        return ok(null, "标签已更新")
    }

    fun deleteTag(principal: Principal, tagId: Long, ip: String): ApiResponse<Int> {
        requireOrgPerm(principal, OrgPermissions.TAG_MANAGE)
        return ok(tags.deleteTag(tagId, principal.userId, ip))
    }

    // ---------- /recycle-bin ----------

    fun recycleList(principal: Principal, page: Int, pageSize: Int): ApiResponse<Page<RecycleRowDto>> {
        val p = page.coerceAtLeast(1); val ps = pageSize.coerceIn(1, 100)
        val (items, total) = recycle.repo.list(principal.userId, principal.isSuperAdmin, p, ps)
        return ok(Page(items.map { RecycleRowDto(it.id, it.sourceType, it.sourceId, it.originalName, it.deletedBy, it.deletedAt, it.expireAt) }, total, p, ps))
    }

    fun restore(principal: Principal, recycleId: Long, ip: String): ApiResponse<String> =
        ok(recycle.restore(principal.userId, recycleId, ip))

    fun purge(principal: Principal, recycleId: Long, ip: String): ApiResponse<Unit?> {
        // §9：二次删除需 PROJECT_DELETE（任一项目角色含此权限即可，或SUPER_ADMIN）
        if (!principal.isSuperAdmin && !hasAnyProjectPerm(principal.userId, ProjectPermissions.PROJECT_DELETE)) {
            throw BusinessException("缺少 PROJECT_DELETE 权限", httpStatus = 403, bizCode = 2001)
        }
        recycle.purge(principal.userId, recycleId, ip)
        return ok(null, "已彻底删除")
    }

    fun clearAll(principal: Principal, ip: String): ApiResponse<Int> {
        if (!principal.isSuperAdmin) throw BusinessException("仅超级管理员可强制清空", httpStatus = 403, bizCode = 2001)
        return ok(recycle.clearAll(principal.userId, ip))
    }

    // ---------- /operation-logs ----------

    fun logs(principal: Principal, page: Int, pageSize: Int, action: String?): ApiResponse<Page<LogView>> {
        requireOrgPerm(principal, OrgPermissions.LOG_READ)
        val p = page.coerceAtLeast(1); val ps = pageSize.coerceIn(1, 100)
        val page = transaction {
            val base = OperationLogs.selectAll()
            val filtered = if (action.isNullOrBlank()) base
            else base.where { SqlExpressionBuilder.run { OperationLogs.action eq action } }
            val total = filtered.copy().count()
            val rows = filtered.orderBy(OperationLogs.createdAt to SortOrder.DESC)
                .limit(ps, ((p - 1).toLong() * ps))
                .map {
                    LogView(it[OperationLogs.id], it[OperationLogs.operatorId], it[OperationLogs.action],
                        it[OperationLogs.projectId], it[OperationLogs.targetType], it[OperationLogs.targetId],
                        it[OperationLogs.ipAddress], it[OperationLogs.createdAt].toString())
                }
            Page(rows, total, p, ps)
        }
        return ok(page)
    }

    // ---------- 内部：§2.3 权限校验 ----------

    private fun requireOrgPerm(principal: Principal, perm: String) {
        if (!principal.hasOrgPerm(perm)) {
            throw BusinessException("缺少权限: $perm", httpStatus = 403, bizCode = 2001)
        }
    }

    /**
     * 项目级权限：§2.3 步骤1 SUPER_ADMIN兜底 → 步骤2 成员的项目角色权限。
     * Note #7：临时访客Token中的projectId必须与请求路径一致。
     */
    fun requireProjectPerm(principal: Principal, projectId: Long, perm: String) {
        if (principal.isGuest && principal.claims.projectId != projectId) {
            throw BusinessException("临时访客只能访问所属项目", httpStatus = 403, bizCode = 2002)
        }
        if (principal.isSuperAdmin) return
        val member = membershipOf(principal.userId, projectId)
            ?: throw BusinessException("不是该项目成员", httpStatus = 403, bizCode = 2003)
        val perms = rolePermissions(member.projectRoleId)
        if (perm !in perms) {
            throw BusinessException("缺少项目权限: $perm", httpStatus = 403, bizCode = 2001)
        }
    }

    /** 文件级权限：先定位文件→再走项目级校验（冻结项目写操作403由Service层 guardWritable 保证）。 */
    fun requireFilePerm(principal: Principal, fileId: Long, perm: String) {
        val pid = files.filesRepo().findById(fileId)?.projectId
            ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)
        requireProjectPerm(principal, pid, perm)
    }

    fun membershipOf(userId: Long, projectId: Long): ProjectMembersInfo? = transaction {
        ProjectMembers.selectAll().where {
            SqlExpressionBuilder.run {
                (ProjectMembers.userId eq userId) and (ProjectMembers.projectId eq projectId)
            }
        }.limit(1).map {
            ProjectMembersInfo(it[ProjectMembers.projectRoleId], it[ProjectMembers.isOwner])
        }.firstOrNull()
    }

    fun rolePermissions(roleId: Long): Set<String> = transaction {
        ProjectRoles.selectAll().where { ProjectRoles.id eq roleId }
            .limit(1).firstOrNull()?.get(ProjectRoles.permissions)?.toSet() ?: emptySet()
    }

    private fun hasAnyProjectPerm(userId: Long, perm: String): Boolean = transaction {
        (ProjectMembers innerJoin ProjectRoles).selectAll().where {
            SqlExpressionBuilder.run { ProjectMembers.userId eq userId }
        }.any { perm in it[ProjectRoles.permissions] }
    }
}
