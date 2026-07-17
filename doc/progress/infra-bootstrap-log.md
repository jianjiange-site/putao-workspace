# Infra Bootstrap 实现进度日志

> 跨服务的"基础设施搭建"会话记录。每个微服务 log.md 只关注业务本身，
> 跨服务的工程基建（脚手架 / 协议 / 配置 / 凭据）追加在这里。

---

## 总进度概览

| 模块 | 状态 | 完成日期 | 备注 |
|------|------|----------|------|
| monorepo + .gitignore | ✅ 完成 | 2026-07-14 | |
| 文档骨架（specs/plans/progress） | ✅ 完成 | 2026-07-14 | |
| proto/ 契约（7 服务） | ✅ 完成 | 2026-07-14 | |
| 8 个微服务脚手架 | ✅ 完成 | 2026-07-14 | example + 7 个业务服务 |
| Nacos 凭据管理（.env.local） | ✅ 完成 | 2026-07-15 | |
| 各服务 Nacos yaml 配置 | ✅ 完成 | 2026-07-16 | 6 个服务 yaml 已发布 |
| 数据库初始化 | ✅ 完成 | 2026-07-16 | putao_dating_dev + 5 个服务 Flyway 迁移 |
| 中间件连通性验证 | ✅ 完成 | 2026-07-16 | PostgreSQL / Redis / MinIO |

---

## Session 日志

<!-- 在下方追加每次 session 的记录 -->

## 2026-07-15 (Session #2) — Nacos 凭据规范

**目标**: 解决"本地启动 Spring Boot 服务连不上 Nacos"问题，把凭据管理规范化。

**完成**:
- [x] `.cursorrules` 一处文档笔误：`master` → `main`
- [x] 在仓库根新建 `.env.local.example`（**tracked**，含 NACOS_* 键名）
- [x] 在仓库根新建 `.env.local`（**gitignored**，含真实凭据）
- [x] 验证 `.gitignore` 行 44 `.env.*` + 行 45 `!.env.*.example` 行为正确
- [x] 推送 6a78b84 至 origin/dev

**遗留**:
- [ ] IDEA ".env files support" 插件需在每个 dev 机器手动安装
- [ ] 没把"使用 EnvFile 插件"写进 student-dev-guide.md，新人容易踩坑

**AI 行为备注**:
- AI 反复提示 PowerShell 不支持 `&&`、git 输出 `\ No newline at end of file` 等噪音，
  后续 commit 应预先在 `git diff --cached` 中检查结尾换行
- AI 起初建议"在 application.yml 里写明文"，被用户否决后改走 .env.local 方案

---

## 2026-07-16 (Session #3) — 数据库初始化 + Nacos 配置发布

**目标**: 在远程 PostgreSQL 创建 `putao_dating_dev` 数据库，跑 Flyway 迁移，发布 Nacos 配置，验证中间件连通性。

### 1. 数据库创建

| 项目 | 值 |
|------|---|
| 数据库名 | `putao_dating_dev`（下划线，PostgreSQL 不支持连字符） |
| Host:Port | `38.76.188.242:5433` |
| 用户 | `jianjian_test` |
| Flyway History 表 | 每服务独立：`flyway_history_user` / `_post` / `_match` / `_im` / `_gateway` |

> **注意**：Nacos 配置中 `putao-dating-dev`（连字符）是 bucket 名（MinIO），JDBC URL 里必须用 `putao_dating_dev`（下划线）。

### 2. Flyway 迁移结果

| 服务 | Flyway Table | 迁移文件 | 结果 |
|------|-------------|---------|------|
| user-service | `flyway_history_user` | `V1__init_user_tables.sql` | ✅ v1 |
| post-service | `flyway_history_post` | `V20260615_01__init_post_tables.sql` | ✅ v20260615.01 |
| match-service | `flyway_history_match` | `V1__init_match_tables.sql` | ✅ v1 |
| im-service | `flyway_history_im` | `V1__init_im_tables.sql` | ✅ v1 |
| mobile-gateway | `flyway_history_gateway` | `V1__init_auth_tables.sql` | ✅ v1 |
| payment-service | — | 无迁移文件 | ⏭️ 跳过 |

**im-service 踩坑**：`WHERE expires_at > NOW()` 谓词索引失败（PostgreSQL 要求 IMMUTABLE 函数），
修复：删除了 `idx_im_token_expires` 索引（token 表数据量小，无需优化索引）。

### 3. Nacos 配置发布

6 个服务的 yaml 已通过 Nacos Open API 发布到 namespace `putao-dating-dev`：

```
im-service.yaml        ✅
match-service.yaml     ✅
mobile-gateway.yaml    ✅
payment-service.yaml   ✅
post-service.yaml      ✅
user-service.yaml      ✅
```

**所有配置统一修改**：`putao-dating-dev`（JDBC 部分）→ `putao_dating_dev`（数据库名），
MinIO bucket 保留 `putao-dating-dev`（连字符，MinIO 支持）。

### 4. 中间件验证

| 服务 | Host:Port | 状态 | 备注 |
|------|-----------|------|------|
| PostgreSQL | `38.76.188.242:5433` | ✅ | 31 张表（含 5 个 flyway_history） |
| Redis | `38.76.188.242:6380` | ✅ | PONG |
| MinIO Bucket | `putao-dating-dev` | ✅ | mc mb 成功 |

### 5. 更新的本地文件

| 文件 | 修改内容 |
|------|---------|
| `nacos/*.yaml`（6 个） | 数据库名 `putao-dating-dev` → `putao_dating_dev`（JDBC 部分） |
| `scripts/init-infra.bat` | 同上 |
| `scripts/init-infra.sh` | 同上 |
| `im-service/.../V1__init_im_tables.sql` | 删除 IMMUTABLE 谓词索引 |

---

## 2026-07-17 (Session #4) — Mobile Gateway 启动问题修复

**目标**: 修复 mobile-gateway 服务启动时的 gRPC 版本冲突问题。

**问题现象**:
```
Failed to start bean 'shadedNettyGrpcServerLifecycle'
Caused by: java.lang.NoClassDefFoundError: io/grpc/InternalGlobalInterceptors
```

**根因**: `grpc-spring-boot-starter 2.15.0.RELEASE` 需要 grpc 1.58.x，但 `grpc.version` 被升级到了 1.68.1。

**解决**:
- 降级 `proto/pom.xml` 中 `grpc.version` 从 1.68.1 → 1.58.0
- 同步降级 `dating-server/mobile-gateway/pom.xml` 中 `grpc.version`
- 重新编译 proto 模块并 install 到本地仓库
- 重新编译 mobile-gateway

**验证**:
```bash
cd proto; mvn clean install -DskipTests
cd ../dating-server/mobile-gateway; mvn clean compile -DskipTests
```

**详细记录**: 见 `doc/progress/mobile-gateway-bootstrap-log.md`

---

## 关键经验（下次开新服务直接抄）

### 1. 本地凭据管理：.env.local 模式

```
仓库根/
├── .env.local.example    # tracked，键名 + 占位符
└── .env.local            # gitignored，真实密码（每台机器一份）
```

`.gitignore` 行 42-46 已有规则，**不要再手写 gitignore 规则**。

### 2. Spring Boot 不会自动读 .env 文件，必须配 IDE 插件

| IDE | 方案 |
|-----|------|
| IntelliJ | 装 `.env files support` 插件 → Run Config → EnvFile tab → 加 `.env.local` |
| VS Code | `launch.json` 加 `"envFile": "${workspaceFolder}/.env.local"` |
| 命令行 | 启动前手动 `$env:VAR = "value"`（PowerShell）或 `export VAR=value`（bash） |

### 3. application.yml 中所有 NACOS_* 必须是占位符，绝不写死

```yaml
# ✅ 安全
password: ${NACOS_PASSWORD}

# ❌ 违反红线 4（敏感信息进 git），且不允许
password: jianjiange
```

### 4. 启动前必查清单（fail-fast 防翻车）

- [ ] `.env.local` 存在且 NACOS_PASSWORD 已填
- [ ] IDE EnvFile 已指向 `.env.local`
- [ ] Nacos 控制台存在 `<service>-dev.yaml` 配置
- [ ] application.yml 里 `spring.cloud.nacos.config.import-check: true`（默认已开）

### 5. 数据库名规范

| 存储 | 命名规则 | 示例 |
|------|---------|------|
| PostgreSQL（JDBC URL） | 下划线 | `putao_dating_dev` |
| Nacos Config（JDBC 部分） | 下划线 | `putao_dating_dev` |
| MinIO Bucket | 连字符 | `putao-dating-dev` |

### 6. Flyway 多服务共存策略

每个服务用独立的 Flyway history 表：
```bash
-e FLYWAY_TABLE=flyway_history_<service>
```

同一数据库下多服务共存时，Flyway 必须在 `public` schema 管理（不指定 schema 隔离），
但所有表名必须带服务前缀（如 `im_*`、`post_*`）防止冲突。

### 7. 常见踩坑速查

| 现象 | 根因 | 解决 |
|------|------|------|
| `Property 'spring.cloud.nacos.password' is missing` | 没读 .env.local | 装插件 + 勾 EnvFile |
| `failed to connect to 38.76.188.242:8848` | Nacos 地址错 / VPN 没开 | 确认 NACOS_SERVER_ADDR |
| 启动成功但 datasource 字段为 null | Nacos 上 `<service>-dev.yaml` 不存在或缺字段 | 去 Nacos 控制台补配置 |
| PowerShell 报错"&& 不是有效分隔符" | 老版本 PS 不支持 `&&` | 用 `;` 串行命令 |
| `\ No newline at end of file` 警告 | 文件结尾没换行 | `Add-Content ""` 补一行后再 add |
| Flyway 迁移报错 `functions in index predicate must be marked IMMUTABLE` | 谓词索引用了 NOW() 等非 IMMUTABLE 函数 | 删除该索引或改用普通索引 |
| PostgreSQL `syntax error at or near "-"` | 数据库名含连字符未引号包裹 | 改用下划线 `putao_dating_dev` |
| MinIO `Bucket name contains invalid characters` | bucket 名含下划线 | 改用连字符 `putao-dating-dev` |
| `NoClassDefFoundError: io/grpc/InternalGlobalInterceptors` | gRPC 版本冲突（grpc-spring-boot-starter 2.15.x 只支持 grpc 1.58.x） | 统一降级 grpc.version 到 1.58.0 |
