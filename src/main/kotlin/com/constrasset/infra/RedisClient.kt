package com.constrasset.infra

import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.time.Duration

/**
 * Redis 7+ 封装（Global Constraint：JWT黑名单、RefreshToken Hash、pack TTL、排序状态缓存）。
 * Key 规划：
 *  - jwt:blacklist:{jti}          -> "1", TTL = Access Token 剩余有效期（§1.2 登出）
 *  - refresh:{userId}             -> Hash { tokenId -> token }, TTL 7d（§1.2 Refresh存Redis Hash）
 *  - pack:{projectId}:{packId}    -> minioKey, TTL 7天（§4.4）
 *  - sort:{projectId}:{userId}    -> 排序状态缓存 JSON
 *  - shortlink:{code}             -> url（短链映射）
 */
class RedisClient(host: String, port: Int) : AutoCloseable {
    private val pool = JedisPool(JedisPoolConfig(), host, port, 3000)

    fun setWithTtl(key: String, value: String, ttlSeconds: Long) {
        pool.resource.use { it.setex(key, ttlSeconds, value) }
    }

    fun get(key: String): String? = pool.resource.use { it.get(key) }

    fun exists(key: String): Boolean = pool.resource.use { it.exists(key) }

    fun del(vararg keys: String) {
        if (keys.isNotEmpty()) pool.resource.use { it.del(*keys) }
    }

    /** Refresh Token Hash 操作（§1.2） */
    fun hSet(hashKey: String, field: String, value: String, ttl: Duration) {
        pool.resource.use { j ->
            j.hset(hashKey, field, value)
            j.expire(hashKey, ttl.seconds)
        }
    }

    fun hGetAll(hashKey: String): Map<String, String> =
        pool.resource.use { it.hgetAll(hashKey) }

    fun hDel(hashKey: String, vararg fields: String) {
        pool.resource.use { it.hdel(hashKey, *fields) }
    }

    fun expire(key: String, ttlSeconds: Long) {
        pool.resource.use { it.expire(key, ttlSeconds) }
    }

    /** 分布式锁（定时任务防重入：打包清理 / 回收站清理 §7.3） */
    fun tryLock(name: String, ttlSeconds: Long): Boolean =
        pool.resource.use { it.set("lock:$name", "1", redis.clients.jedis.params.SetParams().nx().ex(ttlSeconds)) != null }

    override fun close() = pool.close()
}

object RedisKeys {
    fun jwtBlacklist(jti: String) = "jwt:blacklist:$jti"
    fun refreshToken(userId: Long) = "refresh:$userId"
    fun pack(projectId: Long, packId: String) = "pack:$projectId:$packId"
    fun sortOrder(projectId: Long, userId: Long) = "sort:$projectId:$userId"
    fun shortLink(code: String) = "shortlink:$code"
}
