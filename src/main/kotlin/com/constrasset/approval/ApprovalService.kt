package com.constrasset.approval

import com.constrasset.auth.AuditRecorder
import com.constrasset.audit.LogActions
import com.constrasset.audit.TargetTypes
import com.constrasset.common.BusinessException
import com.constrasset.file.ApprovalStatus
import com.constrasset.file.FileRepository
import com.constrasset.infra.timestamptz
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * §6 图片审批 + 评论业务逻辑。
 * 表定义见 ApprovalTables.kt（ApprovalRecords / Comments）。
 */
class ApprovalService(
    private val files: FileRepository,
    private val audit: AuditRecorder,
) {

    // ---------- §6.2 审批提交 ----------

    @Serializable
    data class ApprovalRequest(val result: String, val remark: String? = null)

    /**
     * POST /api/files/{id}/approve。
     * 校验规则（§6.2）：REJECTED 必填备注；APPROVED 忽略备注（置 NULL）。
     * 多表写入 → transaction{}（Critical Note #2）。
     */
    fun submit(fileId: Long, req: ApprovalRequest, approverId: Long, ip: String) {
        val result = runCatching { ApprovalResult.valueOf(req.result.uppercase()) }
            .getOrElse {
                throw BusinessException("result 必须为 APPROVED / REJECTED", httpStatus = 400, bizCode = 6001)
            }
        if (result == ApprovalResult.REJECTED && req.remark.isNullOrBlank()) {
            throw BusinessException("不合格必须填写审批备注", httpStatus = 400, bizCode = 6002)
        }
        val finalRemark = if (result == ApprovalResult.APPROVED) null else req.remark?.trim()

        transaction {
            files.findById(fileId)
                ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)

            ApprovalRecords.insert {
                it[ApprovalRecords.fileId] = fileId
                it[ApprovalRecords.approverId] = approverId
                it[ApprovalRecords.result] = result.name
                it[ApprovalRecords.remark] = finalRemark
            }
            files.setApprovalStatusInTx(
                fileId,
                if (result == ApprovalResult.APPROVED) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            )
            audit.recordInTx(approverId, LogActions.FILE_APPROVE, null, TargetTypes.FILE, fileId, ip,
                mapOf("result" to result.name, "remarkLength" to (finalRemark?.length ?: 0)))
        }
    }

    /** 文件的审批历史（GET /files/{id}/detail 复用）。 */
    fun historyOf(fileId: Long): List<ApprovalRecordView> = transaction {
        ApprovalRecords.selectAll().where { ApprovalRecords.fileId eq fileId }
            .orderBy(ApprovalRecords.createdAt to SortOrder.DESC)
            .map {
                ApprovalRecordView(
                    id = it[ApprovalRecords.id],
                    approverId = it[ApprovalRecords.approverId],
                    result = it[ApprovalRecords.result],
                    remark = it[ApprovalRecords.remark],
                    createdAt = it[ApprovalRecords.createdAt].toString(),
                )
            }
    }

    @Serializable
    data class ApprovalRecordView(
        val id: Long, val approverId: Long, val result: String,
        val remark: String?, val createdAt: String,
    )

    // ---------- §6.3 图片切换列表 ----------

    @Serializable
    data class ImageNav(
        val list: List<ImageItem>,
        val prevFileId: Long?,
        val nextFileId: Long?,
    )

    @Serializable
    data class ImageItem(val fileId: Long, val name: String)

    /**
     * 正常模式：同文件夹下全部图片（updated_at DESC 由仓储排序）。
     * 前端传 currentFileId → 返回 prev/next 游标。
     */
    fun folderImageNav(projectId: Long, folderId: Long?, currentFileId: Long?): ImageNav {
        val imgs = files.imagesOfFolder(projectId, folderId)
        val ids = imgs.map { it.first }
        val (prev, next) = if (currentFileId == null) Pair(null, null)
        else files.searchNeighbors(ids, currentFileId)
        return ImageNav(imgs.map { ImageItem(it.first, it.second) }, prev, next)
    }

    /** 搜索模式：在给定结果集内做 prev/next（复用搜索条件产出的有序ID列表）。 */
    fun searchImageNav(matchedIds: List<Long>, matchedNames: Map<Long, String>, currentFileId: Long): ImageNav {
        val (prev, next) = files.searchNeighbors(matchedIds, currentFileId)
        return ImageNav(matchedIds.map { ImageItem(it, matchedNames[it] ?: "") }, prev, next)
    }

    // ---------- 评论（FILE_COMMENT）----------

    @Serializable
    data class CommentRequest(val content: String, val parentCommentId: Long? = null)

    @Serializable
    data class CommentView(
        val id: Long, val fileId: Long, val userId: Long, val username: String,
        val content: String, val parentCommentId: Long?, val mentionedUserIds: List<Long>,
        val createdAt: String,
    )

    /** GET /api/files/{id}/comments —— 单次批量查询，禁止 N+1（Note #1）。 */
    fun listComments(fileId: Long): List<CommentView> = transaction {
        val rows = Comments.selectAll().where { Comments.fileId eq fileId }
            .orderBy(Comments.createdAt to SortOrder.ASC).toList()
        if (rows.isEmpty()) return@transaction emptyList()

        // 批量 IN 拉取作者用户名
        val authorIds = rows.map { it[Comments.userId] }.distinct()
        val names = userNamesByIds(authorIds)

        // @提及解析：一次性拉取所有被提及用户名对应ID
        val allMentions = rows.flatMap { parseMentions(it[Comments.content]) }.distinct()
        val mentionIds = if (allMentions.isEmpty()) emptyMap() else userIdsByNames(allMentions)

        rows.map { r ->
            val mentions = parseMentions(r[Comments.content])
                .mapNotNull { mentionIds[it.lowercase()] }
            CommentView(
                id = r[Comments.id],
                fileId = r[Comments.fileId],
                userId = r[Comments.userId],
                username = names[r[Comments.userId]] ?: "unknown",
                content = r[Comments.content],
                parentCommentId = r[Comments.parentCommentId],
                mentionedUserIds = mentions,
                createdAt = r[Comments.createdAt].toString(),
            )
        }
    }

    /** POST /api/files/{id}/comments —— 纯文本内容，支持 @username。 */
    fun addComment(fileId: Long, req: CommentRequest, userId: Long, ip: String): Long {
        val content = req.content.trim()
        if (content.isEmpty() || content.length > 2000) {
            throw BusinessException("评论内容不能为空且不超过2000字符", httpStatus = 400, bizCode = 6003)
        }
        return transaction {
            files.findById(fileId)
                ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)
            req.parentCommentId?.let { pid ->
                val exists = Comments.selectAll().where { Comments.id eq pid }.count()
                if (exists == 0L) throw BusinessException("父评论不存在", httpStatus = 400, bizCode = 6004)
            }
            val newId = Comments.insert {
                it[Comments.fileId] = fileId
                it[Comments.userId] = userId
                it[Comments.content] = content
                it[Comments.parentCommentId] = req.parentCommentId
            }[Comments.id]
            audit.recordInTx(userId, "FILE_COMMENT", null, TargetTypes.FILE, fileId, ip,
                mapOf("commentId" to newId, "mentions" to parseMentions(content)))
            newId
        }
    }

    companion object {
        /** @username：前导非字母数字下划线连字符，与用户名正则 ^[a-zA-Z_][a-zA-Z0-9_-]{5,19}$ 对齐 */
        private val MENTION_REGEX = Regex("""(?<![0-9a-zA-Z_\-])@([a-zA-Z_][a-zA-Z0-9_\-]{5,19})""")

        internal fun parseMentions(content: String): List<String> =
            MENTION_REGEX.findAll(content).map { it.groupValues[1] }.toList()

        private fun userNamesByIds(ids: List<Long>): Map<Long, String> {
            val users = com.constrasset.auth.Users
            if (ids.isEmpty()) return emptyMap()
            return transaction {
                users.selectAll().where { users.id inList ids }
                    .associate { it[users.id] to it[users.username] }
            }
        }

        private fun userIdsByNames(names: List<String>): Map<String, Long> {
            val users = com.constrasset.auth.Users
            if (names.isEmpty()) return emptyMap()
            return transaction {
                users.selectAll().where {
                    SqlExpressionBuilder.run { users.username.lowerCase() inList names.map { n -> n.lowercase() } }
                }.associate { it[users.username].lowercase() to it[users.id] }
            }
        }
    }
}
