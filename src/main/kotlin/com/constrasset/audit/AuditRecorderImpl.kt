package com.constrasset.audit

import com.constrasset.auth.AuditRecorder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * §8 AuditRecorder 实现。
 *
 * - record(): 独立事务写入（插件/AOP 拦截器调用，业务代码禁止手动调用）。
 *   写入失败仅记录到 stderr，绝不向外抛出 —— 审计日志故障不得影响主业务流程。
 * - recordInTx(): 在调用方已开启的 transaction{} 块内同步写入，
 *   用于多表一致性场景（注销/转移/还原，Critical Note #2），失败随主事务回滚。
 *
 * metadata 序列化使用 kotlinx.serialization JsonElement（Global Constraint），
 * 并在落库前执行敏感信息脱敏（Critical Note #9）：
 * 键名命中 password / token / secret / credential / cookie / authorization 的值一律替换为 "***"。
 */
class AuditRecorderImpl : AuditRecorder {

    override fun record(
        operatorId: Long, action: String, projectId: Long?, targetType: String?,
        targetId: Long?, ip: String, metadata: Map<String, Any?>?,
    ) {
        try {
            transaction {
                writeRow(operatorId, action, projectId, targetType, targetId, ip, metadata)
            }
        } catch (e: Exception) {
            // 审计写入失败不阻断业务；生产环境应接入告警
            System.err.println("[AUDIT] failed to write log action=$action operator=$operatorId: ${e.message}")
        }
    }

    override fun recordInTx(
        operatorId: Long, action: String, projectId: Long?, targetType: String?,
        targetId: Long?, ip: String, metadata: Map<String, Any?>?,
    ) {
        // 必须在调用方事务内执行；无事务时立即暴露编码错误（Critical Note #2）
        check(org.jetbrains.exposed.sql.transactions.TransactionManager.currentOrNull() != null) {
            "recordInTx must be called inside transaction {} (Critical Note #2)"
        }
        writeRow(operatorId, action, projectId, targetType, targetId, ip, metadata)
    }

    private fun writeRow(
        operatorId: Long, action: String, projectId: Long?, targetType: String?,
        targetId: Long?, ip: String, metadata: Map<String, Any?>?,
    ) {
        val json = metadata?.let { sanitize(it).toString() }
        OperationLogs.insert {
            it[OperationLogs.operatorId] = operatorId
            it[OperationLogs.action] = action.take(50)
            it[OperationLogs.projectId] = projectId
            it[OperationLogs.targetType] = targetType?.take(20)
            it[OperationLogs.targetId] = targetId
            it[OperationLogs.ipAddress] = ip.take(45)
            it[OperationLogs.metadata] = json
        }
    }

    // ---- 脱敏（Critical Note #9）----

    private val sensitiveKeyRegex = Regex(
        "(?i)(password|passwd|pwd|token|secret|credential|authorization|cookie|session|apikey|api_key|refresh)"
    )

    /** 递归脱敏 Map -> JsonObject。 */
    internal fun sanitize(map: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in map) {
            if (sensitiveKeyRegex.containsMatchIn(k)) {
                put(k, "***")
            } else {
                put(k, sanitizeValue(v))
            }
        }
    }

    private fun sanitizeValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(buildMap<String, JsonElement> {
            for ((k, sub) in v) {
                val key = k?.toString() ?: "null"
                @Suppress("UNCHECKED_CAST")
                val child = sub as? Map<String, Any?>
                // 嵌套层同样按规则处理：命中敏感键则置为 "***"
                put(key, if (sensitiveKeyRegex.containsMatchIn(key)) JsonPrimitive("***")
                        else if (child != null) sanitize(child) else sanitizeValue(sub))
            }
        })
        is Iterable<*> -> JsonArray(v.map { sanitizeValue(it) })
        is Array<*> -> JsonArray(v.map { sanitizeValue(it) })
        else -> JsonPrimitive(v.toString())
    }
}
