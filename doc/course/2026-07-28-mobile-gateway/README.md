# mobile-gateway 学习笔记

> 学习日期：2026-07-28
> 学习范围：mobile-gateway — REST → gRPC BFF 网关、JWT 鉴权、设备管理、Token 刷新、BFF 聚合、限流、traceId 透传

## 本次学习内容

- [业务功能设计](./prd.md) - 7 大模块共 30+ 个 REST 接口（Auth / Profile / Match / Post / IM / Upload / Home / Health）
- [核心知识点](./knowledge.md) - 11 个技术知识点（BFF 模式、JWT RS256 双 token、ThreadLocal 上下文、gRPC Metadata 透传、限流分层、proto→VO 裁剪等）
- [面试问答](./interview-qa.md) - 12 个高频问题 + 3 个场景题 + 5 个常见坑

## 学习目标

- [ ] 理解 mobile-gateway 是 dating app 后端的"REST → gRPC" BFF 网关，不是纯路由网关
- [ ] 掌握 JWT 鉴权闭环（JwtIssuer + JwtVerifier + JwtAuthFilter + Redis 黑名单 + PG refresh_token）
- [ ] 理解 access token (RS256, 15min) + refresh token (opaque, 7d) 的双 token 设计
- [ ] 掌握 RequestContext ThreadLocal + gRPC Metadata 透传机制（下游服务无感鉴权）
- [ ] 能讲清 BFF 模式 vs 路由网关的取舍、为什么鉴权收口在网关内部不做独立 auth-service
- [ ] 理解 gateway 的鉴权域边界：仅持 auth_device + auth_refresh_token 两表，业务数据一律走 gRPC

## 关联知识

- 设计文档：`doc/specs/mobile-gateway-design.md`（v0.1 草案，已基本落地）
- 实现计划：暂无独立 plan（gateway 跨多个下游，迭代推进）
- 核心代码：`dating-server/mobile-gateway/src/main/java/com/dating/gateway/`

## 核心代码地图

```
mobile-gateway/src/main/java/com/dating/gateway/
├── MobileGatewayApplication.java              # 启动入口（@EnableDiscoveryClient + @MapperScan）
├── controller/                                # 7 个 REST 控制器（HTTP 入口）
│   ├── AuthController.java                    # 7 个鉴权接口（sms/login/refresh/logout/onboarding）
│   ├── ProfileController.java                 # 4 个用户档案接口（get/update/batch）
│   ├── MatchController.java                   # 3 个匹配接口（feed/swipe/super-hi）
│   ├── PostController.java                    # 10 个动态接口（CRUD/评论/点赞/Feed）
│   ├── ImController.java                      # 2 个 IM 接口（im-token/call-token）
│   ├── UploadController.java                  # 2 个上传接口（presign/confirm）
│   └── HealthController.java                  # 健康检查
├── service/                                   # 业务编排层
│   ├── AuthService.java + AuthServiceImpl.java  # 鉴权主流程（3 种登录 + token 刷新）
│   ├── ProfileService.java + ProfileServiceImpl.java  # 用户档案聚合
│   ├── MatchService.java + MatchServiceImpl.java  # 匹配+滑动
│   ├── PostService.java + PostServiceImpl.java  # 动态 CRUD
│   ├── ImService.java + ImServiceImpl.java     # IM Token 签发
│   ├── UploadService.java + UploadServiceImpl.java  # MinIO presign + confirm
│   └── HomeService.java + HomeServiceImpl.java  # 首页 BFF 聚合（推荐 + 用户资料）
├── manager/                                   # 鉴权域数据访问
│   ├── AuthDeviceManager.java                 # 设备 upsert + 计数
│   └── AuthRefreshTokenManager.java           # refresh token 生命周期管理
├── mapper/                                    # MyBatis-Plus BaseMapper
│   ├── AuthDeviceMapper.java
│   └── AuthRefreshTokenMapper.java
├── entity/                                    # 2 个 PG 实体
│   ├── AuthDeviceEntity.java
│   └── AuthRefreshTokenEntity.java
├── client/                                    # 5 个 gRPC client
│   ├── UserClient.java                        # → user-service (19090)
│   ├── PostClient.java                        # → post-service (19084)
│   ├── MatchClient.java                       # → match-service (19092)
│   ├── ImClient.java                          # → im-service (19091)
│   └── PaymentClient.java                     # → payment-service (19093) — stub 占位
├── security/                                  # JWT 安全模块
│   ├── JwtIssuer.java                         # access + refresh 双 token 签发
│   ├── JwtVerifier.java                       # 验签 + Redis 黑名单查询
│   └── RequestContext.java                    # ThreadLocal 上下文（userId/deviceId/traceId/jti）
├── filter/                                    # Servlet Filter 链
│   ├── JwtAuthFilter.java                     # @Order(1) JWT 鉴权，注入 RequestContext
│   └── TraceIdFilter.java                     # @Order(2) traceId 注入 MDC
├── interceptor/
│   └── GrpcClientMetadataInterceptor.java     # gRPC Metadata 透传 user_id/device_id/trace_id
├── config/                                    # 6 个配置类
│   ├── JwtConfig.java                         # RSA 2048 密钥对生成/加载
│   ├── WebMvcConfig.java                      # CORS
│   ├── RedisConfig.java                       # RedisTemplate String 序列化
│   ├── GrpcClientConfig.java                  # gRPC 客户端 host:port 配置
│   └── ResilienceRateLimiterConfig.java       # Resilience4j 限流器（global + auth 20req/60s）
├── exception/                                 # 异常体系
│   ├── GatewayException.java                  # 业务异常
│   ├── GlobalExceptionHandler.java            # @RestControllerAdvice 兜底
│   └── ErrorCodes.java                        # 错误码分段（105xx token / 106xx sms / 109xx upstream）
├── dto/                                       # 14 个移动端入参
├── vo/                                        # 12 个移动端出参（含 Result 包装类）
└── interceptor/                               # gRPC 拦截器
```

## 一句话定位

> mobile-gateway 是 dating app 后端的 **REST → gRPC BFF 网关**。它是移动端 RN App 的唯一 HTTPS 入口，承担五件事：(1) JWT 鉴权闭环（签发 / 验签 / 黑名单 / refresh token 轮换） + 设备指纹管理，(2) REST → gRPC 协议转换 + 字段裁剪，(3) BFF 聚合（首页卡片 = 推荐 + 用户资料 + 关注关系 + IM 在线），(4) Resilience4j 限流 + CORS + traceId 透传，(5) 鉴权域元数据透传（`userId`/`deviceId`/`traceId` 通过 gRPC Metadata 给下游，下游服务无感鉴权）。gateway 自身持久层仅覆盖鉴权域 2 张表（`auth_device` / `auth_refresh_token`），业务数据一律 gRPC 调下游。

## 重点数字（背下来能应付 80% 追问）

| 数字 | 含义 | 出现位置 |
|------|------|---------|
| **18080** | HTTP REST 端口 | `application.yml` |
| **19080** | gRPC Server 端口（gateway 自身目前未对外暴露 gRPC，但保留端口） | `application.yml` |
| **19090** | user-service gRPC 端口 | `application.yml` + `GrpcClientConfig` |
| **19084** | post-service gRPC 端口 | `application.yml` |
| **19091** | im-service gRPC 端口 | `application.yml` |
| **19092** | match-service gRPC 端口 | `application.yml` |
| **19093** | payment-service gRPC 端口 | `application.yml`（stub 占位） |
| **15 min** | access token 过期时间 | `JwtProperties.accessTokenExpirySeconds = 900` |
| **7 days** | refresh token 过期时间 | `JwtProperties.refreshTokenExpiryDays = 7` |
| **20 / 60s** | auth 接口限流阈值（每分钟 20 次） | `ResilienceRateLimiterConfig.authRateLimiter` |
| **500ms** | 限流等待超时 | `timeoutDuration(Duration.ofMillis(500))` |
| **2048** | RSA 密钥对位数 | `JwtConfig.generateKeyPair()` |
| **2** | gateway 持久层表数（auth_device + auth_refresh_token） | V1__init_auth_tables.sql |
| **6** | gateway 错误码段位数（105xx / 106xx / 109xx / 10xxx） | `ErrorCodes` |
| **5** | gRPC 下游服务数（user/post/im/match/payment） | `client/` |
| **7** | AuthController 接口数 | `AuthController` |
| **10** | PostController 接口数（最大） | `PostController` |
| **6** | Filter / Interceptor 总数（2 Filter + 1 Interceptor + 3 @Order） | `filter/` + `interceptor/` |

## mobile-gateway 的关键决策点

### 决策 1：BFF 而非纯路由网关
- **纯路由网关（Spring Cloud Gateway / Kong）**：基于路由表转发 → 下游 HTTP 服务。但本系统下游全是 gRPC，路由网关的"路由转发 + 负载均衡"优势用不上。
- **BFF（Backend for Frontend）网关**：在网关内部做协议转换 + 字段裁剪 + 业务聚合。移动端接口常常需要聚合多服务（首页卡片 = 推荐 + 资料 + 关注 + 在线），BFF 模式天然契合。
- → 选 BFF：在网关一处收口字段风格、版本适配、业务聚合，下游 gRPC 服务保持纯净（不感知 HTTP / 移动端字段风格）。

### 决策 2：鉴权能力收口在网关内部（无独立 auth-service）
- **建独立 auth-service**：网关 → auth-service gRPC 拿 token → 下游再拿 token 验签。多一跳 gRPC + 两份密钥管理。
- **网关内自闭环**：登录 / 签发 / 验签 / 黑名单 / refresh / 登出都在 mobile-gateway 内部。公私钥在 gateway 节点本地持有，黑名单在 gateway 自己的 Redis。
- → 当前阶段鉴权流量与网关 1:1，独立服务只多一跳 RPC 没有收益；将来接入 H5 / web-bff 时再抽 `gateway-auth` 内部模块共享。

### 决策 3：JWT RS256 非对称 + 双 token
- **对称 HS256**：签发/验签共享密钥 → 多服务要共享密钥才能验签 → 密钥泄漏面大。
- **非对称 RS256**：私钥仅 gateway 持有（签发用），公钥可全量下发（验签用） → 验签方不持有私钥，泄漏面降到最低。
- → 选 RS256，私钥从 Nacos Config 注入到内存，不落盘。
- **access + refresh 双 token**：access 短（15min）防泄漏风险；refresh 长（7d）但不可直接访问业务接口，只能用于刷新 access → 即使 refresh 泄漏也只能续命，攻击面有限。

### 决策 4：ThreadLocal RequestContext + gRPC Metadata 透传
- **传统做法**：每个 service 方法加 `Long userId` 参数，污染业务代码。
- **ThreadLocal + 拦截器**：JwtAuthFilter 解析 token → 写入 RequestContext ThreadLocal → 业务代码 `RequestContext.current().getUserId()` 一行调用；gRPC ClientInterceptor 自动把 userId/deviceId/traceId 通过 Metadata 透传到下游。
- → 下游业务服务无需感知 token，从 gRPC Metadata 拿 `x-user-id` 即可，App / 网关 / 业务三层解耦。

### 决策 5：refresh token 哈希存 PG + token 轮换
- **明文存 PG**：数据库泄漏 → 攻击者直接拿 refresh token。
- **SHA-256 哈希存 PG**：数据库泄漏 → 攻击者拿到的是哈希，反推不出原 token。
- **轮换（rotation）**：每次刷新把旧 refresh 标记为 used（`used_at`），签发新 refresh → 即使旧 refresh 泄漏也只能用一次，且旧 refresh used 后还能用来检测重放攻击。
- → 用 `auth_refresh_token.token_hash`（SHA-256 hex）+ `used_at` 字段实现 refresh token 单次使用。

### 决策 6：gateway 持久层仅鉴权域（红线 #2）
- **每个服务一张表**：`auth_device` / `auth_refresh_token`，表前缀 `auth_` 明确边界。
- **业务数据一律 gRPC**：用户档案 / 帖子 / 匹配 / IM Token 等全部 gRPC 调下游，gateway 不写业务表、不读业务 Redis key。
- → 严守红线 #2：跨服务直连别人家的库表/Redis 一票否决。

### 决策 7：Resilience4j 三档限流（接口级 + 用户级 + IP 级）
- **接口级**：Resilience4j `@RateLimiter`，按 `name=auth` 限 20 req/60s。
- **用户级**：Redis + Lua 滑动窗口（设计中，代码层已埋 `RedisTemplate` 基础设施）。
- **IP 级**：Nginx 层（不在 gateway 重复实现）。
- → 三层防御：粗粒度（接口）挡住整体洪流 + 中粒度（用户）挡住单用户刷 + 细粒度（IP）挡地域性攻击。

### 决策 8：HTTP status 默认 200 + 业务码在 body
- **传统 REST**：401/403/500 区分错误 → 客户端需要 switch (status) 多分支处理。
- **统一 200 + 业务码**：body 里 `code` 字段区分成功/失败 → 客户端只看 body 一个分支，业务码由后端按段位定义（105xx token / 106xx sms / 109xx upstream）。
- → App 端处理逻辑统一，错误码段位化便于按服务定位问题（看码即知归属 gateway / user / payment）。

## mobile-gateway 的关键边界

| 边界 | mobile-gateway 的角色 |
|------|------------------|
| App | **唯一 HTTPS 入口**；JWT 签发方；黑名单管理者；REST ↔ gRPC 翻译 |
| user-service | 调用方（getProfile / updateProfile / batchGetUserProfiles gRPC） |
| post-service | 调用方（createPost / getPostDetail / actionLike / createComment / listComments / getRecommendFeed gRPC） |
| match-service | 调用方（matchAction / getRecommendations gRPC） |
| im-service | 调用方（getImToken gRPC）；**IM 长连由 App 直连 OpenIM，gateway 不维护 WS**（红线 #7） |
| payment-service | 调用方占位（PaymentClient stub 化，等待 payment-service 上线） |
| Redis（`gateway:*` 前缀） | 仅自用：JWT 黑名单（`gateway:auth:blacklist:<jti>`）、限流计数 |
| PostgreSQL（`auth_*` 前缀表） | 仅自用：auth_device + auth_refresh_token |
| MinIO | 仅自用：upload presign URL 生成 + confirm 回调（gateway 不读文件流） |

## 学习路径建议

1. 先看 `prd.md` 把 7 个控制器 + 30+ 个 REST 接口吃透，重点是鉴权流（手机/设备/三方 → token 签发 → refresh 轮换 → 登出黑名单）
2. 再看 `knowledge.md` 把每个技术决策的 why 理解（为什么 BFF、为什么 RS256、为什么双 token、为什么 ThreadLocal 透传）
3. 最后看 `interview-qa.md` 整理话术，模拟被问 Q1（BFF vs 路由网关）/ Q3（JWT 双 token 设计）/ Q8（refresh token 轮换与重放）三个最高频问题

## 已知 gap（面试时主动提及）

1. **`AuthServiceImpl.sendSmsCode` 只打 log 未实现** — 真实生产需要接 SMS 服务商（阿里云/腾讯云短信）+ 验证码存 Redis + 限流（5 次/小时）
2. **`AuthServiceImpl.refreshToken` 抛 `Not implemented`** — refresh 轮换 + 重放检测需要补 `AuthRefreshTokenManager.findValidByTokenHash` + `markUsed` + `revokeAllForUser`
3. **`AuthServiceImpl.logout` 只打 log 未实现** — 需要：① 黑名单写入（`JwtVerifier.blacklist(jti, ttl)`），② `AuthRefreshTokenManager.revokeAllForUser(userId, deviceId)`
4. **`AuthServiceImpl.loginPhone` / `loginThirdParty` 用 `System.currentTimeMillis()` 作 userId 占位** — 真实生产需要调 user-service `ResolveOrCreateByPhone` / `ResolveOrCreateByThirdParty` gRPC
5. **`ImServiceImpl.getCallToken` 返回空 VO** — LiveKit JWT 签发依赖 im-service 的 `GenerateCallToken` gRPC，但当前 im-service 端该接口也是 TODO 占位（见 im-service 学习笔记已知 gap #1）
6. **`PaymentClient` 整体 stub** — `getBalance` / `purchaseCoins` 返回 hardcode 空响应；payment-service 上线后才能补真实 gRPC 调用
7. **`MatchServiceImpl.getMatches` / `getSwipeHistory` 返回 `List.of()`** — 当前未实现，等 match-service 补对应 gRPC 接口
8. **`HomeServiceImpl.getHomeCards` 调用了 `userClient.batchGetUserProfiles` 但 VO 字段没用上** — 当前实现忽略 batch 返回值，从 match 返回的 Recommendation 里直接取 nickname/age/avatar/bio，bio 字段被硬编码 `""`；后续需要整合 batch 返回的 bio
9. **`@RestControllerAdvice` 未生效** — `GlobalExceptionHandler` 当前类上没有 `@RestControllerAdvice` 注解，是普通类，方法签名也未标 `@ExceptionHandler`；生产前需要补注解，否则全局异常兜底失效
10. **`UploadServiceImpl.presign` 拼接的 URL 不可用** — 拼接方式是 `endpoint + "/" + bucket + "/" + objectKey + "?presigned=true"`，不是 MinIO SDK 生成的真正 presigned PUT URL（缺签名参数）；需要接入 MinIO SDK `getPresignedObjectUrl` 才能产生合法签名 URL

## 关联文档

- `doc/specs/mobile-gateway-design.md` — 技术方案 v0.1 草案（已落地大部分）
- `doc/course/2026-07-25-payment-service/knowledge.md` — payment-service 幂等设计（gateway 调 payment 时用得上）
- `doc/course/2026-07-26-im-service/knowledge.md` — IM Provider 适配器（gateway 调 im-service 的 GetImToken 时依赖）
- `doc/course/2026-07-23-match-service/knowledge.md` — DH 模拟计划（HomeService 聚合推荐结果时 match-service 是源头）
