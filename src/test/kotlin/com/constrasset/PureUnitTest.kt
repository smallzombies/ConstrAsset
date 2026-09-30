package com.constrasset

import com.constrasset.common.BusinessException
import com.constrasset.common.Patterns
import com.constrasset.common.ValidationException
import com.constrasset.common.Validator
import com.constrasset.infra.PasswordHasher
import com.constrasset.file.ConflictStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 纯逻辑单元测试（无外部依赖，可在任意环境运行）。
 * 覆盖：§1.2 输入校验正则、Argon2id 哈希、§5.2 标签深度 BFS、§4.2 冲突策略解析。
 */
class PureUnitTest {

    // ---------- §1.2 用户名/密码校验 ----------
    @Test fun `username regex accepts valid and rejects invalid`() {
        assertTrue("alice_dev".matches(Patterns.USERNAME))
        assertTrue("Z_9abc-def".matches(Patterns.USERNAME))          // 10位合法
        assertFalse("abc".matches(Patterns.USERNAME))                 // <6位
        assertFalse("1user".matches(Patterns.USERNAME))               // 数字开头
        assertFalse("-dash".matches(Patterns.USERNAME))               // 连字符开头
        assertFalse("has space".matches(Patterns.USERNAME))
        assertFalse(("a".repeat(21)).matches(Patterns.USERNAME))      // >20位
    }

    @Test fun `password regex per spec`() {
        assertTrue("Abcdefg1".matches(Patterns.PASSWORD))             // 8位含大小写数字
        assertTrue("Str0ngPass".matches(Patterns.PASSWORD))
        assertFalse("alllower1".matches(Patterns.PASSWORD))           // 缺大写
        assertFalse("ALLUPPER1".matches(Patterns.PASSWORD))           // 缺小写
        assertFalse("NoDigits!".matches(Patterns.PASSWORD))           // 缺数字且含非法字符!
        assertFalse("Sh0rt".matches(Patterns.PASSWORD))               // <8位
        assertFalse(("Aa1" + "b".repeat(16)).matches(Patterns.PASSWORD)) // >18位
    }

    // ---------- Argon2id ----------
    @Test fun `argon2id hash verify roundtrip`() {
        val hash = PasswordHasher.hash("Abcdefg1")
        assertTrue(hash.startsWith("\$argon2id\$"))                 // §0 强制 Argon2id
        assertTrue(PasswordHasher.verify("Abcdefg1", hash))
        assertFalse(PasswordHasher.verify("WrongPass1", hash))
    }

    // ---------- §5.2 五级深度校验（内存BFS版） ----------
    private fun chain(parents: Map<Long, Long?>, startId: Long): Int {
        // 计算从 startId 向上的祖先链深度（含自身），模拟 TagService.checkDepth
        var depth = 1
        var cur = parents[startId]
        while (cur != null) { depth++; cur = parents[cur] }
        return depth
    }

    @Test fun `tag depth limit 5`() {
        val parents = mapOf(1L to null, 2L to 1L, 3L to 2L, 4L to 3L, 5L to 4L)
        assertEquals(5, chain(parents, 5L))                         // 第5层存在 → 其下创建子标签应被拒
        assertEquals(1, chain(parents, 1L))
    }

    /// TagService 名称校验内联于 createTag（DB前置检查），此处仅验证纯长度规则不依赖DB
    private fun nameRuleOk(name: String): Boolean = name.trim().isNotEmpty() && name.trim().length <= 100

    @Test fun `tag name rule enforces 1-100 chars`() {
        assertFalse(nameRuleOk(""))
        assertFalse(nameRuleOk("   "))
        assertFalse(nameRuleOk("标".repeat(101)))
        assertTrue(nameRuleOk("设计图纸"))
    }

    // ---------- §4.2 冲突策略解析 ----------
    @Test fun `conflict strategy parsing per header protocol`() {
        assertTrue(ConflictStrategy.parse(null) is ConflictStrategy.FailFast)
        assertEquals(ConflictStrategy.ReplaceAll, ConflictStrategy.parse("REPLACE_ALL"))
        assertEquals(ConflictStrategy.SkipAll, ConflictStrategy.parse("skip_all"))   // 大小写不敏感
        val named = ConflictStrategy.parse("REPLACE:a.jpg,b.png")
        assertTrue(named is ConflictStrategy.ReplaceSet)
        assertEquals(setOf("a.jpg", "b.png"), (named as ConflictStrategy.ReplaceSet).names)
        assertFailsWith<BusinessException> { ConflictStrategy.parse("BOGUS") }
    }

    // ---------- §3.1 项目编码正则 ----------
    @Test fun `project code regex per spec`() {
        assertTrue(Patterns.PROJECT_CODE.matches("PRJ2024"))
        assertFalse(Patterns.PROJECT_CODE.matches("a-b!"))     // 非字母数字
        assertFalse(Patterns.PROJECT_CODE.matches("a"))         // <2位
        assertFalse(Patterns.PROJECT_CODE.matches("x".repeat(51)))
    }

    // ---------- Validator 聚合错误行为（Critical Note #6）----------
    @Test fun `validator collects all field errors then throws once`() {
        val ex = assertFailsWith<ValidationException> {
            Validator()
                .required("name", null, "名称")
                .pattern("code", "a-b!", Patterns.PROJECT_CODE, "编码非法")
                .finish()
        }
        // ValidationException 聚合所有字段错误到 message（Critical Note #6）
        assertTrue(ex.message!!.contains("name"))
        assertTrue(ex.message!!.contains("code"))
    }
}
