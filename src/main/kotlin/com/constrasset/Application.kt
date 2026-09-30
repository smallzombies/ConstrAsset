package com.constrasset

import com.constrasset.api.RouteHandlers
import com.constrasset.api.apiModule
import com.constrasset.approval.ApprovalService
import com.constrasset.audit.AuditRecorderImpl
import com.constrasset.auth.AuditRecorder
import com.constrasset.auth.AuthService
import com.constrasset.auth.JwtService
import com.constrasset.auth.UserRepository
import com.constrasset.file.FileRepository
import com.constrasset.file.FileService
import com.constrasset.infra.AppConfig
import com.constrasset.infra.DatabaseFactory
import com.constrasset.infra.MinioStorage
import com.constrasset.infra.RedisClient
import com.constrasset.project.ProjectRepository
import com.constrasset.project.ProjectService
import com.constrasset.recycle.RecycleBinRepository
import com.constrasset.recycle.RecycleBinService
import com.constrasset.tag.TagService
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File as JFile
import com.typesafe.config.ConfigFactory

/**
 * 应用装配入口：Koin 风格的显式依赖注入（全部单例挂在此，禁止散落全局 object）。
 */
class AppContainer(val config: AppConfig) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val redis = RedisClient(config.redisHost, config.redisPort)
    val minio = MinioStorage(config.minioEndpoint, config.minioAccessKey, config.minioSecretKey, config.minioBucket)

    val audit: AuditRecorder = AuditRecorderImpl()
    val users = UserRepository()
    val projectsRepo = ProjectRepository()
    val recycleRepo = RecycleBinRepository()
    val filesRepo = FileRepository(minio)

    val auth = AuthService(users, projectsRepo, audit)
    val projectService = ProjectService(projectsRepo, recycleRepo, audit)
    val fileService = FileService(filesRepo, projectsRepo, minio, redis, recycleRepo, audit, scope)
    val tagService = TagService(audit)
    val approvalService = ApprovalService(filesRepo, audit)
    val recycleService = RecycleBinService(recycleRepo, projectsRepo, filesRepo, minio, audit)

    val handlers = RouteHandlers(auth, users, projectService, fileService, tagService, approvalService, recycleService)

    init {
        DatabaseFactory.init(config)
        minio.ensureBucket()
        JwtService.init(config.jwtSecret, config.accessTokenTtlSeconds, config.refreshTokenTtlSeconds, redis)
        startSchedulers()
    }

    /** §7.3 回收站每小时清理 + §4.4 pack TTL 过期对象删除（Redis锁防多实例重复执行） */
    private fun startSchedulers() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60 * 60 * 1000L)
                runCatching {
                    recycleService.scheduledCleanup { redis.tryLock("sched:recycle-cleanup", 55 * 60) }
                }
            }
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { redis.close() }
        runCatching { minio.close() }
    }
}

fun Application.module(container: AppContainer) {
    apiModule(container.handlers)
}

fun main() {
    // 配置优先级：环境变量 > application.conf > 默认值
    val conf = ConfigFactory.parseFile(JFile("application.conf"))
        .withFallback(ConfigFactory.load())
    val cfg = AppConfig(
        port = envInt("PORT", conf.getInt("app.port")),
        jdbcUrl = env("DB_URL", conf.getString("app.db.url")),
        dbUser = env("DB_USER", conf.getString("app.db.user")),
        dbPassword = env("DB_PASSWORD", conf.getString("app.db.password")),
        debugSql = envBool("DEBUG_SQL", conf.getBoolean("app.db.debugSql")),
        redisHost = env("REDIS_HOST", conf.getString("app.redis.host")),
        redisPort = envInt("REDIS_PORT", conf.getInt("app.redis.port")),
        minioEndpoint = env("MINIO_ENDPOINT", conf.getString("app.minio.endpoint")),
        minioAccessKey = env("MINIO_ACCESS_KEY", conf.getString("app.minio.accessKey")),
        minioSecretKey = env("MINIO_SECRET_KEY", conf.getString("app.minio.secretKey")),
        minioBucket = env("MINIO_BUCKET", conf.getString("app.minio.bucket")),
        jwtSecret = env("JWT_SECRET", conf.getString("app.jwt.secret")),
        accessTokenTtlSeconds = envLong("JWT_ACCESS_TTL", 15 * 60L),
        refreshTokenTtlSeconds = envLong("JWT_REFRESH_TTL", 7 * 24 * 3600L),
    )
    val container = AppContainer(cfg)
    try {
        embeddedServer(Netty, port = cfg.port) { module(container) }.start(wait = true)
    } finally {
        container.close()
    }
}

private fun env(key: String, default: String): String = System.getenv(key) ?: default
private fun envInt(key: String, default: Int): Int = System.getenv(key)?.toIntOrNull() ?: default
private fun envLong(key: String, default: Long): Long = System.getenv(key)?.toLongOrNull() ?: default
private fun envBool(key: String, default: Boolean): Boolean =
    System.getenv(key)?.toBooleanStrictOrNull() ?: default
