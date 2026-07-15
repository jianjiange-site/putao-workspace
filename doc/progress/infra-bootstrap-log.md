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
| 各服务 Nacos yaml 配置 | ⏳ 待开始 | - | 上线前必须就位 |

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
- [ ] Nacos 控制台存在 `<service>-dev.yaml` 配置（**7 个新服务都还没建！**）
- [ ] application.yml 里 `spring.cloud.nacos.config.import-check: true`（默认已开）

### 5. 常见踩坑速查

| 现象 | 根因 | 解决 |
|------|------|------|
| `Property 'spring.cloud.nacos.password' is missing` | 没读 .env.local | 装插件 + 勾 EnvFile |
| `failed to connect to 38.76.188.242:8848` | Nacos 地址错 / VPN 没开 | 确认 NACOS_SERVER_ADDR |
| 启动成功但 datasource 字段为 null | Nacos 上 `<service>-dev.yaml` 不存在或缺字段 | 去 Nacos 控制台补配置 |
| PowerShell 报错"&& 不是有效分隔符" | 老版本 PS 不支持 `&&` | 用 `;` 串行命令 |
| `\ No newline at end of file` 警告 | 文件结尾没换行 | `Add-Content ""` 补一行后再 add |