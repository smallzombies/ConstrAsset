package com.constrasset.infra

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * PostgreSQL 专用列扩展（Global Constraint：TIMESTAMPTZ 时间类型 / BIGSERIAL 主键）。
 */

/** TIMESTAMPTZ 列 —— 全链路 UTC 存储。 */
fun Table.timestamptz(name: String): Column<Instant> = registerColumn(name, TimestamptzColumnType())

class TimestamptzColumnType : org.jetbrains.exposed.sql.ColumnType<Instant>() {
    override fun sqlType(): String = "TIMESTAMPTZ"

    override fun nonNullValueToString(value: Instant): String =
        "'${OffsetDateTime.ofInstant(value, ZoneOffset.UTC)}'::timestamptz"

    override fun valueFromDB(value: Any): Instant = when (value) {
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is String -> OffsetDateTime.parse(value).toInstant()
        else -> error("Unsupported timestamptz DB value: ${value::class.java} -> $value")
    }

}

/** JSONB 列（§8 operation_logs.metadata），值以 JSON 字符串形式读写。 */
fun Table.jsonb(name: String): Column<String> = registerColumn(name, PgJsonbColumnType())

class PgJsonbColumnType : org.jetbrains.exposed.sql.ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun nonNullValueToString(value: String): String =
        "'" + value.replace("'", "''") + "'::jsonb"

    override fun valueFromDB(value: Any): String = when (value) {
        is String -> value
        is java.sql.SQLXML -> value.toString() // 驱动实现通常返回 XML/JSON 文本
        else -> value.toString() // PGobject.toJson() 等驱动包装
    }
}

/** TEXT[] 列，用于 org_roles.permissions / project_roles.permissions（§2.1/§2.2） */
fun Table.textArray(name: String): Column<List<String>> = registerColumn(name, PgTextArrayColumnType())

class PgTextArrayColumnType : org.jetbrains.exposed.sql.ColumnType<List<String>>() {
    override fun sqlType(): String = "TEXT[]"

    override fun nonNullValueToString(value: List<String>): String {
        val body = value.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        return "'{$body}'::text[]"
    }

    override fun valueFromDB(value: Any): List<String> = when (value) {
        is Array<*> -> value.map { it?.toString() ?: "" }
        is String -> parsePgArray(value)
        else -> error("Unsupported text[] DB value: ${value::class.java}")
    }

    private fun parsePgArray(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "{}") return emptyList()
        val body = trimmed.removePrefix("{").removeSuffix("}")
        if (body.isEmpty()) return emptyList()
        // 处理带引号元素与转义
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var escaped = false
        for (ch in body) {
            when {
                escaped -> { cur.append(ch); escaped = false }
                ch == '\\' && inQuotes -> escaped = true
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(ch)
            }
        }
        out.add(cur.toString())
        return out
    }

}

/**
 * PostgreSQL 专用 DSL 辅助：Exposed 0.54 核心包未提供 `like` 中缀运算符，
 * 统一使用 ILIKE（大小写不敏感）自定义布尔表达式。
 */
infix fun org.jetbrains.exposed.sql.Expression<String>.ilike(pattern: String): org.jetbrains.exposed.sql.Op<Boolean> =
    object : org.jetbrains.exposed.sql.Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: org.jetbrains.exposed.sql.QueryBuilder) = queryBuilder {
            // pattern 经占位参数绑定，避免 SQL 注入
            append(this@ilike)
            append(" ILIKE ")
            registerArgument(org.jetbrains.exposed.sql.TextColumnType(), pattern)
        }
    }
