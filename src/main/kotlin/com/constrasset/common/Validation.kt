package com.constrasset.common

/**
 * 输入校验器（Critical Note #6：所有 Request Body 必须经过 custom validator，禁止信任前端数据）。
 * 收集全部字段错误后一次性抛出 ValidationException。
 */
class Validator {
    private val errors = mutableMapOf<String, String>()

    fun required(field: String, value: String?, label: String = field): Validator {
        if (value.isNullOrBlank()) errors[field] = "${label}不能为空"
        return this
    }

    fun pattern(field: String, value: String?, regex: Regex, msg: String): Validator {
        if (value != null && !regex.matches(value)) errors[field] = msg
        return this
    }

    fun length(field: String, value: String?, min: Int, max: Int, msg: String? = null): Validator {
        if (value != null && (value.length < min || value.length > max))
            errors[field] = msg ?: "${field}长度必须在[$min,$max]"
        return this
    }

    fun enumOf(field: String, raw: String?, choices: Set<String>): Validator {
        if (raw != null && raw !in choices) errors[field] = "${field}取值非法，允许: $choices"
        return this
    }

    fun nonBlankList(field: String, list: List<*>?): Validator {
        if (list.isNullOrEmpty()) errors[field] = "${field}不能为空列表"
        return this
    }

    fun check(cond: Boolean, field: String, msg: String): Validator {
        if (!cond) errors[field] = msg
        return this
    }

    fun finish() {
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }
}

// ---------- 规格书中的正则常量（§1.1 / §1.2 / §3.1） ----------
object Patterns {
    /** 用户名: 6-20位 ^[a-zA-Z_][a-zA-Z0-9_-]{5,19}$ */
    val USERNAME = Regex("^[a-zA-Z_][a-zA-Z0-9_-]{5,19}$")

    /** 密码强度: ^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[a-zA-Z\d]{8,18}$ */
    val PASSWORD = Regex("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d]{8,18}$")

    /** 项目编号: ^[a-zA-Z0-9]{2,50}$ */
    val PROJECT_CODE = Regex("^[a-zA-Z0-9]{2,50}$")

    /** 临时访客用户名前缀（§1.2） */
    const val GUEST_PREFIX = "guest_"
}
