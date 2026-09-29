package com.constrasset.file

import com.constrasset.common.BusinessException
import com.constrasset.infra.MinioStorage
import com.constrasset.infra.ObjectKeys
import com.constrasset.infra.ilike
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * §4 文件仓库。软删除通过 recycle_bin + deleted 标记实现：
 * files 表增加逻辑列 `deleted`（见下方 DDL 补充说明），回收站还原时翻转。
 */
class FileRepository(private val minio: MinioStorage) {

    private fun ResultRow.toEntity() = FileEntity(
        id = this[Files.id],
        projectId = this[Files.projectId],
        parentFolderId = this[Files.parentFolderId],
        name = this[Files.name],
        size = this[Files.size],
        mimeType = this[Files.mimeType],
        minioKey = this[Files.minioKey],
        thumbnailKey = this[Files.thumbnailKey],
        exifOrientation = this[Files.exifOrientation],
        uploadedBy = this[Files.uploadedBy],
        approvalStatus = ApprovalStatus.valueOf(this[Files.approvalStatus]),
        createdAt = this[Files.createdAt],
        updatedAt = this[Files.updatedAt],
    )

    fun findById(id: Long): FileEntity? = transaction {
        Files.selectAll().where { (Files.id eq id) and (Files.deleted eq false) }
            .map { it.toEntity() }.firstOrNull()
    }

    /** §4.2 冲突检查：一次 IN 查询返回全部冲突，禁止逐名查询（Critical Note #1） */
    data class Conflict(val fileName: String, val existingSize: Long, val existingUpdatedAt: String)

    fun checkConflicts(projectId: Long, folderId: Long?, fileNames: List<String>): List<Conflict> {
        if (fileNames.isEmpty()) return emptyList()
        return transaction {
            Files.selectAll().where {
                (Files.projectId eq projectId) and
                    (Files.parentFolderId eq folderId) and   // NULL文件夹也走 eq（Exposed生成 IS NULL）
                    (Files.name inList fileNames) and
                    (Files.deleted eq false)
            }.map {
                Conflict(it[Files.name], it[Files.size], it[Files.updatedAt].toString())
            }
        }
    }

    fun nameExistsInFolder(projectId: Long, folderId: Long?, name: String, excludeId: Long? = null): Boolean =
        transaction {
            Files.selectAll().where {
                (Files.projectId eq projectId) and (Files.parentFolderId eq folderId) and
                    (Files.name eq name) and (Files.deleted eq false)
            }.any { excludeId == null || it[Files.id] != excludeId }
        }

    @Serializable
    data class UploadedFileMeta(
        val fileId: Long, val name: String, val size: Long, val mimeType: String,
        val downloadUrl: String, val approvalStatus: String,
    )

    /** 插入文件元数据（事务内调用；MinIO上传在此之前完成） */
    fun insertInTx(
        projectId: Long, folderId: Long?, name: String, size: Long, mimeType: String,
        minioKey: String, exifOrientation: Int, uploadedBy: Long,
    ): Long {
        val now = Instant.now()
        return Files.insert {
            it[Files.projectId] = projectId
            it[Files.parentFolderId] = folderId
            it[Files.name] = name
            it[Files.size] = size
            it[Files.mimeType] = mimeType
            it[Files.minioKey] = minioKey
            it[Files.thumbnailKey] = null
            it[Files.exifOrientation] = exifOrientation
            it[Files.uploadedBy] = uploadedBy
            it[Files.approvalStatus] = ApprovalStatus.PENDING.name
            it[Files.deleted] = false
            it[Files.createdAt] = now
            it[Files.updatedAt] = now
        }[Files.id]
    }

    fun replaceContentInTx(fileId: Long, size: Long, minioKey: String, mimeType: String) {
        Files.update({ Files.id eq fileId }) {
            it[Files.size] = size
            it[Files.minioKey] = minioKey
            it[Files.mimeType] = mimeType
            it[Files.approvalStatus] = ApprovalStatus.PENDING.name // REPLACE后重新进入待审批
            it[Files.updatedAt] = Instant.now()
        }
    }

    fun setThumbnailKeyInTx(fileId: Long, thumbnailKey: String?) {
        Files.update({ Files.id eq fileId }) {
            it[Files.thumbnailKey] = thumbnailKey
            it[Files.updatedAt] = Instant.now()
        }
    }

    fun setApprovalStatusInTx(fileId: Long, status: ApprovalStatus) {
        Files.update({ Files.id eq fileId }) {
            it[Files.approvalStatus] = status.name
            it[Files.updatedAt] = Instant.now()
        }
    }

    /** 软删除 → 进回收站（外层事务包裹，§7） */
    fun softDeleteInTx(fileId: Long) {
        Files.update({ Files.id eq fileId }) {
            it[deleted] = true
            it[updatedAt] = Instant.now()
        }
    }

    fun restoreInTx(fileId: Long, newName: String) {
        Files.update({ Files.id eq fileId }) {
            it[name] = newName
            it[deleted] = false
            it[updatedAt] = Instant.now()
        }
    }

    fun hardDeleteInTx(fileId: Long) {
        Files.deleteWhere(op = { SqlExpressionBuilder.run { Files.id eq fileId } })
    }

    /** §6.3 图片切换列表：同文件夹下图片，updated_at DESC */
    fun imagesOfFolder(projectId: Long, folderId: Long?): List<Pair<Long, String>> = transaction {
        Files.selectAll().where {
            (Files.projectId eq projectId) and (Files.parentFolderId eq folderId) and
                (Files.mimeType ilike "image/%") and (Files.deleted eq false)
        }.orderBy(Files.updatedAt to SortOrder.DESC).map { it[Files.id] to it[Files.name] }
    }

    /** 搜索模式游标：给定有序结果集与当前ID，返回 prev/next */
    fun searchNeighbors(ids: List<Long>, currentId: Long): Pair<Long?, Long?> {
        val idx = ids.indexOf(currentId)
        if (idx < 0) return null to null
        return ids.getOrNull(idx - 1) to ids.getOrNull(idx + 1)
    }

    fun listByProject(projectId: Long, folderId: Long?, page: Int, pageSize: Int): Pair<List<FileEntity>, Long> =
        transaction {
            val q = Files.selectAll().where {
                (Files.projectId eq projectId) and (Files.parentFolderId eq folderId) and (Files.deleted eq false)
            }
            val total = q.count()
            val items = q.orderBy(Files.id to SortOrder.DESC)
                .limit(pageSize, offset = (page - 1).toLong() * pageSize)
                .map { it.toEntity() }
            items to total
        }

    /** 批量取文件（打包下载用，IN查询防N+1） */
    fun findByIds(ids: List<Long>): List<FileEntity> {
        if (ids.isEmpty()) return emptyList()
        return transaction {
            Files.selectAll().where { (Files.id inList ids) and (Files.deleted eq false) }.map { it.toEntity() }
        }
    }

    /** §4.4 打包：folderIds 展开为其下所有未删除文件（一次 IN 查询防 N+1） */
    fun listFilesInFolder(folderId: Long): List<FileEntity> = transaction {
        Files.selectAll().where { (Files.parentFolderId eq folderId) and (Files.deleted eq false) }
            .map { it.toEntity() }
    }

    /** Critical Note #3：一律 Presigned URL，禁止代理流 */
    fun presignedDownloadUrl(file: FileEntity): String = minio.presignedGetUrl(file.minioKey)

    fun buildEntity(row: ResultRow): FileEntity = row.toEntity()
}
