package com.constrasset.common

import kotlinx.serialization.Serializable

/**
 * 全局统一响应包装 (Global Constraint): `{ code: Int, message: String, data: T? }`
 */
@Serializable
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
)

inline fun <reified T> ok(data: T?, message: String = "success"): ApiResponse<T> =
    ApiResponse(0, message, data)

fun <T> fail(code: Int, message: String): ApiResponse<T?> = ApiResponse(code, message, null)

/**
 * 分页载荷。列表接口一律返回 Page<T>（禁止N+1，Critical Note #1）。
 */
@Serializable
data class Page<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

/**
 * 业务异常：由 StatusPages 统一转换为 `ApiResponse{code!=0}`。
 * code 约定：
 *  - HTTP 语义错误使用 4xx/5xx 对应值（401/403/404/409...）
 *  - 细粒度业务码放在 bizCode（可选，前端用于特殊提示）
 */
open class BusinessException(
    override val message: String,
    val httpStatus: Int = 400,
    val bizCode: Int = 1000,
) : RuntimeException(message)

class ValidationException(fields: Map<String, String>) :
    BusinessException("参数校验失败: ${fields.entries.joinToString("; ") { "${it.key}=${it.value}" }}", httpStatus = 422)
