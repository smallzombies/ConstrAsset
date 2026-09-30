package com.constrasset.api

import com.constrasset.auth.JwtService
import com.constrasset.common.BusinessException
import io.ktor.server.application.ApplicationCall
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 认证中间件解析 Access Token 后挂载到 call 上的主体信息（§2.3 / Note #7）。
 */
data class Principal(
    val claims: JwtService.Claims,
) {
    val userId: Long get() = claims.userId
    val isGuest: Boolean get() = claims.isGuest
    val isSuperAdmin: Boolean get() = claims.orgRoleName == "SUPER_ADMIN"

    /** 组织级权限：实时查库（角色权限可被 ROLE_EDIT_PERMISSION 修改，不能只信Token快照） */
    fun hasOrgPerm(perm: String): Boolean {
        if (isSuperAdmin) return true
        if (isGuest) return false // §1.2：临时访客无平台菜单权限
        return transaction {
            (com.constrasset.auth.Users innerJoin com.constrasset.rbac.OrgRoles)
                .selectAll()
                .where {
                    SqlExpressionBuilder.run {
                        (com.constrasset.auth.Users.id eq userId) and
                            (com.constrasset.rbac.OrgRoles.permissions containsArrayOf perm)
                    }
                }.count() > 0
        }
    }

}

fun ApplicationCall.requirePrincipal(): Principal =
    attributes.getOrNull(PrincipalAttr)
        ?: throw BusinessException("未认证", httpStatus = 401, bizCode = 2005)

/** Exposed 对 PostgreSQL TEXT[] 的 `@>` 包含判断（单元素，配合 PgTextArrayColumnType）。 */
infix fun Column<List<String>>.containsArrayOf(element: String): Op<Boolean> =
    ArrayContainsOp(this, element)

private class ArrayContainsOp(
    private val column: Column<List<String>>,
    private val element: String,
) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        // 参数以数组字面量文本形式绑定，PG 侧显式 cast 为 text[]
        append(column.table, ".", column)
        append(" @> ?::text[]")
        registerArgument(column, listOf(element))
    }
}
