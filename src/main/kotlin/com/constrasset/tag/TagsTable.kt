package com.constrasset.tag

import com.constrasset.infra.timestamptz
import org.jetbrains.exposed.sql.Table

/**
 * §5.1 tags 表 —— 无 path 字段，无 level 字段（深度通过递归 CTE 计算）。
 */
object Tags : Table("tags") {
    val id = long("id").autoIncrement()                 // BIGSERIAL
    val parentId = long("parent_id").nullable()         // FK -> tags.id ON DELETE CASCADE（DDL 见 DatabaseFactory）
    val name = varchar("name", 100)

    override val primaryKey = PrimaryKey(id)
}

/**
 * §5.1 file_tags 多对多关联。ON DELETE CASCADE 双向。
 */
object FileTags : Table("file_tags") {
    val fileId = long("file_id")
    val tagId = long("tag_id")

    override val primaryKey = PrimaryKey(fileId, tagId)
}

/**
 * §5.2 标签树扁平化节点（GET /tags 返回扁平列表，前端按 parentId 组树）。
 */
@kotlinx.serialization.Serializable
data class TagNode(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val depth: Int,
)
