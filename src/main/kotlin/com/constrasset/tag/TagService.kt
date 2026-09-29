package com.constrasset.tag

import com.constrasset.auth.AuditRecorder
import com.constrasset.audit.LogActions
import com.constrasset.audit.TargetTypes
import com.constrasset.common.BusinessException
import com.constrasset.file.Files
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * §5 标签服务。
 * - tags 表无 path/level 字段，深度全部通过祖先链遍历计算（§5.2 语义等价实现）。
 *   因标签树最多 MAX_DEPTH=5 级、节点规模有限，采用"单次全表加载 + 内存 BFS/上溯"，
 *   既满足 Note #1（禁止 N+1），又避免手写原生 SQL 的驱动兼容问题。
 * - 批量打标仅支持 OVERWRITE 模式（§5.3）。
 * - 多表写入必须在 transaction{} 内（Critical Note #2）。
 */
class TagService(private val audit: AuditRecorder) {

    companion object {
        const val MAX_DEPTH = 5
    }

    // ---------- 查询 ----------

    /** GET /tags —— 扁平化全树（含深度）。单次全表查询 + 内存BFS定深，禁止逐层查询（Note #1）。 */
    fun flatTree(): List<TagNode> = transaction<List<TagNode>> {
        val all = Tags.selectAll().map { TagEntity(it[Tags.id], it[Tags.parentId], it[Tags.name]) }
        buildDepthList(all)
    }

    fun findById(tagId: Long): TagEntity? = transaction {
        Tags.selectAll().where { Tags.id eq tagId }.limit(1).map {
            TagEntity(it[Tags.id], it[Tags.parentId], it[Tags.name])
        }.firstOrNull()
    }

    data class TagEntity(val id: Long, val parentId: Long?, val name: String)

    // ---------- 创建（POST /tags, TAG_MANAGE）----------

    fun createTag(name: String, parentId: Long?, operatorId: Long, ip: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) {
            throw BusinessException("标签名称需为1-100字符", httpStatus = 400, bizCode = 5001)
        }
        return transaction {
            if (parentId != null) {
                Tags.selectAll().where { Tags.id eq parentId }.limit(1).firstOrNull()
                    ?: throw BusinessException("父标签不存在", httpStatus = 404, bizCode = 5002)
                // §5.2：新父祖先深度 >= 5 则拒绝；新建节点无 id，不可能构成环
                enforceDepthUnder(parentId)
            }
            val newId = Tags.insert {
                it[Tags.name] = trimmed
                it[Tags.parentId] = parentId
            }[Tags.id]
            audit.recordInTx(operatorId, LogActions.TAG_CREATE, null, TargetTypes.TAG, newId, ip,
                mapOf("name" to trimmed, "parentId" to parentId))
            newId
        }
    }

    // ---------- 编辑（PUT /tags/{id}, TAG_MANAGE）----------

    fun updateTag(tagId: Long, newName: String?, newParentId: Long?, operatorId: Long, ip: String) {
        transaction {
            val row = Tags.selectAll().where { Tags.id eq tagId }.limit(1).firstOrNull()
                ?: throw BusinessException("标签不存在", httpStatus = 404, bizCode = 5002)
            val oldName = row[Tags.name]
            val oldParent = row[Tags.parentId]

            val finalName = newName?.trim()?.ifEmpty { null } ?: oldName
            if (finalName.length > 100) {
                throw BusinessException("标签名称需为1-100字符", httpStatus = 400, bizCode = 5001)
            }

            val finalParent = if (newParentId != null && newParentId != oldParent) {
                // 移动到新父级：新父自身深度必须 < MAX_DEPTH
                Tags.selectAll().where { Tags.id eq newParentId }.limit(1).firstOrNull()
                    ?: throw BusinessException("父标签不存在", httpStatus = 404, bizCode = 5002)
                // 环检测：newParent 不能是 tagId 的后代
                forbidCycle(newParentId, tagId)
                // 子树整体下移后仍须 <= MAX_DEPTH：
                // 新父祖先深度 + 1 + 本子树相对高度 <= 5
                val parentAncDepth = ancestorDepth(newParentId)
                val subHeight = descendantRelativeDepth(tagId) // 含自身，>=1
                if (parentAncDepth + 1 + (subHeight - 1) > MAX_DEPTH) {
                    throw BusinessException("移动后将超过${MAX_DEPTH}级标签深度限制", httpStatus = 400, bizCode = 5003)
                }
                newParentId
            } else oldParent

            if (finalName != oldName || finalParent != oldParent) {
                Tags.update({ Tags.id eq tagId }) {
                    it[Tags.name] = finalName
                    it[Tags.parentId] = finalParent
                }
                audit.recordInTx(operatorId, LogActions.TAG_UPDATE, null, TargetTypes.TAG, tagId, ip,
                    mapOf("oldName" to oldName, "newName" to finalName,
                        "oldParentId" to oldParent, "newParentId" to finalParent))
            }
        }
    }

    // ---------- 删除（DELETE /tags/{id}，级联子孙 + file_tags，§5/Note #2）----------

    fun deleteTag(tagId: Long, operatorId: Long, ip: String): Int {
        return transaction {
            Tags.selectAll().where { Tags.id eq tagId }.limit(1).firstOrNull()
                ?: throw BusinessException("标签不存在", httpStatus = 404, bizCode = 5002)

            // 收集整棵子树（一次递归查询代替数据库级联，以便写审计快照）
            val subtreeIds = collectSubtree(tagId)
            FileTags.deleteWhere(op = { SqlExpressionBuilder.run { FileTags.tagId inList subtreeIds } })
            Tags.deleteWhere(op = { SqlExpressionBuilder.run { Tags.id inList subtreeIds } })
            audit.recordInTx(operatorId, LogActions.TAG_DELETE, null, TargetTypes.TAG, tagId, ip,
                mapOf("cascadedTagIds" to subtreeIds, "count" to subtreeIds.size))
            subtreeIds.size
        }
    }

    /** 收集整棵子树 id（含自身）。单次全表加载 + 内存BFS，代替递归SQL/逐层查询。 */
    private fun collectSubtree(rootId: Long): List<Long> = transaction<List<Long>> {
        val childrenByParent = Tags.selectAll()
            .map { it[Tags.id] to it[Tags.parentId] }
            .groupBy({ it.second }, { it.first })
        val out = mutableListOf(rootId)
        var frontier = listOf(rootId)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { childrenByParent[it].orEmpty() }
            out.addAll(frontier)
        }
        out
    }

    // ---------- 打标 ----------

    /** PUT /api/files/{id}/tags —— 单文件覆盖式打标（§5.3）。 */
    fun setFileTags(fileId: Long, tagIds: List<Long>, operatorId: Long, ip: String) {
        transaction {
            Files.selectAll().where { (Files.id eq fileId) and (Files.deleted eq false) }.limit(1).firstOrNull()
                ?: throw BusinessException("文件不存在", httpStatus = 404, bizCode = 4004)
            validateTagsExist(tagIds)
            applyOverwrite(fileId, tagIds)
            audit.recordInTx(operatorId, LogActions.FILE_TAG, null, TargetTypes.FILE, fileId, ip,
                mapOf("tagIds" to tagIds, "mode" to "OVERWRITE"))
        }
    }

    /** POST /api/projects/{id}/files/batch-tag —— 批量仅支持 OVERWRITE（§5.3）。 */
    fun batchTag(projectId: Long, fileIds: List<Long>, tagIds: List<Long>, mode: String,
                 operatorId: Long, ip: String) {
        if (!mode.equals("OVERWRITE", ignoreCase = true)) {
            throw BusinessException("批量打标签仅支持 OVERWRITE 模式", httpStatus = 400, bizCode = 5004)
        }
        if (fileIds.isEmpty()) {
            throw BusinessException("fileIds 不能为空", httpStatus = 400, bizCode = 5005)
        }
        transaction {
            // 一次 IN 查询校验所有文件归属项目（Note #1 + Note #6 不信任前端）
            val owned = Files.selectAll().where {
                (Files.id inList fileIds) and (Files.projectId eq projectId) and (Files.deleted eq false)
            }.map { it[Files.id] }.toSet()
            val missing = fileIds.toSet() - owned
            if (missing.isNotEmpty()) {
                throw BusinessException("文件不属于该项目或已删除: ${missing.sorted()}",
                    httpStatus = 400, bizCode = 5006)
            }
            validateTagsExist(tagIds)
            for (fid in fileIds) applyOverwrite(fid, tagIds)
            audit.recordInTx(operatorId, LogActions.FILE_TAG, projectId, TargetTypes.FILE, null, ip,
                mapOf("fileIds" to fileIds, "tagIds" to tagIds, "mode" to "OVERWRITE"))
        }
    }

    /** 获取文件的标签ID集合（供详情接口复用，单条 IN 查询）。 */
    fun tagsOfFile(fileId: Long): List<Long> = transaction {
        FileTags.selectAll().where { FileTags.fileId eq fileId }.map { it[FileTags.tagId] }
    }

    // ---------- 内部工具 ----------

    private fun applyOverwrite(fileId: Long, tagIds: List<Long>) {
        FileTags.deleteWhere(op = { SqlExpressionBuilder.run { FileTags.fileId eq fileId } })
        if (tagIds.isNotEmpty()) {
            FileTags.batchInsert(tagIds) { tid ->
                this[FileTags.fileId] = fileId
                this[FileTags.tagId] = tid
            }
        }
    }

    private fun validateTagsExist(tagIds: List<Long>) {
        if (tagIds.isEmpty()) return
        val found = Tags.selectAll().where { Tags.id inList tagIds }.count()
        if (found != tagIds.distinct().size.toLong()) {
            throw BusinessException("存在无效标签ID", httpStatus = 400, bizCode = 5007)
        }
    }

    /** §5.2：新父祖先深度 >= 5 时拒绝在其下创建/挂载。 */
    private fun enforceDepthUnder(newParentId: Long) {
        val d = ancestorDepth(newParentId)
        if (d >= MAX_DEPTH) {
            throw BusinessException("标签最多 $MAX_DEPTH 级，无法在深度 $d 的标签下继续创建",
                httpStatus = 400, bizCode = 5003)
        }
    }

    /** §5.2：nodeId 的祖先链深度（自身=1）。单次全表加载后内存上溯。 */
    private fun ancestorDepth(nodeId: Long): Int = transaction<Int> {
        val parentOf = Tags.selectAll().associate { it[Tags.id] to it[Tags.parentId] }
        var depth = 0
        var cur: Long? = nodeId
        val guard = mutableSetOf<Long>()
        while (cur != null && guard.add(cur)) {
            depth++
            cur = parentOf[cur]
        }
        depth
    }

    /** 以 rootId 为根的后代子树相对高度（含自身，>=1）。 */
    private fun descendantRelativeDepth(rootId: Long): Int = transaction<Int> {
        val childrenByParent = Tags.selectAll()
            .map { it[Tags.id] to it[Tags.parentId] }
            .groupBy({ it.second }, { it.first })
        var height = 0
        var frontier = listOf(rootId)
        while (frontier.isNotEmpty()) {
            height++
            frontier = frontier.flatMap { childrenByParent[it].orEmpty() }
        }
        height.coerceAtLeast(1)
    }

    /** 移动场景环检测：candidateParent 不得位于 nodeId 的子树内。 */
    private fun forbidCycle(candidateParentId: Long, nodeId: Long) {
        if (candidateParentId == nodeId) {
            throw BusinessException("不能将标签的父级指向自身", httpStatus = 400, bizCode = 5008)
        }
        var cursor: Long? = candidateParentId
        val guard = mutableSetOf<Long>()
        while (cursor != null) {
            val c = cursor
            if (!guard.add(c)) break // 数据本身已有环（理论不可能），终止防死循环
            if (c == nodeId) {
                throw BusinessException("不能将标签移动到自己的子孙下（形成环）", httpStatus = 400, bizCode = 5009)
            }
            cursor = Tags.selectAll().where { Tags.id eq c }.limit(1)
                .firstOrNull()?.get(Tags.parentId)
        }
    }

    /** 由扁平节点集合构建带 depth 的有序列表（BFS 序，同层按名称排序）。 */
    private fun buildDepthList(all: List<TagEntity>): List<TagNode> {
        val childrenByParent = all.groupBy({ it.parentId }, { it })
        val roots = childrenByParent[null].orEmpty().sortedBy { it.name }
        val out = mutableListOf<TagNode>()
        var frontier = roots.map { it to 1 }
        while (frontier.isNotEmpty()) {
            out.addAll(frontier.map { TagNode(it.first.id, it.first.parentId, it.first.name, it.second) })
            frontier = frontier.flatMap { (node, d) ->
                childrenByParent[node.id].orEmpty().sortedBy { it.name }.map { it to d + 1 }
            }
        }
        return out
    }
}
