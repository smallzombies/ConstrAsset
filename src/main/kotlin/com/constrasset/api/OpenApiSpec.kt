package com.constrasset.api

/**
 * OpenAPI 3.0 规范（§9 API端点清单），由 /openapi.json 暴露，/docs 渲染 Swagger UI。
 * 手写为 JSON 字符串以避免引入额外 codegen 依赖（约束：序列化仅 kotlinx）。
 */
object OpenApiSpec {

    private val pathsJson: String by lazy {
        buildString {
            // ---------- Auth & Users ----------
            path("post", "/auth/login", "登录，返回AccessToken+RefreshToken", "Public", req = "LoginRequest")
            path("post", "/auth/refresh", "刷新Token", "Public", req = "RefreshRequest")
            path("post", "/auth/logout", "登出(Access进Redis黑名单)", "Bearer")
            path("get", "/auth/me", "当前用户信息", "Bearer")
            path("get", "/users", "用户列表(分页)", "USER_READ")
            path("post", "/users", "创建用户", "USER_CREATE", req = "CreateUserRequest")
            path("put", "/users/{id}/password-reset", "重置他人密码", "USER_RESET_PASSWORD", req = "ResetPasswordRequest")
            path("delete", "/users/{id}", "注销用户", "USER_DELETE")
            path("post", "/users/inherit/assign", "指定继承", "USER_INHERIT_ASSIGN", req = "InheritAssignRequest")
            path("post", "/users/inherit/claim", "认领项目", "USER_INHERIT_CLAIM", req = "InheritClaimRequest")
            // ---------- Projects ----------
            path("get", "/projects", "项目列表", "Bearer")
            path("post", "/projects", "创建项目", "PROJECT_CREATE", req = "CreateProjectRequest")
            path("put", "/projects/{id}/archive", "归档(二次确认name)", "PROJECT_ARCHIVE", req = "ConfirmNameRequest")
            path("delete", "/projects/{id}", "删除→回收站(二次确认name)", "PROJECT_DELETE", req = "ConfirmNameRequest")
            path("post", "/projects/{id}/transfer-owner", "转移OWNER", "is_owner")
            path("post", "/projects/{id}/members", "添加成员", "MEMBER_MANAGE")
            path("post", "/projects/{id}/guests", "创建临时访客", "MEMBER_MANAGE")
            path("post", "/projects/{id}/upload/check-conflict", "检查重名(§4.2)", "MEDIA_UPLOAD", req = "ConflictCheckRequest")
            path("post", "/projects/{id}/upload", "上传(multipart, X-Conflict-Strategy头)", "MEDIA_UPLOAD")
            path("post", "/projects/{id}/pack", "打包下载(异步ZIP→MinIO, TTL 7d)", "FILE_DOWNLOAD", req = "PackRequest")
            path("get", "/projects/{id}/pack/{packId}", "打包状态/预签名URL", "FILE_DOWNLOAD")
            path("post", "/projects/{id}/files/batch-tag", "批量打标(OVERWRITE)", "FILE_TAG", req = "BatchTagRequest")
            path("get", "/projects/{id}/images/nav", "图片切换列表(§6.3)", "FILE_VIEW")
            // ---------- Files ----------
            path("get", "/files/{id}/detail", "文件详情", "FILE_VIEW_DETAIL")
            path("get", "/files/{id}/download-url", "Presigned下载URL(Note #3)", "FILE_DOWNLOAD")
            path("put", "/files/{id}/tags", "单文件打标", "FILE_TAG", req = "SetTagsRequest")
            path("post", "/files/{id}/approve", "提交审批(REJECTED必填备注)", "FILE_APPROVE", req = "ApprovalRequest")
            path("get", "/files/{id}/comments", "评论列表", "FILE_COMMENT")
            path("post", "/files/{id}/comments", "发表评论(@username解析)", "FILE_COMMENT", req = "CommentRequest")
            path("delete", "/files/{id}", "文件删除→回收站", "PROJECT_DELETE")
            // ---------- Tags ----------
            path("get", "/tags", "标签树(扁平化)", "TAG_VIEW")
            path("post", "/tags", "创建标签(5级深度校验)", "TAG_MANAGE", req = "CreateTagRequest")
            path("put", "/tags/{id}", "编辑标签", "TAG_MANAGE", req = "UpdateTagRequest")
            path("delete", "/tags/{id}", "删除标签(级联子标签+file_tags)", "TAG_MANAGE")
            // ---------- Recycle bin ----------
            path("get", "/recycle-bin", "回收站列表(按权限过滤, expire_at>NOW())", "Bearer")
            path("post", "/recycle-bin/{id}/restore", "还原(冲突加restore_前缀)", "Bearer")
            path("delete", "/recycle-bin/{id}", "二次删除", "PROJECT_DELETE")
            path("delete", "/recycle-bin/clear-all", "强制清空", "SUPER_ADMIN")
            // ---------- Logs ----------
            path("get", "/operation-logs", "操作日志", "LOG_READ")
        }
    }

    fun json(): String = """
{
  "openapi": "3.0.3",
  "info": {
    "title": "多用户媒体资源管理系统 API",
    "version": "1.0.0",
    "description": "统一响应包装 { code, message, data }；全链路UTC；文件访问一律Presigned URL。"
  },
  "servers": [{ "url": "/" }],
  "security": [{ "bearerAuth": [] }],
  "paths": {
$pathsJson
  },
  "components": {
    "securitySchemes": {
      "bearerAuth": { "type": "http", "scheme": "bearer", "bearerFormat": "JWT" }
    },
    "schemas": {
      "ApiResponse": {
        "type": "object",
        "properties": {
          "code": { "type": "integer", "description": "0=成功；非0为业务错误码" },
          "message": { "type": "string" },
          "data": { "nullable": true }
        }
      }
    }
  }
}
""".trimIndent()

    private fun StringBuilder.path(
        method: String, route: String, summary: String, perm: String, req: String? = null,
    ) {
        val params = Regex("\\{(\\w+)}").findAll(route).joinToString(",") { m ->
            """{"name":"${m.groupValues[1]}","in":"path","required":true,"schema":{"type":"string"}}"""
        }
        val body = if (req != null) ""","requestBody":{"content":{"application/json":{"schema":{"type":"object"}}}}""" else ""
        val (parent, op) = if ("/" in route.trimStart('/')) {
            val i = route.lastIndexOf('/')
            route.substring(0, i) to route.substring(i + 1)
        } else "/" to ""
        val key = if (op.isEmpty()) parent else "$parent/{$op}"
        append("""    "$key": { "${method.toLowerCase()}": { "summary": "$summary [权限:$perm]",""" +
            """"tags":["${tagOf(route)}"],"parameters":[$params]$body,""" +
            """"responses":{"default":{"description":"ApiResponse",""" +
            """"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/ApiResponse"}}}}}} },\n""")
    }

    private fun tagOf(route: String): String = when {
        route.startsWith("/auth") -> "Auth"
        route.startsWith("/users") -> "Users"
        route.startsWith("/projects") -> "Projects"
        route.startsWith("/files") -> "Files"
        route.startsWith("/tags") -> "Tags"
        route.startsWith("/recycle-bin") -> "RecycleBin"
        route.startsWith("/operation-logs") -> "Logs"
        else -> "System"
    }
}
