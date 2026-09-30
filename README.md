# ConstrAsset

Construction (工程建设) + Asset (资产/素材)，多用户媒体资源管理系统。

Kotlin 2.x + Ktor 3.0.3 (Netty) + PostgreSQL 16 + Redis 7 + MinIO，Exposed DSL（无 Hibernate），kotlinx.serialization（无 Jackson/Gson），Argon2id 密码哈希，全链路 UTC。

## 模块结构（对应规格书章节）

| 包 | 章节 | 内容 |
| :--- | :--- | :--- |
| `auth` | §1 | 登录/刷新/登出(JWT黑名单)、注销、继承-指定/认领、临时访客(guest_) |
| `rbac` | §2 | 组织角色/项目角色 TEXT[] 权限、§2.3 校验顺序(SUPER_ADMIN兜底→项目级→组织级) |
| `project` | §3 | 创建/归档(二次确认)/删除(入回收站)/转移OWNER/冻结 |
| `file` | §4 | 上传重名协议(X-Conflict-Strategy)、缩略图异步Pipeline、打包下载(Redis TTL 7d) |
| `tag` | §5 | 标签树、5级深度校验(内存BFS)、批量OVERWRITE打标 |
| `approval` | §6 | 审批(REJECTED必填备注)、图片切换列表、评论+@解析 |
| `recycle` | §7 | 还原冲突改名 restore_、每小时清理、查询侧 expire_at 过滤 |
| `audit` | §8 | OperationLogs 落库(jsonb)、递归脱敏(password/token/secret等) |
| `api` | §9 | Routing/权限中间件/OpenAPI 3.0/Swagger UI(/docs) |

## 快速启动

```bash
docker compose up -d                       # postgres + redis + minio
export JWT_SECRET="$(openssl rand -base64 48)"   # 生产必改！
gradle run                                 # 或 ./gradlew installDist 后 bin/constr-asset
```

首次启动自动建表并 Seed：无 SUPER_ADMIN 时创建 `admin / admin123`（§1.3，请立即修改）。

- API: http://localhost:8080  · Swagger UI: http://localhost:8080/docs · OpenAPI: /openapi.json
- MinIO Console: http://localhost:9001 (minioadmin/minioadmin)

### 环境变量（覆盖 application.conf）

`PORT, DB_URL, DB_USER, DB_PASSWORD, DEBUG_SQL, REDIS_HOST, REDIS_PORT, MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY, MINIO_BUCKET, JWT_SECRET, JWT_ACCESS_TTL(默认900s), JWT_REFRESH_TTL(默认604800s)`

## 响应格式

统一包装 `{ "code": Int, "message": String, "data": T? }`；业务错误由 StatusPages 映射 HTTP 状态码 + bizCode。

## 测试

```bash
gradle test            # 纯逻辑单元测试(无需外部依赖)
gradle test --tests "*IT" # 集成测试需 Docker(Testcontainers: PG/MinIO + embedded-redis)
```

当前：PureUnitTest 8 例全部通过（校验正则/Argon2id/冲突策略/Validator聚合）。
计划中（TODO）：AuthFlowIT、RbacMiddlewareIT、UserInheritanceIT、FileWorkspaceIT、TagApprovalIT、RecycleBinIT —— 需在带 Docker 的 CI 环境编写运行。

## 已知限制

- 缩略图视频抽帧依赖 FFmpeg（镜像内需安装 ffmpeg）。
- EXIF 读取使用 metadata-extractor；orientation 旋转走 java.awt。
- 沙箱开发环境无 Docker，Testcontainers 集成测试未在本机执行过。
