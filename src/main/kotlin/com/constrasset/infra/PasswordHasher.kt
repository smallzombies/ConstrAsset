package com.constrasset.infra

import de.mkammerer.argon2.Argon2Factory


/**
 * 密码哈希：Argon2id ONLY（Global Constraint，禁止 BCrypt/MD5）。
 * 参数遵循 OWASP 推荐：memory=65536KB(64MB), iterations=3, parallelism=4。
 * argon2-jvm 生成的哈希串自带 "$argon2id$" 前缀，可在 verify 时校验算法类型。
 */
object PasswordHasher {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    private const val ITERATIONS = 3
    private const val MEMORY_KB = 65536
    private const val PARALLELISM = 4

    fun hash(rawPassword: String): String =
        argon2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, rawPassword.toCharArray())

    fun verify(rawPassword: String, hash: String): Boolean {
        // 防御性校验：数据库中必须是 argon2id 哈希
        if (!hash.startsWith("\$argon2id\$")) return false
        return try {
            argon2.verify(hash, rawPassword.toCharArray())
        } catch (_: Exception) {
            false
        }
    }
}
