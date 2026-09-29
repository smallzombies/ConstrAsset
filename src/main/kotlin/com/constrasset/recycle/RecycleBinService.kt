package com.constrasset.recycle

import com.constrasset.audit.LogActions
import com.constrasset.audit.TargetTypes
import com.constrasset.auth.AuditRecorder
import com.constrasset.common.BusinessException
import com.constrasset.file.FileRepository
import com.constrasset.infra.MinioStorage
import com.constrasset.project.ProjectRepository
import com.constrasset.project.ProjectStatus
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * §7 回收站仓库 + 业务。
 * Critical Note #8：查询始终加 `expire_at > NOW()`，不依赖定时任务保证精确性。
 */
class RecycleBinRepository {

    @Serializable
    data class RecycleItem(
        val id: Long,
        val sourceType: String,
        val sourceId: Long,
        val originalName: String,
        val deletedBy: Long,
        val deletedAt: String,
        val expireAt: String,
    )

    /** 写入回收站（事务内调用）。expire_at = deleted_at + 30天（§7.1） */
    fun insertInTx(sourceType: RecycleSourceType, sourceId: Long, originalName: String, deletedBy: Long): Long {
        val now = Instant.now()
        return RecycleBin.insert {
            it[RecycleBin.sourceType] = sourceType.name
            it[RecycleBin.sourceId] = sourceId
            it[RecycleBin.originalName] = originalName
            it[RecycleBin.deletedBy] = deletedBy
            it[RecycleBin.deletedAt] = now
            it[RecycleBin.expireAt] = now.plus(30, ChronoUnit.DAYS)
            it[RecycleBin.restored] = false
        }[RecycleBin.id]
    }

    private fun aliveCondition(): Op<Boolean> =
        with(SqlExpressionBuilder) {
            (RecycleBin.restored eq false) and (RecycleBin.expireAt greater EqNow)
        }

    /** 自定义表达式：NOW()，由PG服务器求值，避免应用时钟漂移（Critical Note #8）。
     *  声明为 Instant 类型以匹配 timestamptz 列的比较运算符签名。 */
    object EqNow : org.jetbrains.exposed.sql.Expression<java.time.Instant>() {
        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) =
            queryBuilder { append("NOW()") }
    }

    /**
     * GET /recycle-bin —— 按权限过滤：
     * SUPER_ADMIN看全部；其他用户只看自己删除的条目（deleted_by = me）。
     */
    fun list(userId: Long, isSuperAdmin: Boolean, page: Int, pageSize: Int): Pair<List<RecycleItem>, Long> =
        transaction {
            val q = if (isSuperAdmin) {
                RecycleBin.selectAll().where { aliveCondition() }
            } else {
                RecycleBin.selectAll().where { aliveCondition() and (RecycleBin.deletedBy eq userId) }
            }
            val total = q.count()
            val items = q.orderBy(RecycleBin.id to SortOrder.DESC)
                .limit(pageSize, offset = (page - 1).toLong() * pageSize)
                .map {
                    RecycleItem(
                        id = it[RecycleBin.id], sourceType = it[RecycleBin.sourceType],
                        sourceId = it[RecycleBin.sourceId], originalName = it[RecycleBin.originalName],
                        deletedBy = it[RecycleBin.deletedBy], deletedAt = it[RecycleBin.deletedAt].toString(),
                        expireAt = it[RecycleBin.expireAt].toString(),
                    )
                }
            items to total
        }

    fun findById(id: Long): RecycleItem? = transaction {
        RecycleBin.selectAll().where { (RecycleBin.id eq id) and aliveCondition() }.map {
            RecycleItem(
                id = it[RecycleBin.id], sourceType = it[RecycleBin.sourceType],
                sourceId = it[RecycleBin.sourceId], originalName = it[RecycleBin.originalName],
                deletedBy = it[RecycleBin.deletedBy], deletedAt = it[RecycleBin.deletedAt].toString(),
                expireAt = it[RecycleBin.expireAt].toString(),
            )
        }.firstOrNull()
    }

    fun markRestoredInTx(id: Long) {
        RecycleBin.update({ RecycleBin.id eq id }) { it[RecycleBin.restored] = true }
    }

    /** 硬过期条目（供定时清理，§7.3）：expire_at < NOW() AND restored=false */
    fun findExpired(): List<RecycleItem> = transaction {
        RecycleBin.selectAll().where {
            (RecycleBin.restored eq false) and (RecycleBin.expireAt less EqNow)
        }.map {
            RecycleItem(
                id = it[RecycleBin.id], sourceType = it[RecycleBin.sourceType],
                sourceId = it[RecycleBin.sourceId], originalName = it[RecycleBin.originalName],
                deletedBy = it[RecycleBin.deletedBy], deletedAt = it[RecycleBin.deletedAt].toString(),
                expireAt = it[RecycleBin.expireAt].toString(),
            )
        }
    }

    fun deleteRowsInTx(ids: List<Long>) {
        if (ids.isEmpty()) return
        RecycleBin.deleteWhere(op = { SqlExpressionBuilder.run { RecycleBin.id inList ids } })
    }

    fun allAliveItems(): List<RecycleItem> = transaction {
        RecycleBin.selectAll().where { aliveCondition() }.map {
            RecycleItem(
                id = it[RecycleBin.id], sourceType = it[RecycleBin.sourceType],
                sourceId = it[RecycleBin.sourceId], originalName = it[RecycleBin.originalName],
                deletedBy = it[RecycleBin.deletedBy], deletedAt = it[RecycleBin.deletedAt].toString(),
                expireAt = it[RecycleBin.expireAt].toString(),
            )
        }
    }

    fun clearAllInTx(): Int = RecycleBin.deleteWhere(op = { Op.TRUE })
}

/**
 * §7.2 还原冲突处理 + §7.3 二次删除/清空。
 */
class RecycleBinService(
    private val repo: RecycleBinRepository,
    private val projects: ProjectRepository,
    private val files: FileRepository,
    private val minio: MinioStorage,
    private val audit: AuditRecorder,
) {

    /** POST /recycle-bin/{id}/restore */
    fun restore(operatorId: Long, recycleBinId: Long, ip: String): String {
        val item = repo.findById(recycleBinId)
            ?: throw BusinessException("回收站条目不存在或已过期", httpStatus = 404, bizCode = 7001)

        return when (RecycleSourceType.valueOf(item.sourceType)) {
            RecycleSourceType.PROJECT -> {
                transaction {
                    // 项目名全局唯一(code)但name可重复；还原仅需状态回ACTIVE
                    projects.setStatusInTx(item.sourceId, ProjectStatus.ACTIVE)
                    repo.markRestoredInTx(recycleBinId)
                    audit.recordInTx(operatorId, LogActions.RECYCLE_RESTORE, item.sourceId,
                        TargetTypes.PROJECT, item.sourceId, ip, null)
                }
                item.originalName
            }
            RecycleSourceType.FILE -> {
                var finalName = item.originalName
                transaction {
                    val file = files.findById(item.sourceId)
                        ?: throw BusinessException("文件记录已被彻底删除", httpStatus = 410, bizCode = 7002)
                    // §7.2 existsConflict → "restore_" 前缀
                    if (files.nameExistsInFolder(file.projectId, file.parentFolderId, item.originalName, excludeId = item.sourceId)) {
                        finalName = "restore_${item.originalName}"
                    }
                    files.restoreInTx(item.sourceId, finalName)
                    repo.markRestoredInTx(recycleBinId)
                    audit.recordInTx(operatorId, LogActions.RECYCLE_RESTORE, file.projectId,
                        TargetTypes.FILE, item.sourceId, ip, mapOf("restoredName" to finalName))
                }
                finalName
            }
        }
    }

    /** DELETE /recycle-bin/{id} —— PROJECT_DELETE 权限；物理删除源对象+MinIO文件 */
    fun purge(operatorId: Long, recycleBinId: Long, ip: String) {
        val item = repo.findById(recycleBinId)
            ?: throw BusinessException("回收站条目不存在或已过期", httpStatus = 404, bizCode = 7001)
        transaction {
            when (RecycleSourceType.valueOf(item.sourceType)) {
                RecycleSourceType.PROJECT -> { /* 项目本体保留DELETED状态，等待级联清理 */ }
                RecycleSourceType.FILE -> {
                    val f = files.findById(item.sourceId)
                    if (f != null) {
                        runCatching { minio.removeObject(f.minioKey) }   // 同步删除MinIO对象
                        f.thumbnailKey?.let { runCatching { minio.removeObject(it) } }
                        files.hardDeleteInTx(item.sourceId)
                    }
                }
            }
            repo.deleteRowsInTx(listOf(recycleBinId))
            audit.recordInTx(operatorId, LogActions.RECYCLE_PURGE, null,
                item.sourceType, item.sourceId, ip, null)
        }
    }

    /** DELETE /recycle-bin/clear-all —— SUPER_ADMIN 强制清空（含MinIO对象） */
    fun clearAll(operatorId: Long, ip: String): Int {
        val items = repo.allAliveItems()
        var n = 0
        transaction {
            items.forEach { item ->
                if (item.sourceType == RecycleSourceType.FILE.name) {
                    val f = files.findById(item.sourceId)
                    if (f != null) {
                        runCatching { minio.removeObject(f.minioKey) }
                        f.thumbnailKey?.let { runCatching { minio.removeObject(it) } }
                        files.hardDeleteInTx(item.sourceId)
                    }
                }
            }
            n = repo.clearAllInTx()
            audit.recordInTx(operatorId, LogActions.RECYCLE_CLEAR_ALL, null, null, null, ip,
                mapOf("clearedCount" to n))
        }
        return n
    }

    /**
     * §7.3 定时清理（每小时）：expire_at < NOW() AND restored=false，同步删除MinIO对象。
     * 使用Redis分布式锁防止多实例重复执行。
     */
    suspend fun scheduledCleanup(lockedBy: () -> Boolean) {
        if (!lockedBy()) return
        val expired = repo.findExpired()
        if (expired.isEmpty()) return
        expired.filter { it.sourceType == RecycleSourceType.FILE.name }.forEach { item ->
            val f = files.findById(item.sourceId)
            if (f != null) {
                runCatching { minio.removeObject(f.minioKey) }
                f.thumbnailKey?.let { runCatching { minio.removeObject(it) } }
                transaction { files.hardDeleteInTx(item.sourceId) }
            }
        }
        transaction { repo.deleteRowsInTx(expired.map { it.id }) }
    }
}
