package com.constrasset.file

import com.constrasset.audit.LogActions
import com.constrasset.audit.TargetTypes
import com.constrasset.auth.AuditRecorder
import com.constrasset.common.BusinessException
import com.constrasset.infra.MinioStorage
import com.constrasset.infra.ObjectKeys
import com.constrasset.infra.RedisClient
import com.constrasset.infra.RedisKeys
import com.constrasset.project.ProjectRepository
import com.constrasset.project.ProjectStatus
import com.constrasset.project.guardWritable
import com.constrasset.recycle.RecycleBinRepository
import com.constrasset.recycle.RecycleSourceType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * §4.2/§4.3 上传服务。
 * Critical Note #4：上传接口同步返回文件元数据，缩略图生成通过 Channel 异步处理。
 * Global Constraint：所有IO在协程中执行（Handler层用 withContext(Dispatchers.IO) 包裹本服务调用）。
 */
class FileService(
    private val files: FileRepository,
    private val projects: ProjectRepository,
    private val minio: MinioStorage,
    private val redis: RedisClient,
    private val recycle: RecycleBinRepository,
    private val audit: AuditRecorder,
    thumbnailScope: CoroutineScope,
) {
    /** 缩略图任务队列（有界背压；生产环境可替换为外部MQ） */
    val thumbnailChannel = Channel<Long>(capacity = 1024)
    private val pipeline = ThumbnailPipeline(files, minio, redis, thumbnailScope, thumbnailChannel)

    data class UploadItem(val fileName: String, val content: ByteArray, val mimeType: String)

    data class UploadResult(
        val uploaded: List<FileRepository.UploadedFileMeta>,
        val skipped: List<String>,
    )

    fun requireActiveProject(projectId: Long): ProjectRepository.ProjectView {
        val p = projects.findById(projectId)
            ?: throw BusinessException("项目不存在", httpStatus = 404, bizCode = 3003)
        ProjectStatus.valueOf(p.status).guardWritable("上传")
        return p
    }

    /** POST /api/projects/{id}/upload/check-conflict（§4.2） */
    fun checkConflicts(projectId: Long, folderId: Long?, fileNames: List<String>) =
        files.checkConflicts(projectId, folderId, fileNames)

    /**
     * POST /api/projects/{id}/upload —— 携带 X-Conflict-Strategy（§4.2）。
     * 流程：逐文件按策略处理 → MinIO put → 事务内写files → 投递缩略图任务。
     */
    suspend fun upload(
        projectId: Long, folderId: Long?, uploaderId: Long, ip: String,
        items: List<UploadItem>, strategyHeader: String?,
    ): UploadResult {
        requireActiveProject(projectId)
        if (items.isEmpty()) throw BusinessException("无上传内容", httpStatus = 400, bizCode = 4002)
        val strategy = ConflictStrategy.parse(strategyHeader)
        val uploaded = mutableListOf<FileRepository.UploadedFileMeta>()
        val skipped = mutableListOf<String>()

        for (item in items) {
            val conflict = files.checkConflicts(projectId, folderId, listOf(item.fileName))
                .firstOrNull()
            when {
                conflict == null -> { // 无冲突 → 新增
                    val key = ObjectKeys.raw(projectId, -System.currentTimeMillis(), item.fileName)
                    minio.putObject(key, item.content, item.mimeType)
                    val fileId = transaction {
                        files.insertInTx(projectId, folderId, item.fileName, item.content.size.toLong(),
                            item.mimeType, key, exifOrientation = 0, uploadedBy = uploaderId)
                    }
                    // 回填规范key（fileId_{name}），避免二次网络往返可保留原key；此处直接记录
                    pipeline.enqueue(fileId)
                    uploaded += metaOf(fileId)
                    audit.record(uploaderId, LogActions.FILE_UPLOAD, projectId, TargetTypes.FILE, fileId, ip,
                        mapOf("fileName" to item.fileName))
                }
                strategy is ConflictStrategy.SkipAll -> skipped += item.fileName
                strategy is ConflictStrategy.ReplaceAll ||
                    (strategy is ConflictStrategy.ReplaceSet && item.fileName in strategy.names) -> {
                    // REPLACE：覆盖MinIO对象 + 重置审批状态
                    minio.putObject(conflictKey(projectId, conflict.fileName), item.content, item.mimeType)
                    val existing = findExisting(projectId, folderId, conflict.fileName)
                    val existingId = existing?.id ?: throw BusinessException(
                        "冲突文件记录缺失：${conflict.fileName}", httpStatus = 409, bizCode = 4004)
                    transaction {
                        files.replaceContentInTx(existingId, item.content.size.toLong(),
                            conflictKey(projectId, conflict.fileName), item.mimeType)
                    }
                    pipeline.enqueue(existingId)
                    uploaded += metaOf(existingId)
                    audit.record(uploaderId, LogActions.FILE_UPLOAD, projectId, TargetTypes.FILE, existingId, ip,
                        mapOf("fileName" to item.fileName, "replaced" to true))
                }
                else -> { // FailFast
                    throw BusinessException(
                        "文件 ${item.fileName} 已存在，请先调用 check-conflict 并携带处理策略",
                        httpStatus = 409, bizCode = 4003)
                }
            }
        }
        return UploadResult(uploaded, skipped)
    }

    private fun conflictKey(projectId: Long, fileName: String): String =
        "projects/$projectId/files/conflict_$fileName"

    private fun findExisting(projectId: Long, folderId: Long?, name: String): FileEntity? =
        files.listByProject(projectId, folderId, 1, Int.MAX_VALUE).first.firstOrNull { it.name == name }

    private fun metaOf(fileId: Long): FileRepository.UploadedFileMeta {
        val f = files.findById(fileId)!!
        return FileRepository.UploadedFileMeta(
            fileId, f.name, f.size, f.mimeType,
            downloadUrl = files.presignedDownloadUrl(f), approvalStatus = f.approvalStatus.name,
        )
    }

    /** DELETE 文件 → 进回收站（§7；事务：软删+回收站条目） */
    fun deleteFile(operatorId: Long, fileId: Long, ip: String) {
        val f = files.findById(fileId)
            ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)
        requireActiveProject(f.projectId)
        transaction {
            files.softDeleteInTx(fileId)
            recycle.insertInTx(RecycleSourceType.FILE, fileId, f.name, operatorId)
            audit.recordInTx(operatorId, LogActions.FILE_DELETE, f.projectId, TargetTypes.FILE, fileId, ip, null)
        }
    }

    /** GET 下载/预览：Presigned URL（Critical Note #3） */
    fun downloadUrl(fileId: Long): String {
        val f = files.findById(fileId)
            ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)
        return files.presignedDownloadUrl(f)
    }

    /**
     * §4.4 打包下载：POST /api/projects/{id}/pack → 异步ZIP流式写入MinIO →
     * Redis `pack:{projectId}:{packId}` TTL=7天；过期由定时任务删除MinIO对象。
     * 立即返回 packId，客户端轮询 /pack/{packId}/status。
     */
    fun startPack(operatorId: Long, projectId: Long, fileIds: List<Long>, folderIds: List<Long>, ip: String): String {
        requireActiveProject(projectId)
        if (fileIds.isEmpty() && folderIds.isEmpty())
            throw BusinessException("fileIds与folderIds不能同时为空", httpStatus = 400, bizCode = 4005)
        val packId = "${Instant.now().epochSecond}_${operatorId}"
        val ts = System.currentTimeMillis()
        val zipKey = ObjectKeys.pack(projectId, ts)
        redis.setWithTtl(RedisKeys.pack(projectId, packId), zipKey, 7 * 24 * 3600)
        pipeline.enqueuePack(PackJob(projectId, packId, zipKey, fileIds, folderIds))
        audit.record(operatorId, LogActions.FILE_PACK, projectId, TargetTypes.PROJECT, projectId, ip,
            mapOf("packId" to packId, "fileCount" to fileIds.size, "folderCount" to folderIds.size))
        return packId
    }

    fun packStatus(projectId: Long, packId: String): Map<String, String?> {
        val key = redis.get(RedisKeys.pack(projectId, packId))
            ?: return mapOf("status" to "NOT_FOUND_OR_EXPIRED", "url" to null)
        val done = redis.exists("pack:$projectId:$packId:done")
        return mapOf(
            "status" to if (done) "READY" else "PROCESSING",
            "url" to if (done) minio.presignedGetUrl(key) else null,
        )
    }

    internal fun completePack(zipKey: String, projectId: Long, packId: String) {
        redis.setWithTtl("pack:$projectId:$packId:done", "1", 7 * 24 * 3600)
    }

    fun filesRepo() = files
    fun minioStorage() = minio
    fun redisClient() = redis
}
