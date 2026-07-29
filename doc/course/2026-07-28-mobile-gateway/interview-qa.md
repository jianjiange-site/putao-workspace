# mobile-gateway 面试问答

> 配套：[`README.md`](./README.md)、[`prd.md`](./prd.md)、[`knowledge.md`](./knowledge.md)
>
> 本文按"高频问题 → 追问 → 场景题 → 常见坑"四部分组织。所有答案基于 mobile-gateway 真实代码 + 设计文档，可直接背用。

---

## 高频问题

### Q1: 什么是 BFF 网关？mobile-gateway 跟 Spring Cloud Gateway 有什么区别？

**考察点**：微服务架构理解、BFF 模式 vs 路由网关的取舍。

**标准答案**：

- **BFF（Backend for Frontend）网关**：网关不只做路由，还做**协议转换 + 字段裁剪 + 业务聚合**。每个端（移动端 / Web / 第三方）有独立的 BFF。
- **路由网关**（Spring Cloud Gateway / Kong / Nginx）：基于路由表做转发 + 负载均衡，下游是 HTTP 服务。

mobile-gateway 选 BFF 的三个理由：

1. **下游全是 gRPC**：路由网关的"按 URL 前缀路由 + HTTP 负载均衡"优势用不上；BFF 的"协议转换（REST → gRPC）"才能发挥价值。
2. **聚合场景多**：移动端首页 = 推荐 + 资料 + 关注 + 在线 4 个数据 → BFF 在网关内并发调 4 个 RPC 拼装 VO，App 只发一次请求；路由网关要 App 自己串/并发调 4 个 REST。
3. **字段风格统一**：BFF 在网关一处裁剪字段（如 `avatarKey` → `avatar`，隐去内部 ID），下游 gRPC 服务保持纯净（不感知 HTTP 风格）。

**追问**：
- **Q: BFF 网关不会膨胀吗？怎么控制？**
  A: 会的。控制方式：(1) 按业务垂直拆分 BFF（mobile-bff / web-bff / open-api-bff），不要做成"什么都管"的胖 BFF；(2) 业务聚合场景按域划分（如 HomeService 只管首页聚合），避免一个 service 写 500 行；(3) 关键聚合场景用 `CompletableFuture` 并发 + 专用线程池隔离，避免阻塞 Tomcat 线程。

- **Q: 为什么不用 Spring Cloud Gateway？**
  A: SCG 主要给"下游 HTTP 服务"用，本系统下游 5 个服务全是 gRPC，SCG 的 HTTP 路由转发优势用不上；SCG 也不擅长做 BFF 聚合（虽然可以写 GlobalFilter，但聚合代码写在过滤器里调试栈会很乱）。

- **Q: BFF 网关和路由网关能共存吗？**
  A: 可以。Nginx（TLS 终结 + IP 限流）→ mobile-gateway（BFF 聚合 + 鉴权 + 协议转换）→ 下游 gRPC 服务，三层各管一段。

### Q2: JWT 鉴权为什么用 RS256 非对称而不是 HS256 对称？

**考察点**：JWT 算法选型 + 密钥管理 + 安全权衡。

**标准答案**：

| 算法 | 密钥管理 | 性能 | 安全 |
|------|---------|------|------|
| HS256 对称 | 签发/验签共享密钥 | 快 | 密钥要全量同步到所有验签方，泄漏面大 |
| **RS256 非对称** | 私钥签发、公钥验签 | 稍慢 | 公钥可全量下发，私钥仅签发方持有 |
| EdDSA（Ed25519） | 类似 RS256 | 更快 | jjwt 生态不够成熟 |

mobile-gateway 选 RS256 的三个理由：

1. **下游服务未来要验签 token**：如果用 HS256，下游 4 个服务都要持有对称密钥 → 泄漏点 × 4。RS256 私钥只在 gateway，下游用公钥验签 → 泄漏面降到 1。
2. **密钥轮换更安全**：HS256 轮换密钥要全量服务同步改，容易漏；RS256 私钥轮换不影响公钥验签（前提是保留旧公钥一段时间），灰度切换更平滑。
3. **密钥存储更安全**：私钥从 Nacos Config 拉到内存，**不落盘** → 容器销毁即丢失；公钥可以公开分发。

**追问**：
- **Q: 私钥放 Nacos 安全吗？**
  A: Nacos Config 本身有 ACL 控制 + 加密存储能力，比放本地文件系统安全。生产环境应该开 Nacos 命名空间隔离 + 凭据加密。设计文档约定私钥 base64 编码后从 Nacos 注入。

- **Q: RSA 2048 位够安全吗？**
  A: 足够。RSA 2048 破解成本约 $10亿（参考 NIST 2024 评估），dating app 用户量级远不到需要 4096 位的程度。如果未来需要更强，可以升级到 RSA 3072 或切换到 Ed25519。

- **Q: 为什么不直接用 HTTPS？**
  A: HTTPS 解决的是传输加密（链路层），JWT 解决的是身份认证 + 完整性校验（应用层）。两者是不同层面的：HTTPS 防中间人窃听，JWT 防身份伪造。生产用 HTTPS + JWT 双层防护。

### Q3: 为什么用 access token + refresh token 双 token？只用 access 行不行？

**考察点**：JWT 不可撤销的设计权衡 + 用户体验。

**标准答案**：

- **只用 access token** 面临两难：
  - 过期时间短（5min）→ 用户每 5 分钟重新登录，UX 差
  - 过期时间长（30天）→ token 泄漏后 30 天都能用，安全差

- **双 token 设计**：
  - **Access token**（JWT, RS256, 15min）：携带在 Authorization Header，业务接口鉴权。短过期 → 泄漏窗口小。
  - **Refresh token**（Opaque UUID, 7d）：仅用于换新 access token，**不能直接访问业务接口**。长过期 → 用户 7 天不感知登录。

- **核心思想**：把"高频鉴权"和"身份续命"分开。
  - 高频（每个 HTTP 请求）→ access token，每次验签（公钥 RS256，无状态）
  - 低频（每 15 分钟一次）→ refresh token，每次查 PG（有状态，可撤销）

**追问**：
- **Q: refresh token 泄漏了怎么办？**
  A: 三个机制兜底：(1) refresh token 单次使用 + 轮换（每次刷新换新，旧 refresh 失效）；(2) 重放检测（旧 refresh 第二次用 → 触发 → 撤销该用户全部 refresh）；(3) 设备绑定（refresh 必须与 access 在同一 deviceId 签发，跨设备视为异常）。三件套配合，泄漏后最坏情况是损失 ≤ 7 天有效期，且用户感知到异常登录会被主动改密码。

- **Q: access token 泄漏了怎么办？**
  A: 写 Redis 黑名单（`gateway:auth:blacklist:<jti>`），TTL = access 剩余有效期（最多 15 分钟）。JwtVerifier 验签后查黑名单 → 命中视为无效 → 强制用户重新登录。短期泄漏窗口（15 分钟）+ 可主动撤销。

- **Q: 为什么 access 用 JWT 而 refresh 用 opaque？**
  A: access 是高频无状态查询（每次请求都验签），JWT 自带签名 + 无需查库，性能高；refresh 是低频有状态查询（每 15 分钟一次），用 opaque UUID 配 PG 表可以：(1) 单次使用（JWT 的 jti 字段做不到主动标记 used_at）；(2) 设备绑定（opaque + PG 字段灵活）；(3) 重放检测（PG 行 `used_at` 字段）。

### Q4: gateway 怎么把 userId 透传给下游 gRPC 服务？为什么不让下游持有 token？

**考察点**：微服务鉴权设计 + gRPC Metadata 透传。

**标准答案**：

**ThreadLocal + gRPC Interceptor 双层机制**：

```java
// 1. JwtAuthFilter 解析 token → 写 ThreadLocal
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request, ...) {
        var claims = jwtVerifier.verifyAccessToken(token).get();
        RequestContext.set(claims.userId(), claims.deviceId(), traceId, claims.jti());
        try { filterChain.doFilter(request, response); }
        finally { RequestContext.clear(); }  // 线程复用必须清理
    }
}

// 2. GrpcClientMetadataInterceptor 自动从 ThreadLocal 透传到下游
@Component
public class GrpcClientMetadataInterceptor implements ClientInterceptor {
    public ClientCall<ReqT, RespT> interceptCall(...) {
        Metadata metadata = new Metadata();
        RequestContext ctx = RequestContext.current();
        if (ctx.getUserId() != null) {
            metadata.put(Metadata.Key.of("x-user-id", ASCII_STRING_MARSHALLER),
                    ctx.getUserId().toString());
        }
        // x-device-id, x-trace-id 同理
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(...) {
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.merge(metadata);
                super.start(responseListener, headers);
            }
        };
    }
}
```

**业务代码变成**：
```java
// controller 一行拿 userId，service 不传 userId 参数
@PostMapping("/api/v1/posts")
public Result<Map<String, Long>> createPost(@Valid @RequestBody CreatePostReq req) {
    Long userId = RequestContext.current().getUserId();  // ← ThreadLocal
    Long postId = postService.createPost(req);  // service 内部从 ThreadLocal 拿
    return Result.ok(Map.of("postId", postId));
}
```

**为什么不直接让下游持有 token？** 三个原因：

1. **安全**：token 落到下游 4 个服务，泄漏面 × 4
2. **重复劳动**：下游都要实现 JWT 验签 + 黑名单查询 → 重复造轮子
3. **业务入侵**：每个下游接口方法都要加 `Long userId` 参数 → 污染业务代码

→ **下游从 gRPC Metadata 拿 `x-user-id` 即可，不持有 token**，App / 网关 / 业务三层解耦。

**追问**：
- **Q: ThreadLocal 在 Tomcat 线程复用下会污染吗？**
  A: 不会。`RequestContext.clear()` 在 `JwtAuthFilter` 的 `finally` 块中调用 → 每个请求结束后清理 ThreadLocal → 下一个请求进来时 ThreadLocal 为 null，`RequestContext.current()` 返回 empty（设计兜底）。`@Order(1)` 保证 JwtAuthFilter 在业务代码前执行。

- **Q: 如果用 WebFlux 异步，ThreadLocal 不就失效了吗？**
  A: 是的。WebFlux 用 reactor 响应式，线程会切换，ThreadLocal 会丢。要用 Reactor Context 或 MDC（Mapped Diagnostic Context）替代。本项目选 MVC + 虚拟线程（JDK 21），保留同步编程模型 + ThreadLocal。

- **Q: gRPC Metadata 怎么约定 key 命名？**
  A: 设计文档 §5.7 约定 `x-user-id` / `x-device-id` / `x-trace-id`，小写 + 横线分隔。下游业务服务通过 `Metadata.Key.of("x-user-id", ASCII_STRING_MARSHALLER)` 读取。

### Q5: mobile-gateway 为什么要把鉴权收口在内部？而不是独立 auth-service？

**考察点**：微服务拆分粒度 + 鉴权架构设计。

**标准答案**：

**已决策：鉴权能力全部内聚在 mobile-gateway**，不单独建 auth-service。三个理由：

1. **鉴权流量与网关 1:1**：当前阶段移动端是唯一接入端，登录/签发/验签/黑名单流量全部走网关。独立 auth-service → 网关 → auth-service 多一跳 gRPC，没有收益。
2. **签发/验签同源**：JWT 签发和验签共用密钥（RS256 私钥/公钥）、共用黑名单（Redis）、共用 refresh 表（PG）。拆成两个服务要维护两份配置 + 两份凭证管理。
3. **将来多端复用**：H5 / web-bff 接入时，鉴权逻辑通过 `gateway-auth` 内部模块抽出来共享（同代码、不同进程），而不是上来就做远程服务化。

**追问**：
- **Q: 什么时候应该拆 auth-service？**
  A: 当出现以下信号时：(1) 鉴权流量与业务流量比例超过 1:5（鉴权占比过高，影响业务服务稳定性）；(2) 多个 BFF（mobile / web / open-api）共享鉴权逻辑时（共享代码演进为共享服务）；(3) 鉴权规则需要独立灰度发布时。当前 dating app 不满足这三个条件。

- **Q: 鉴权不独立服务，怎么保证多端复用？**
  A: 设计文档 §9 待决策提到：H5 / 第三方接入时倾向另起 `web-bff`，鉴权逻辑抽 `gateway-auth` 内部模块共享（同代码包、不同进程）。这是一种"代码共享先于服务化"的演进路径，避免过早拆分。

- **Q: 网关挂了，所有用户都登不上了？**
  A: 是的，这是 BFF 内聚鉴权的代价。解决方式：(1) 网关多实例部署（K8s 部署 2-3 副本）+ 负载均衡，单实例挂了不影响；(2) 鉴权域表用独立 schema（`auth_*` 前缀），即使网关崩了数据不丢；(3) 健康检查 + 自动重启（K8s liveness probe）。

### Q6: refresh token 为什么要哈希存 PG？明文存不行吗？

**考察点**：密钥 / token 存储安全 + 哈希算法选型。

**标准答案**：

**明文存的威胁**：
- PG 备份泄漏 → 攻击者拿整库 refresh token → 7 天内无限续命
- DBA/开发误查 → 看到所有用户的 refresh token → 内部威胁
- SQL 注入 → 直接读出 token → 用户失陷

**SHA-256 哈希存**：

```java
// AuthServiceImpl.createLoginResult：哈希存 PG
private String hashToken(String token) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);  // SHA-256 hex 输出
}

// 保存时只存哈希
AuthRefreshTokenEntity entity = new AuthRefreshTokenEntity();
entity.setTokenHash(hashToken(tokens.refreshToken()));  // ← 哈希
authRefreshTokenManager.saveRefreshToken(entity);
```

**为什么 SHA-256 而不是 bcrypt/argon2？**

| 算法 | 抗彩虹表 | 性能 | 适用场景 |
|------|---------|------|---------|
| SHA-256 | 不抗（要加盐） | 快（一次哈希微秒级） | 高熵 token 哈希 |
| bcrypt | 抗 | 慢（百毫秒级） | 低熵密码哈希 |
| argon2 | 抗 + 抗 GPU | 较慢 | 抗暴力破解 |

refresh token 是 128 位高熵 UUID（不可预测），不需要 bcrypt 加盐抗彩虹表；refresh 是高频查询（每次续命都查 PG），SHA-256 哈希快。

**追问**：
- **Q: 哈希后还能反查吗？**
  A: 不能。SHA-256 是单向函数，PG 泄漏后攻击者只能拿到哈希，反推原 token 需要暴力破解 128 位 UUID 空间（不可能）。代价是反查功能（如"列出某用户所有 refresh"）不能做，只能按 tokenHash 等值匹配。

- **Q: 如果攻击者同时拿到 PG 和 Redis（哈希 + 明文），哈希还有用吗？**
  A: 没用。哈希只能防"PG 单独泄漏"的场景。如果 Redis 黑名单 + PG refresh_token 都被攻破，攻击者可以：(1) 把 access token 写到黑名单 → 让当前用户登出（无价值）；(2) 用 refresh token 换新 access → 7 天有效。所以**安全是多层防御**：(1) Redis 单独泄漏不危险（黑名单只是加速失效）；(2) PG 单独泄漏只能拿到哈希；(3) 两者都被攻破才真正失陷，且攻击窗口 ≤ 7 天。

- **Q: 为什么不用 bcrypt 加盐？**
  A: refresh token 是高熵随机 UUID（128 bit 熵 ≈ 2^128），加盐意义不大（攻击者没有彩虹表可以匹配），且 bcrypt 慢（百毫秒）会影响 refresh 查询性能。SHA-256 一次哈希微秒级，对 128 位熵 token 足够安全。

### Q7: mobile-gateway 的持久层只有 2 张表（auth_device + auth_refresh_token），为什么不缓存业务数据？

**考察点**：CLAUDE.md 红线 #2 实践 + 服务边界设计。

**标准答案**：

**CLAUDE.md 红线 #2**：❌ 跨服务直连别人家的库表/Redis/对象桶

mobile-gateway 严守这条红线的三个表现：

1. **持久层仅鉴权域 2 张表**：`auth_device` / `auth_refresh_token`，表前缀 `auth_` 明确边界。
2. **业务数据一律 gRPC**：用户档案 / 帖子 / 匹配 / IM Token 等全部 gRPC 调下游，gateway 不写业务表、不读业务 Redis key。
3. **Redis key 前缀隔离**：`gateway:auth:blacklist:<jti>` 前缀 `gateway:`，不与下游服务的 `putao:user:*` / `putao:post:*` 撞车。

**为什么不缓存业务数据？**

| 缓存层 | 谁负责 | mobile-gateway 的角色 |
|--------|--------|---------------------|
| 用户资料缓存 | user-service 自管 | gateway 不缓存，调 user-service gRPC |
| 帖子缓存 | post-service 自管 | gateway 不缓存，调 post-service gRPC |
| 匹配推荐缓存 | match-service 自管 | gateway 不缓存，调 match-service gRPC |
| 在线状态缓存 | im-service 自管（Redis ZSet） | gateway 不缓存，调 im-service gRPC |
| **JWT 黑名单** | **gateway 自管**（`gateway:auth:blacklist:*`） | **唯一缓存职责** |
| **限流计数** | **gateway 自管**（`lock:gateway:rate:*`） | **唯一缓存职责** |

→ gateway 只缓存"自己产生的、需要跨请求追踪的数据"（黑名单、限流计数），业务数据缓存下沉到各自业务服务。

**追问**：
- **Q: 如果下游服务慢，gateway 加一层缓存提速行不行？**
  A: 不行。三个理由：(1) 缓存一致性难保证（业务数据变化时 gateway 缓存怎么失效？）；(2) 缓存击穿 / 雪崩要 gateway 处理（增加复杂度）；(3) 各业务服务自己已经有缓存层（user-service 的 profile 缓存、match-service 的推荐缓存），gateway 再加一层是重复建设且易出现数据不一致。

- **Q: 聚合时 BFF 能不能加缓存？**
  A: 可以但要小心。聚合结果是 VO（已拼装好的移动端字段），缓存 VO 比缓存底层业务数据安全一些（不直接泄漏业务数据），但仍要：(1) 设置短 TTL（30-60 秒）；(2) 业务数据变更时主动失效（用户改头像 → 删首页缓存）；(3) 监控缓存命中率评估效果。

### Q8: refresh token 怎么防重放攻击？

**考察点**：单次使用 + 轮换 + 重放检测机制。

**标准答案**：

**重放攻击场景**：
- 攻击者拿到用户 refresh token（如 XSS 截获、App 本地泄漏）
- 攻击者用 refresh 换新 access → 用户 access 也被换走
- 用户刷新 → 用旧 refresh 第二次刷新 → 攻击者已经把 access 用走 → 用户登出

**三件套防御**：

1. **单次使用 + 轮换（Rotation）**：
```java
// AuthServiceImpl.refreshToken：每次刷新换新，旧 refresh 标 used_at
authRefreshTokenManager.markUsed(oldEntity);  // UPDATE SET used_at = NOW()
authRefreshTokenManager.saveRefreshToken(newEntity);  // INSERT 新行
```

2. **重放检测（Replay Detection）**：
```java
// 旧 refresh 第二次用会触发 used_at != null 分支
if (entity.getUsedAt() != null) {
    // 触发重放检测 → 撤销该用户该设备全部 refresh
    authRefreshTokenManager.revokeAllForUser(entity.getUserId(), entity.getDeviceId());
    throw new GatewayException(REFRESH_TOKEN_REUSED, "refresh token 已失效，请重新登录");
}
```

3. **设备绑定（Device Binding）**：
```java
// refresh 签发时记录 deviceId，刷新时校验匹配
if (!entity.getDeviceId().equals(currentDeviceId)) {
    throw new GatewayException(REFRESH_TOKEN_DEVICE_MISMATCH, "设备不匹配");
}
```

**重放触发的链条**：

```
攻击者用旧 refresh 第二次换 access
   ↓
gateway.AuthServiceImpl.refreshToken 检测 used_at != null
   ↓
authRefreshTokenManager.revokeAllForUser(userId, deviceId)
   ↓
UPDATE auth_refresh_token SET revoked_at=NOW() WHERE user_id=? AND device_id=?
   ↓
该用户该 device 全部 refresh 失效 → 攻击者即使有再多旧 refresh 全部失效
   ↓
用户被强制重新登录（攻击者同时也失去 access）
```

**追问**：
- **Q: 重放检测会不会误杀正常用户？**
  A: 有可能。场景：用户点"刷新"按钮两次（手抖/网络重试），第二次会触发重放检测 → 撤销全部 refresh → 用户被登出。解决方式：(1) 客户端 idempotency key（同一次刷新用同一 key，server 去重）；(2) 短时间内第二次 refresh 直接返回第一次的结果（要求 server 端记录 idempotency）。

- **Q: 重放检测到要不要报警？**
  A: 要。production 环境应该在 `revokeAllForUser` 调用时发一条安全日志（含 userId / deviceId / IP），监控短时间内同用户重放次数，如果超过阈值（如 1 小时内 3 次）触发风控告警（可能账号被盗）。

- **Q: refresh token 还能再保险吗？**
  A: 可以加 (1) IP 绑定（refresh 只能在签发时的 IP 段使用，跨 IP 视为异常）；(2) User-Agent 绑定（App 升级后旧 refresh 失效）。但代价是用户体验变差（升级 App 后必须重新登录）。当前实现只用 deviceId 绑定是性价比最高的方案。

### Q9: gateway 怎么做限流？Resilience4j 的原理是什么？

**考察点**：限流架构 + 令牌桶算法 + 三层防御。

**标准答案**：

**三档组合防御**：

| 维度 | 实现 | 备注 |
|------|------|------|
| 接口级 | Resilience4j `@RateLimiter(name="auth")` | 配置在 Nacos，动态生效 |
| 用户级 | Redis + Lua 滑动窗口 | key `lock:gateway:rate:<userId>:<api>`（设计约定） |
| IP 级 | Nginx 层 | 不在 gateway 重复实现 |

**Resilience4j RateLimiter 配置**：

```java
// ResilienceRateLimiterConfig
@Bean
public RateLimiter authRateLimiter(RateLimiterRegistry registry) {
    RateLimiterConfig config = RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofSeconds(60))  // 60 秒窗口
            .limitForPeriod(20)                          // 20 个许可
            .timeoutDuration(Duration.ofMillis(500))     // 等许可最多 500ms
            .build();
    return registry.rateLimiter("auth", config);
}
```

**令牌桶算法（Token Bucket）**：
- 桶里最多 N 个令牌，每 T 时间补充 1 个令牌
- 请求进来 → 拿 1 个令牌 → 有令牌放行，无令牌拒绝（或等令牌）
- 突发流量：桶空时瞬间可放 N 个请求（峰值），稳定后按 T 时间 1 个通过

**mobile-gateway 现状**：
- 接口级：Resilience4j 配置类已建好，但 `@RateLimiter` 注解未加在 controller 上（gap）
- 用户级：设计文档约定，未实现
- IP 级：Nginx 层处理（生产配置）

**追问**：
- **Q: 限流算法选令牌桶还是滑动窗口？**
  A: 取决于场景。令牌桶允许突发流量（适合 API 网关，突发用户打开 App 多发请求），滑动窗口严格按时间窗口计数（适合秒杀场景，严格限速）。mobile-gateway 选令牌桶，因为 App 启动时可能瞬间发多个请求（首页 Feed + 推荐 + 用户资料并发），令牌桶能容忍这种突发。

- **Q: 限流命中返回什么？**
  A: HTTP 429 + 业务码 `TOO_MANY_REQUESTS=42900`。HTTP 状态码表达"被限流"语义，业务码便于 App 按服务维度处理（用户看到"请求过于频繁"提示）。响应 header 加 `Retry-After: 60` 告诉客户端何时重试。

- **Q: 限流会不会误杀正常用户？**
  A: 会。场景：用户 App 频繁切换页面触发多次 Feed 拉取，单用户短时间内可能触发限流。解决方式：(1) 接口级限流阈值要宽松（auth 20 req/60s 已经很宽松）；(2) 用户级限流是兜底，按 (userId + api) 组合 key，单接口被刷影响其他接口；(3) IP 级限流避免单 IP 多账号刷。

### Q10: gateway 怎么做 traceId 透传？为什么日志需要 traceId？

**考察点**：分布式链路追踪 + MDC + gRPC Metadata。

**标准答案**：

**traceId 的价值**：
- 一次 App 操作（"查看推荐"）→ gateway → match-service + user-service + im-service 三个下游
- 日志散落各处（每个服务一份），没有 traceId 关联 → 排查问题要逐个服务翻日志
- 有 traceId → 所有相关日志一行 grep 出来

**链路设计**：

```
App (发起请求) → "X-Trace-ID: abc123"
   ↓
[Nginx] 透传 X-Trace-ID
   ↓
[mobile-gateway]
   ├─ TraceIdFilter：从 header 拿，没有则生成 UUID，写 MDC
   ├─ JwtAuthFilter：再次拿（顺序在 TraceIdFilter 前，但都做了同样逻辑）
   ├─ RequestContext.set(userId, deviceId, traceId=abc123, jti)
   ├─ 业务代码：logback pattern [%X{traceId}] 自动输出 abc123
   ├─ GrpcClientMetadataInterceptor：metadata.put("x-trace-id", "abc123")
   └─ response.setHeader("X-Trace-ID", "abc123") 返回给 App
   ↓
[match-service / user-service / im-service]
   ├─ ServerInterceptor：metadata.get("x-trace-id") = abc123
   ├─ 写 MDC
   └─ logback 输出 [%X{traceId}] abc123
   ↓
所有日志按 traceId=abc123 聚合（Loki / ELK 查询）
```

**关键代码**：

```java
// TraceIdFilter：写 MDC + 响应头
@Component
@Order(2)
public class TraceIdFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String traceId = request.getHeader("X-Trace-ID");
        if (traceId == null || traceId.isEmpty()) traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);  // ← SLF4J MDC，logback 自动输出
        response.setHeader("X-Trace-ID", traceId);  // ← 返回给 App
        try { filterChain.doFilter(request, response); }
        finally { MDC.remove("traceId"); }
    }
}

// logback 配置（application.yml）
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{36} - %msg%n"
//                                                ↑ MDC key=traceId，没有则输出 -
```

**追问**：
- **Q: 为什么不直接用 OpenTelemetry / SkyWalking？**
  A: 当前实现是简化版（自定义 X-Trace-ID header + MDC），适用于单请求链路追踪。如果未来要做完整的分布式追踪（包含调用链 + span + parent_id），可以切换到 OpenTelemetry SDK，自动注入 W3C TraceContext + 兼容 Zipkin / Jaeger。当前是"够用就好"的渐进式演进。

- **Q: App 一定要传 X-Trace-ID 吗？**
  A: 不强制。如果 App 不传，gateway 自动生成 UUID 并写响应头。生产中 App 端可以在 SDK 里每次请求都生成 UUID，让用户反馈问题时能给出 traceId 便于客服定位。

- **Q: 如果 traceId 在多个 Filter 都设置了，会不会冲突？**
  A: 不会。`JwtAuthFilter` 在 `RequestContext` 设置 traceId，`TraceIdFilter` 在 MDC 设置 traceId，两者不冲突（一个 ThreadLocal 一个 SLF4J MDC）。但都做了"从 header 拿 / 没有则生成"逻辑 → 两个 Filter 生成的 UUID 可能不一致（虽然概率极低）。优化方式：把 traceId 生成逻辑抽到 `TraceContextFilter` 一个 Filter 里，其他 Filter 复用。

### Q11: gateway 调用下游 gRPC 出错了怎么办？熔断降级怎么做？

**考察点**：微服务容错 + Resilience4j CircuitBreaker + 降级策略。

**标准答案**：

**当前实现（容错未做）**：
```java
// PostServiceImpl：catch 异常后抛 GatewayException
public Long createPost(Long userId, CreatePostReq req) {
    try {
        return postClient.createPost(req.getContent(), req.getImageKeys()).getPostId();
    } catch (Exception e) {
        log.error("Failed to create post", e);
        throw new GatewayException(10901, "Failed to create post");
    }
}
```

**当前问题**：
- 直接抛 GatewayException(10901) → App 收到 `code=10901, message="Failed to create post"`
- 没有熔断 → 下游服务挂的时候 gateway 继续打，雪崩
- 没有降级 → 关键 RPC 失败整个接口失败

**生产改进（设计未落地）**：

```java
// Resilience4j CircuitBreaker：失败率超阈值 → 熔断
@CircuitBreaker(name = "post-service", fallbackMethod = "createPostFallback")
public Long createPost(Long userId, CreatePostReq req) {
    return postClient.createPost(req.getContent(), req.getImageKeys()).getPostId();
}

// 降级方法
private Long createPostFallback(Long userId, CreatePostReq req, Throwable t) {
    log.warn("PostService unavailable, fallback to local queue", t);
    // 可选：写到本地队列 → 下游恢复后补偿
    // 或直接返回 -1 + 提示用户重试
    throw new GatewayException(POST_SERVICE_UNAVAILABLE, "发帖服务暂不可用，请稍后重试");
}
```

**熔断策略**：
- **滑动窗口**：最近 100 个请求中失败率 > 50% → 熔断
- **熔断时长**：30 秒（默认）→ 半开状态放 1 个请求试探
- **试探成功** → 关闭熔断；失败 → 继续熔断

**追问**：
- **Q: 哪些接口应该熔断？哪些不该？**
  A: 按业务重要性分：(1) 写接口（发帖 / 发评论）→ 必须熔断，下游挂时不能让 App 一直失败；(2) 读接口（Feed / 推荐）→ 可以熔断+降级（返回兜底数据 + 提示）；(3) 鉴权接口 → 不能熔断，必须 fast fail（让用户明确知道登录失败）。

- **Q: 熔断阈值怎么定？**
  A: 没有标准答案，看业务容忍度。一般：(1) 失败率 50%（不能太敏感，否则正常抖动也熔断）；(2) 滑动窗口 100 个请求（样本量足够，避免小样本误判）；(3) 熔断时长 30 秒（不能太长，否则下游恢复后还要等很久）。

### Q12: gateway 怎么保护下游不被压垮？有没有限流、熔断之外的措施？

**考察点**：BFF 出口流量控制 + 背压 + 服务降级。

**标准答案**：

**BFF 是流量咽喉**，所有 App 请求都经过 gateway，保护下游有四个层级：

| 层级 | 措施 | 目的 |
|------|------|------|
| 入口 | Nginx IP 限流 / WAF | 挡地域性攻击、CC 攻击 |
| 入口 | Resilience4j 接口限流 | 挡 App 端 BUG 引起的请求风暴 |
| 出口 | gRPC 连接池限流 | 控制同时调用下游的并发数 |
| 出口 | gRPC 超时控制 | 避免慢请求拖垮 gateway 线程 |

**gRPC 连接池 / 并发控制**：

```java
// 当前实现：每次调用 newBlockingStub 都新建 channel（性能差且不控并发）
private UserServiceGrpc.UserServiceBlockingStub createStub() {
    ManagedChannel channel = ManagedChannelBuilder
            .forAddress(userServiceHost, userServicePort)
            .usePlaintext()
            .build();  // ← 每次都新建 channel
    return UserServiceGrpc.newBlockingStub(channel);
}
```

**改进（设计未落地）**：
```java
// 用 @GrpcClient 注入共享 channel，单例化
@GrpcClient("user-service")
private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

// Resilience4j Bulkhead（信号量隔离）：限制同时调用 user-service 的并发数
@Bulkhead(name = "user-service", type = BulkheadType.SEMAPHORE, maxConcurrentCalls = 100)
public UserProfileResponse getUserProfile(Long userId) {
    return userClient.getUserProfile(userId);
}
```

**gRPC 超时控制**：
```java
UserServiceBlockingStub stub = UserServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS);  // 500ms 超时
```

**追问**：
- **Q: 每次调用新建 channel 性能怎么样？**
  A: 不好。每个 channel 建 TCP 连接 + TLS 握手（即使明文也有握手开销），且 channel 关闭有资源释放。建议：(1) 服务启动时建好 channel 单例；(2) 用 `@GrpcClient` 注解 + Spring 注入；(3) 配合 Resilience4j Bulkhead 控并发。

- **Q: 熔断和限流的区别？**
  A: 限流是"主动控制流量"，保护自己（gateway）不被压垮；熔断是"被动断开故障链路"，保护自己（gateway）和下游（fail fast 让下游有时间恢复）。两者互补：限流挡正常流量，熔断挡故障流量。

---

## 场景问题

### 场景题 1：设计一个 BFF 网关

**考察点**：系统设计能力 + 微服务架构 + 鉴权设计。

**题目**：假设你要为某 dating app 设计 BFF 网关，要求支持：(1) 移动端 RN App + Web 端两个 BFF；(2) JWT 鉴权；(3) BFF 聚合首页（推荐 + 资料 + 在线）；(4) 限流；(5) 链路追踪。给出你的设计。

**回答框架**：

### 1. 需求澄清
- 流量规模：DAU 100 万 → peak QPS 5 万 → 单 BFF 实例抗 1 万 QPS
- 鉴权方式：JWT（access + refresh 双 token）
- 下游服务数：5 个（user/post/match/im/payment）
- 端：移动端 + Web 端，未来可能加第三方开放 API

### 2. 架构设计
```
App/Web ──► [Nginx TLS终结] ──► [BFF mobile-bff / web-bff]
                                          │
                                          │ 1. JWT 鉴权（filter）
                                          │ 2. 限流（Resilience4j）
                                          │ 3. traceId 注入 MDC
                                          │ 4. RequestContext 写 ThreadLocal
                                          │ 5. 业务聚合（CompletableFuture 并发）
                                          │ 6. 协议转换（REST ↔ gRPC + VO 裁剪）
                                          ▼
                                    下游 gRPC 服务（user/post/match/im/payment）
```

### 3. 关键决策
- **BFF 而非路由网关**：聚合场景多（首页 / 消息 / 匹配卡）
- **鉴权内聚在 BFF**：不独立 auth-service（当前阶段）
- **多 BFF 而非单 BFF**：mobile-bff / web-bff / open-api-bff 分别独立部署
- **双 token**：access 15min + refresh 7d
- **RS256**：私钥签发 / 公钥验签

### 4. 数据模型
- BFF 持久层仅鉴权域：`auth_device` / `auth_refresh_token`
- 业务数据全部走 gRPC 调下游

### 5. 权衡
- 选 BFF 内聚鉴权 → 网关挂了所有用户登不上 → 用 K8s 多副本 + 健康检查兜底
- 选 MVC + 虚拟线程 → 保留同步编程模型，BFF 聚合代码简洁
- 选 RS256 → 公私钥分离，下游安全验签

### 场景题 2：JWT 双 token 的安全审计

**考察点**：安全思维 + 漏洞挖掘能力。

**题目**：假设你是安全审计员，要对 mobile-gateway 做 JWT 鉴权安全审计。请列出你会检查的 10 个点。

**回答**：

1. **算法验证**：是否强制 RS256，是否禁用 `alg=none`（防止 JWT 算法降级攻击）
2. **签名验证**：是否每个请求都验签，公钥是否硬编码（防止攻击者替换公钥）
3. **过期时间**：access 是否强制过期（防永久 token），refresh 是否有过期
4. **黑名单**：登出 / 风控触发是否写黑名单 + 撤销 refresh
5. **refresh 轮换**：是否每次刷新换新 refresh，旧 refresh 标 used_at
6. **重放检测**：旧 refresh 第二次用是否触发撤销全部 refresh
7. **设备绑定**：refresh 的 deviceId 是否匹配当前请求设备
8. **HTTPS 强制**：是否禁用 HTTP，明文传输 JWT 等于裸奔
9. **密钥管理**：私钥是否从 Nacos 注入且不落盘，公钥是否经过审核
10. **日志脱敏**：日志是否不打印 token 明文（防止日志泄漏）
11. **限流**：登录 / 短信接口是否有限流（防撞库 / 短信轰炸）
12. **CORS**：是否限制允许的 origin（防 CSRF）

### 场景题 3：BFF 聚合首页的并发实现

**考察点**：并发编程 + CompletableFuture + 线程池隔离。

**题目**：BFF 聚合首页需要并发调 4 个下游 RPC（match / user / im / relation），单接口总超时 800ms。请写出实现代码。

**回答**：

```java
@Service
public class HomeServiceImpl implements HomeService {
    private final MatchClient matchClient;
    private final UserClient userClient;
    private final ImClient imClient;
    private final UserClient relationClient;  // 复用 userClient 或独立
    private final ExecutorService bffExecutor;  // BFF 专用线程池

    public HomeServiceImpl(MatchClient matchClient, UserClient userClient, ImClient imClient) {
        this.matchClient = matchClient;
        this.userClient = userClient;
        this.imClient = imClient;
        // 虚拟线程池（JDK 21），不复用 Tomcat 线程
        this.bffExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public List<HomeCardVO> getHomeCards(Long userId, int pageSize) {
        // 1. 先并发调 match + im（不依赖 userId 列表）
        var matchFuture = CompletableFuture
                .supplyAsync(() -> matchClient.getRecommendations(userId, pageSize), bffExecutor)
                .orTimeout(500, TimeUnit.MILLISECONDS)  // 单 RPC 500ms 超时
                .exceptionally(ex -> { log.warn("match fail", ex); return List.of(); });

        var imFuture = CompletableFuture
                .supplyAsync(() -> imClient.listOnlineUsers(), bffExecutor)
                .orTimeout(500, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> { log.warn("im fail", ex); return List.of(); });

        // 2. 等 match 完成后才能调 user（需要 userId 列表）
        var homeCardsFuture = matchFuture.thenComposeAsync(recs -> {
            List<Long> userIds = recs.stream().map(RecommendedUser::getUserId).toList();
            if (userIds.isEmpty()) {
                return CompletableFuture.completedFuture(List.<HomeCardVO>of());
            }
            return CompletableFuture
                    .supplyAsync(() -> userClient.batchGetUserProfiles(userIds), bffExecutor)
                    .orTimeout(500, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> { log.warn("user fail", ex); return List.of(); })
                    .thenApply(profiles -> {
                        // 拼装 VO
                        return recs.stream().map(rec -> {
                            HomeCardVO vo = new HomeCardVO();
                            vo.setTargetUserId(rec.getUserId());
                            vo.setNickname(rec.getNickname());
                            // ... 其他字段从 profiles 里匹配
                            return vo;
                        }).toList();
                    });
        }, bffExecutor);

        // 3. 等全部完成（总超时 800ms）
        try {
            return CompletableFuture.allOf(matchFuture, imFuture, homeCardsFuture)
                    .get(800, TimeUnit.MILLISECONDS)  // 总超时 800ms
                    .thenApply(v -> homeCardsFuture.join())
                    .get();
        } catch (TimeoutException e) {
            log.warn("Home aggregation timeout");
            throw new GatewayException(HOME_CARDS_FAILED, "首页加载超时，请重试");
        }
    }
}
```

**关键点**：
- 虚拟线程池隔离：JVM 自动调度，不耗 Tomcat 线程
- 单 RPC 500ms 超时：单个下游失败不影响整体
- 总超时 800ms：用户体验底线
- 可降级 RPC 失败 → WARN 日志 + 兜底默认值
- 关键 RPC（match）失败 → 抛异常

---

## 常见坑及回答

### 坑 1：ThreadLocal 在异步线程失效

**场景**：在 controller 里 `RequestContext.current().getUserId()` 拿到 userId，然后调 `CompletableFuture.supplyAsync(() -> service.doSomething(userId), bffExecutor)`，service 内部 `RequestContext.current().getUserId()` 返回 null。

**原因**：`CompletableFuture` 切换到 `bffExecutor` 线程 → 原 ThreadLocal 数据丢失。

**解决**：
1. **方案 A（推荐）**：把 userId 作为方法参数传入 service（不依赖 ThreadLocal 跨线程）
2. **方案 B**：用 `TransmittableThreadLocal`（Alibaba 开源）配合线程池装饰器，自动捕获/恢复 ThreadLocal 值
3. **方案 C**：用 Reactor Context / Kotlin coroutines 替代 ThreadLocal

**回答话术**："我们 mobile-gateway 当前 BFF 聚合是同步串行调用（已知 gap），未来落地 CompletableFuture 时会注意这个问题。当前我们用方案 A（userId 显式传参），避免 ThreadLocal 跨线程失效。"

### 坑 2：JWT 验签在过滤器里重复执行

**场景**：JwtAuthFilter 验签后写 RequestContext，controller 又调一次 `jwtVerifier.verifyAccessToken(token)` 双重验签。

**原因**：controller 写法不一致，部分 controller 忘了 RequestContext 已经存了 userId 又从 header 重新验签。

**解决**：
1. **统一约定**：controller 只能从 `RequestContext.current()` 拿 userId，不能直接动 token
2. **代码 review**：禁用 controller 里出现 `Authorization Header` 相关代码
3. **封装 BaseController**：提供 `protected Long currentUserId()` 方法，统一从 ThreadLocal 拿

**回答话术**："我们约定 controller 只通过 `RequestContext.current().getUserId()` 拿 userId，禁止在 controller 里手动解析 Authorization header。代码 review 时会重点检查。"

### 坑 3：gRPC Channel 没关导致连接泄漏

**场景**：`UserClient` 每次 `createStub()` 都 `ManagedChannelBuilder.build()`，但 channel 没 close，导致连接池泄漏，最终 OOM。

**原因**：当前实现确实每次新建 channel（gap），是生产事故隐患。

**解决**：
1. **用 `@GrpcClient` 注解 + Spring 管理 channel 生命周期**
2. **自定义 channel bean**：单例化 channel，gateway 关闭时统一 shutdown
3. **资源监控**：监控 channel 数 / 连接数，超过阈值告警

**回答话术**："当前实现每次调用 newBlockingStub 是有问题的（已知 gap），生产部署前必须改用 `@GrpcClient` 注入共享 channel，并配置 Spring `@PreDestroy` 钩子统一 shutdown。这是 high priority 的技术债。"

### 坑 4：限流阈值太严误杀正常用户

**场景**：auth 接口限流 5 req/60s，正常用户登录（sms send + login phone + 几个查询）就触发限流。

**原因**：限流阈值拍脑袋定的，没考虑真实业务调用链。

**解决**：
1. **埋点统计**：上线前用 shadow mode 统计真实 QPS
2. **分级限流**：auth 接口细分（sms send / login / refresh 各算一组），不要全压在一起
3. **白名单**：测试账号 / 内部账号不限流
4. **阈值可调**：放 Nacos 动态调，不要 hard code

**回答话术**："我们 auth 限流现在是 20 req/60s，参考线上统计后定的。但生产环境一定要配合 Nacos 动态配置，不能写死在代码里。"

### 坑 5：限流漏配置 → 用户刷爆下游

**场景**：controller 方法漏加 `@RateLimiter` 注解，下游服务被刷爆。

**原因**：限流靠注解，容易漏。

**解决**：
1. **AOP 兜底**：用 AOP 切所有 controller，自动按 URI 模式匹配限流
2. **全局兜底限流**：Resilience4j 配置 default RateLimiter，所有 controller 都过一遍
3. **nginx 层兜底**：即使 gateway 漏配，Nginx limit_req 还能挡一层

**回答话术**："我们靠注解 + Nacos 动态配置，潜在漏配风险。生产前会加 AOP 兜底，或者 nginx 层 limit_req 兜底，避免 gateway 单点失效。"

---

## 一句话总结

> mobile-gateway 是 dating app 后端的 **REST → gRPC BFF 网关**。技术上五件事：(1) JWT RS256 + access/refresh 双 token + Redis 黑名单 + 哈希存 PG 构成鉴权闭环；(2) ThreadLocal RequestContext + gRPC Metadata 透传让下游业务服务无感鉴权；(3) BFF 聚合用 CompletableFuture 并发调下游 + 字段裁剪 + 协议转换；(4) Resilience4j 接口级 + Redis 用户级 + Nginx IP 级三层限流；(5) traceId 经 filter + 拦截器 + MDC 透传到下游 + 日志。架构上严守 CLAUDE.md 红线 #2（gateway 持久层仅鉴权域 2 张表，业务数据一律 gRPC 调下游），红线 #3（服务间只走 gRPC，gateway 自身才暴露 REST），红线 #7（不维护 WS，IM 长连由 App 直连 OpenIM）。