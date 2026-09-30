package com.constrasset.api

import com.constrasset.approval.ApprovalService
import com.constrasset.auth.AuthService
import com.constrasset.auth.JwtService
import com.constrasset.common.ApiResponse
import com.constrasset.common.BusinessException
import com.constrasset.common.ValidationException
import com.constrasset.common.ok
import com.constrasset.file.FileService
import com.constrasset.project.ProjectService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.withCharset
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.MultipartData
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/** Swagger UI（ktor-server-swagger 3.0.3：io.ktor.server.plugins.swagger.swaggerUI） */
private fun io.ktor.server.routing.Route.swaggerDocs(path: String, url: String) {
    io.ktor.server.plugins.swagger.swaggerUI(path = path, url = url)
}

/** JSON 序列化全局配置（Global Constraint：kotlinx.serialization，禁止Jackson/Gson） */
val AppJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val jsonContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)

/**
 * §9 API端点清单的 Ktor Routing 装配 + §2.3 权限校验中间件 + OpenAPI/Swagger。
 * 统一响应包装 { code, message, data }；异常由 StatusPages 兜底转换。
 */
fun Application.apiModule(handlers: RouteHandlers) {

    install(ContentNegotiation) {
        json(AppJson)
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Conflict-Strategy") // §4.2 上传冲突策略头
        exposeHeader(HttpHeaders.ContentLength)
    }

    // ---------- 认证/权限中间件（§2.3 / Note #5 #7） ----------
    val publicPaths = setOf("/auth/login", "/auth/refresh")
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path().trimEnd('/')
        if (path in publicPaths || path == "/docs" || path == "/openapi.json") return@intercept
        val authHeader = call.request.header(HttpHeaders.Authorization)
            ?: throw BusinessException("缺少Authorization头", httpStatus = 401, bizCode = 1007)
        if (!authHeader.startsWith("Bearer ")) {
            throw BusinessException("Authorization格式错误", httpStatus = 401, bizCode = 1007)
        }
        val claims = withContext(Dispatchers.IO) { JwtService.decode(authHeader.removePrefix("Bearer ").trim()) }
        call.attributes.put(PrincipalAttr, Principal(claims))
    }

    // ---------- 异常 → 统一响应包装 ----------
    install(StatusPages) {
        exception<BusinessException> { call, cause ->
            call.respond(HttpStatusCode.fromValue(cause.httpStatus),
                ApiResponse<Unit?>(cause.bizCode, cause.message, null))
        }
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity,
                ApiResponse<Unit?>(422, cause.message ?: "参数校验失败", null))
        }
        exception<kotlinx.serialization.SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Unit?>(400, "请求体缺失或JSON格式错误", null))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiResponse<Unit?>(500, "服务器内部错误", null))
        }
    }

    routing {

        get("/health") { call.respond(ok<Unit?>(null)) }

        // ================= /auth =================
        route("/auth") {
            post("/login") {
                val req = call.receive<AuthService.LoginRequest>()
                call.respond(call.handlerOk { handlers.login(req, call.clientIp()) })
            }
            post("/refresh") {
                val req = call.receive<RouteHandlers.RefreshRequest>()
                call.respond(call.handlerOk { handlers.refresh(req) })
            }
            post("/logout") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.logout(p, call.clientIp()) })
            }
            get("/me") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.me(p) })
            }
        }

        // ================= /users =================
        route("/users") {
            get {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk {
                    handlers.listUsers(p, call.queryInt("page", 1), call.queryInt("pageSize", 20),
                        call.request.queryParameters["keyword"], call.request.queryParameters["status"])
                })
            }
            post {
                val p = call.requirePrincipal()
                val req = call.receive<AuthService.CreateUserRequest>()
                call.respond(call.handlerOk { handlers.createUser(p, req, call.clientIp()) })
            }
            put("/{id}/password-reset") {
                val p = call.requirePrincipal()
                val id = call.pathLong("id")
                val req = call.receive<RouteHandlers.ResetPasswordRequest>()
                call.respond(call.handlerOk { handlers.resetPassword(p, id, req, call.clientIp()) })
            }
            delete("/{id}") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.deactivateUser(p, call.pathLong("id"), call.clientIp()) })
            }
            post("/inherit/assign") {
                val p = call.requirePrincipal()
                val req = call.receive<AuthService.InheritAssignRequest>()
                call.respond(call.handlerOk { handlers.inheritAssign(p, req, call.clientIp()) })
            }
            post("/inherit/claim") {
                val p = call.requirePrincipal()
                val req = call.receive<AuthService.InheritClaimRequest>()
                call.respond(call.handlerOk { handlers.inheritClaim(p, req, call.clientIp()) })
            }
        }

        // ================= /projects =================
        route("/projects") {
            get {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk {
                    handlers.listProjects(p, call.queryInt("page", 1), call.queryInt("pageSize", 20))
                })
            }
            post {
                val p = call.requirePrincipal()
                val req = call.receive<ProjectService.CreateProjectRequest>()
                call.respond(call.handlerOk { handlers.createProject(p, req, call.clientIp()) })
            }
            route("/{id}") {
                put("/archive") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.ConfirmNameRequest>()
                    call.respond(call.handlerOk { handlers.archiveProject(p, call.pathLong("id"), req, call.clientIp()) })
                }
                delete {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.ConfirmNameRequest>()
                    call.respond(call.handlerOk { handlers.deleteProject(p, call.pathLong("id"), req, call.clientIp()) })
                }
                post("/transfer-owner") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.TransferOwnerBody>()
                    call.respond(call.handlerOk { handlers.transferOwner(p, call.pathLong("id"), req, call.clientIp()) })
                }
                post("/members") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.AddMemberBody>()
                    call.respond(call.handlerOk { handlers.addMember(p, call.pathLong("id"), req, call.clientIp()) })
                }
                post("/guests") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.CreateGuestBody>()
                    call.respond(call.handlerOk { handlers.createGuest(p, call.pathLong("id"), req, call.clientIp()) })
                }
                post("/upload/check-conflict") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.ConflictCheckRequest>()
                    call.respond(call.handlerOk { handlers.checkConflict(p, call.pathLong("id"), req) })
                }
                // §4.2 multipart 上传 + X-Conflict-Strategy 策略头
                post("/upload") {
                    val p = call.requirePrincipal()
                    val projectId = call.pathLong("id")
                    val folderId = call.request.queryParameters["folderId"]?.toLongOrNull()
                    val strategy = call.request.header("X-Conflict-Strategy")
                    val items = withContext(Dispatchers.IO) {
                        val mp = call.receiveMultipart()
                        val list = ArrayList<FileService.UploadItem>()
                        mp.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                val bytes = part.provider().use { ins ->
                                    ByteArrayOutputStream().also { out -> ins.copyTo(out) }.toByteArray()
                                }
                                require(bytes.size <= MAX_UPLOAD_BYTES) { "单文件超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB 上限" }
                                list += FileService.UploadItem(
                                    fileName = part.originalFileName ?: (part.disposition?.name() ?: "unnamed"),
                                    content = bytes,
                                    mimeType = part.contentType?.toString() ?: "application/octet-stream",
                                )
                            }
                            part.dispose()
                        }
                        if (list.isEmpty()) throw BusinessException("未检测到文件部分", httpStatus = 400, bizCode = 1101)
                        list
                    }
                    call.respond(call.handlerOk { handlers.upload(p, projectId, folderId, items, strategy, call.clientIp()) })
                }
                post("/pack") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.PackRequest>()
                    call.respond(call.handlerOk { handlers.pack(p, call.pathLong("id"), req, call.clientIp()) })
                }
                get("/pack/{packId}") {
                    val p = call.requirePrincipal()
                    call.respond(call.handlerOk { handlers.packStatus(p, call.pathLong("id"), call.parameters["packId"] ?: "") })
                }
                post("/files/batch-tag") {
                    val p = call.requirePrincipal()
                    val req = call.receive<RouteHandlers.BatchTagRequest>()
                    call.respond(call.handlerOk { handlers.batchTag(p, call.pathLong("id"), req, call.clientIp()) })
                }
                get("/images/nav") {
                    val p = call.requirePrincipal()
                    val folderId = call.request.queryParameters["folderId"]?.toLongOrNull()
                    val current = call.request.queryParameters["currentFileId"]?.toLongOrNull()
                    call.respond(call.handlerOk { handlers.imageNav(p, call.pathLong("id"), folderId, current) })
                }
            }
        }

        // ================= /files =================
        route("/files/{id}") {
            val fileId = { call.pathLong("id") }
            get("/detail") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.fileDetail(p, fileId()) })
            }
            get("/download-url") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.downloadUrl(p, fileId()) })
            }
            put("/tags") {
                val p = call.requirePrincipal()
                val req = call.receive<RouteHandlers.SetTagsRequest>()
                call.respond(call.handlerOk { handlers.setFileTags(p, fileId(), req, call.clientIp()) })
            }
            post("/approve") {
                val p = call.requirePrincipal()
                val req = call.receive<ApprovalService.ApprovalRequest>()
                call.respond(call.handlerOk { handlers.approve(p, fileId(), req, call.clientIp()) })
            }
            get("/comments") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.listComments(p, fileId()) })
            }
            post("/comments") {
                val p = call.requirePrincipal()
                val req = call.receive<ApprovalService.CommentRequest>()
                call.respond(call.handlerOk { handlers.addComment(p, fileId(), req, call.clientIp()) })
            }
            delete {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.deleteFile(p, fileId(), call.clientIp()) })
            }
        }

        // ================= /tags =================
        route("/tags") {
            get {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.tagTree(p) })
            }
            post {
                val p = call.requirePrincipal()
                val req = call.receive<RouteHandlers.CreateTagRequest>()
                call.respond(call.handlerOk { handlers.createTag(p, req, call.clientIp()) })
            }
            put("/{id}") {
                val p = call.requirePrincipal()
                val req = call.receive<RouteHandlers.UpdateTagRequest>()
                call.respond(call.handlerOk { handlers.updateTag(p, call.pathLong("id"), req, call.clientIp()) })
            }
            delete("/{id}") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.deleteTag(p, call.pathLong("id"), call.clientIp()) })
            }
        }

        // ================= /recycle-bin =================
        route("/recycle-bin") {
            get {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk {
                    handlers.recycleList(p, call.queryInt("page", 1), call.queryInt("pageSize", 20))
                })
            }
            post("/{id}/restore") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.restore(p, call.pathLong("id"), call.clientIp()) })
            }
            delete("/clear-all") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.clearAll(p, call.clientIp()) })
            }
            delete("/{id}") {
                val p = call.requirePrincipal()
                call.respond(call.handlerOk { handlers.purge(p, call.pathLong("id"), call.clientIp()) })
            }
        }

        // ================= /operation-logs =================
        get("/operation-logs") {
            val p = call.requirePrincipal()
            call.respond(call.handlerOk {
                handlers.logs(p, call.queryInt("page", 1), call.queryInt("pageSize", 20),
                    call.request.queryParameters["action"])
            })
        }

        // ================= OpenAPI 3.0 + Swagger UI =================
        get("/openapi.json") {
            call.respondText(OpenApiSpec.json(), jsonContentType)
        }
        swaggerDocs(path = "/docs", url = "/openapi.json")
    }
}

// ---------- 小工具 ----------

/** 认证主体挂载 key（与 Principal.requirePrincipal 对应） */
val PrincipalAttr: AttributeKey<Principal> = AttributeKey("auth.principal")

private const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024 // 500MB

fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        ?: request.local.remoteHost

inline fun <T> ApplicationCall.handlerOk(block: () -> ApiResponse<T>): ApiResponse<T> = block()

fun ApplicationCall.pathLong(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw BusinessException("路径参数 $name 非法", httpStatus = 400, bizCode = 1102)

fun ApplicationCall.queryInt(name: String, default: Int): Int =
    request.queryParameters[name]?.toIntOrNull() ?: default
