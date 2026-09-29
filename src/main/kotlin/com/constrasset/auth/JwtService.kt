package com.constrasset.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.constrasset.common.BusinessException
import com.constrasset.infra.RedisClient
import com.constrasset.infra.RedisKeys
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * §1.2 JWT 规则：
 *  - Access Token 15min + Refresh Token 7d
 *  - Refresh 存 Redis Hash（key = refresh:{userId}，field = tokenId）
 *  - 登出时 Access 的 jti 加入 Redis 黑名单，TTL = Access 剩余有效期
 *  - 临时访客 Token 含 projectId + projectRoleId（Critical Note #7：Token中projectId必须与请求路径一致）
 */
object JwtService {
    private const val ISSUER = "constr-asset"

    private lateinit var algorithm: Algorithm
    private lateinit var redis: RedisClient
    var accessTtlSeconds: Long = 15 * 60L
        private set
    var refreshTtlSeconds: Long = 7 * 24 * 3600L
        private set

    fun init(secret: String, accessTtl: Long, refreshTtl: Long, redisClient: RedisClient) {
        algorithm = Algorithm.HMAC256(secret)
        accessTtlSeconds = accessTtl
        refreshTtlSeconds = refreshTtl
        redis = redisClient
    }

    @Serializable
    data class Claims(
        val userId: Long,
        val username: String,
        val orgRoleName: String,
        /** 临时访客专用：所属项目（普通用户为 null） */
        val projectId: Long? = null,
        /** 临时访客专用：项目角色ID */
        val projectRoleId: Long? = null,
        val isGuest: Boolean = false,
        val jti: String = UUID.randomUUID().toString(),
        val typ: TokenType = TokenType.ACCESS,
        val expEpochSec: Long = 0,
    )

    enum class TokenType { ACCESS, REFRESH }

    fun issueAccess(c: Claims): String {
        val now = Instant.now()
        val exp = now.plusSeconds(accessTtlSeconds)
        return JWT.create()
            .withIssuer(ISSUER)
            .withJWTId(c.jti)
            .withSubject(c.userId.toString())
            .withClaim("username", c.username)
            .withClaim("orgRole", c.orgRoleName)
            .apply { if (c.projectId != null) withClaim("projectId", c.projectId) }
            .apply { if (c.projectRoleId != null) withClaim("projectRoleId", c.projectRoleId) }
            .withClaim("guest", c.isGuest)
            .withClaim("typ", TokenType.ACCESS.name)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(exp))
            .sign(algorithm)
    }

    /** Refresh Token：签发后写入 Redis Hash；校验时必须同时命中 Redis（可吊销）。 */
    fun issueRefresh(userId: Long): Pair<String, String> {
        val tokenId = UUID.randomUUID().toString()
        val now = Instant.now()
        val token = JWT.create()
            .withIssuer(ISSUER)
            .withJWTId(tokenId)
            .withSubject(userId.toString())
            .withClaim("typ", TokenType.REFRESH.name)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(refreshTtlSeconds)))
            .sign(algorithm)
        redis.hSet(RedisKeys.refreshToken(userId), tokenId, token, java.time.Duration.ofSeconds(refreshTtlSeconds))
        return token to tokenId
    }

    fun decode(token: String): Claims {
        val dec = try {
            JWT.require(algorithm).withIssuer(ISSUER).build().verify(token)
        } catch (e: Exception) {
            throw BusinessException("Token无效或已过期", httpStatus = 401, bizCode = 1001)
        }
        // 登出黑名单检查（§1.2）
        if (redis.exists(RedisKeys.jwtBlacklist(dec.id))) {
            throw BusinessException("Token已失效（已登出）", httpStatus = 401, bizCode = 1002)
        }
        val typ = dec.getClaim("typ").asString()
        if (typ != TokenType.ACCESS.name) {
            throw BusinessException("需要Access Token", httpStatus = 401, bizCode = 1003)
        }
        val pid = dec.getClaim("projectId")
        val prid = dec.getClaim("projectRoleId")
        return Claims(
            userId = dec.subject.toLong(),
            username = dec.getClaim("username").asString(),
            orgRoleName = dec.getClaim("orgRole").asString(),
            projectId = if (pid.isNull) null else pid.asLong(),
            projectRoleId = if (prid.isNull) null else prid.asLong(),
            isGuest = dec.getClaim("guest").asBoolean() ?: false,
            jti = dec.id,
            typ = TokenType.ACCESS,
            expEpochSec = dec.expiresAt.toInstant().epochSecond,
        )
    }

    fun verifyRefreshAndGetClaims(refreshToken: String): Claims {
        val dec = try {
            JWT.require(algorithm).withIssuer(ISSUER).build().verify(refreshToken)
        } catch (e: Exception) {
            throw BusinessException("Refresh Token无效", httpStatus = 401, bizCode = 1004)
        }
        if (dec.getClaim("typ").asString() != TokenType.REFRESH.name)
            throw BusinessException("需要Refresh Token", httpStatus = 401, bizCode = 1005)
        val userId = dec.subject.toLong()
        val stored = redis.hGetAll(RedisKeys.refreshToken(userId))
        if (stored[dec.id] != refreshToken) {
            throw BusinessException("Refresh Token已被吊销", httpStatus = 401, bizCode = 1006)
        }
        return Claims(userId = userId, username = "", orgRoleName = "", jti = dec.id, typ = TokenType.REFRESH)
    }

    /** 登出：Access jti 进黑名单，TTL=剩余有效期（§1.2） */
    fun logout(jti: String, remainingSeconds: Long, userId: Long) {
        if (remainingSeconds > 0) {
            redis.setWithTtl(RedisKeys.jwtBlacklist(jti), "1", remainingSeconds)
        }
        redis.del(RedisKeys.refreshToken(userId))
    }

    fun revokeAllRefreshTokens(userId: Long) = redis.del(RedisKeys.refreshToken(userId))
}
