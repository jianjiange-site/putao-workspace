# mobile-gateway 知识点整理

> 配套：[`README.md`](./README.md)、[`prd.md`](./prd.md)、[`interview-qa.md`](./interview-qa.md)
>
> 本文按"场景 → 问题 → 方案 → 权衡 → 实现"结构组织。每个知识点都给出 mobile-gateway 真实代码引用 + 为什么这么设计的解释。

---

## 知识点一：BFF 模式 vs 纯路由网关

### 背景/场景

dating app 移动端需要调用 5 个下游服务（user / post / match / im / payment）的接口。如果用 Spring Cloud Gateway / Kong 这类**纯路由网关**，网关只做"按 URL 前缀转发 + 负载均衡"，那么移动端首页这种聚合场景就要 App 自己串行/并发调多个 REST 接口：
1. App → match-service `/recommend?count=10` 拿 10 个 userId
2. App → user-service `/profile/batch?ids=...` 拿 10 个用户资料
3. App → im-service `/online?ids=...` 拿在线状态
4. App → user-service `/relation?ids=...` 拿关注关系

→ 4 次 HTTP 往返 + App 端聚合逻辑 + 字段裁剪散落各处。

### 问题分析

**纯路由网关的局限**：
- 网关只路由，业务聚合逻辑写在 App → 升级聚合逻辑要 App 发版
- 多服务接口风格不一致 → App 要适配 4 套不同的字段命名（userId vs id vs target_user_id）
- 字段裁剪（隐藏手机号/邮箱等敏感字段）在每个下游服务做一遍 → 漏改一个就泄漏

**BFF 网关的解法**（Backend for Frontend）：
- 网关一处收口：协议转换（REST → gRPC）+ 字段裁剪（proto → VO）+ 业务聚合（多服务并发 + 拼装）
- 下游服务保持纯净（gRPC interface，proto message），完全不感知 HTTP / 移动端
- 聚合逻辑升级只改网关 → 不发版 App

### 解决方案

mobile-gateway 是 BFF 网关，不是路由网关：

```java
// HomeServiceImpl：BFF 聚合典型实现
@Service
public class HomeServiceImpl implements HomeService {
    private final MatchClient matchClient;     // → match-service
    private final UserClient userClient;       // → user-service

    public List<HomeCardVO> getHomeCards(Long userId, int pageSize) {
        var recommendations = matchClient.getRecommendations(userId, pageSize);
        // 拿推荐 → 批量拿资料 → 拼装 VO
        List<Long> userIds = recommendations.stream().map(r -> r.getUserId()).toList();
        var userProfiles = userClient.batchGetUserProfiles(userIds);
        return recommendations.stream().map(rec -> {
            HomeCardVO vo = new HomeCardVO();
            vo.setTargetUserId(rec.getUserId());
            vo.setNickname(rec.getNickname());
            // ...字段裁剪 + 拼装
            return vo;
        }).toList();
    }
}
```

### 实现细节

- **目录分层**：`controller → service → (manager | client) → (mapper | gRPC stub)`，严格单向
- **client 包装**：每个下游服务一个 `XxxClient` 类（如 `UserClient`），业务代码不直接接触 gRPC stub
- **转换层**：当前实现 proto → VO 在 service 里直接 `.builder()` 拼装（设计文档提到未来用 MapStruct）
- **BFF 并发**：设计文档提到 `CompletableFuture` + `bffExecutor` 专用线程池（当前 `HomeServiceImpl` 是同步串行调用，是已知 gap）

### 权衡取舍

| 维度 | BFF 网关（本方案） | 纯路由网关 |
|------|------------------|-----------|
| 聚合场景 | 网关内并发调 → 总耗时 = max | App 串行调 → 总耗时 = sum |
| 字段裁剪 | 网关一处收口 | 下游每个服务都要做 |
| 下游耦合 | 下游只暴露 gRPC，无 HTTP 字段风格 | 下游可任意定义 REST 风格 |
| 网关膨胀风险 | 高（业务聚合都堆在网关） | 低（只路由） |
| 团队人力 | 需要写聚合逻辑 | 写完路由规则即可 |

→ dating app 选择 BFF，理由：(1) 移动端聚合场景多（首页、消息列表、匹配卡），(2) 团队有网关代码维护能力，(3) 网关膨胀风险通过"按业务垂直拆分 BFF（mobile-bff / web-bff / open-api-bff）"控制。

### 关联知识

- 设计文档 `doc/specs/mobile-gateway-design.md` §2 架构图、§5.3 BFF 聚合
- 知识点四：BFF 聚合的并发实现（`CompletableFuture` + 线程池隔离）

---

## 知识点二：JWT RS256 非对称加密 + 公私钥分离

### 背景/场景

JWT 签名需要"用什么算法 + 怎么管理密钥"。常见方案：
- **HS256（对称）**：签发/验签用同一密钥 → 多服务要共享密钥才能验签 → 密钥泄漏面大
- **RS256（非对称）**：私钥签发，公钥验签 → 验签方不持有私钥

### 问题分析

mobile-gateway 是唯一签发方，但下游业务服务（user / post / match）需要验签 token（如未来下游做权限校验时）。如果用 HS256：
- 下游 4 个服务都要持有对称密钥 → 密钥泄漏点 × 4
- 密钥轮换要 4 个服务同步改 → 运维复杂度

### 解决方案

**RS256 非对称**：
- 私钥（PEM 格式 base64）从 Nacos Config 注入到 gateway 内存，**仅 gateway 持有**，用于签发
- 公钥（PEM 格式 base64）从 Nacos Config 注入到 gateway 内存，公钥可全量下发到下游，用于验签
- 密钥轮换时 Nacos 推送 → 应用监听 → 原子替换（设计文档 §5.1）

```java
// JwtConfig：RSA 密钥对加载
@Configuration
public class JwtConfig {
    @Bean
    public KeyPair jwtKeyPair(JwtProperties properties) {
        if (properties.getPrivateKeyBase64() != null) {
            // 从 Nacos Config 注入的 base64 私钥加载
            byte[] privateKeyBytes = Base64.getDecoder().decode(properties.getPrivateKeyBase64());
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
            // 公钥加载（公钥可全量下发）
            byte[] publicKeyBytes = Base64.getDecoder().decode(properties.getPublicKeyBase64());
            X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
            return new KeyPair(publicKey, privateKey);
        }
        // 未配置公私钥 → 本地生成（仅 dev 用）
        return generateKeyPair();  // KeyPairGenerator.initialize(2048)
    }
}
```

```java
// JwtIssuer：用私钥签发
public TokenPair issueTokens(Long userId, String deviceId) {
    String accessToken = Jwts.builder()
            .subject(userId.toString())
            .id(accessJti)
            .issuer(jwtProperties.getIssuer())  // "dating-app"
            .issuedAt(Date.from(now))
            .expiration(Date.from(accessExpiry))  // now + 15min
            .claims(accessClaims)
            .signWith(keyPair.getPrivate())  // ← 私钥签发
            .compact();
    // refresh token 同理
}

// JwtVerifier：用公钥验签
private Claims parseToken(String token) {
    return Jwts.parser()
            .verifyWith(keyPair.getPublic())  // ← 公钥验签
            .build()
            .parseSignedClaims(token)
            .getPayload();
}
```

### 实现细节

- **密钥长度**：RSA 2048 位（行业标准，安全性 + 性能平衡）
- **公私钥格式**：PKCS#8（私钥）/ X.509（公钥），base64 编码后存入 Nacos
- **本地生成 fallback**：如果 Nacos 未配置公私钥 → 自动生成 2048 位 RSA 密钥对（仅 dev 用，prod 必须配置）
- **密钥不落盘**：私钥从 Nacos 拉到内存后不写文件 → 容器销毁即丢失，避免容器镜像泄漏密钥

### 权衡取舍

| 方案 | 优点 | 缺点 | 选择理由 |
|------|------|------|---------|
| HS256 对称 | 简单、签发快 | 密钥要全量同步、泄漏面大 | ❌ 下游服务多，密钥管理复杂 |
| RS256 非对称 | 公钥可下发、密钥泄漏面小 | 签发/验签稍慢 | ✅ 符合多服务架构 |
| EdDSA（Ed25519） | 更快更安全、签名小 | jjwt 0.12.x 支持有限、生态不够成熟 | ❌ 暂未广泛支持 |

### 关联知识

- 知识点三：双 token 设计（access + refresh）
- 知识点八：refresh token 轮换与重放检测
- 设计文档 §5.1 密钥与黑名单

---

## 知识点三：JWT Access + Refresh 双 Token 设计

### 背景/场景

JWT 一旦签发就不可撤销（设计上 stateless）。如果只用单一 access token：
- token 过期时间设短（如 5min）→ 用户频繁登录
- token 过期时间设长（如 30 天）→ 泄漏后攻击窗口大

### 问题分析

**单一 token 的两难**：
- 短过期 → 用户每 5 分钟就要重新登录，UX 差
- 长过期 → token 泄漏后 30 天都能用，安全差

### 解决方案

**双 token 组合**：

| Token | 类型 | 有效期 | 用途 | 存哪 |
|-------|------|--------|------|------|
| **Access Token** | JWT（RS256） | 15 分钟 | 携带在 Authorization Header，业务接口鉴权 | App 内存 |
| **Refresh Token** | Opaque 字符串 | 7 天 | 仅用于换新 access token，不能直接访问业务接口 | App Keychain + PG hash |

**核心思想**：
- Access 短过期 → 泄漏窗口小（最多 15 分钟）
- Refresh 长过期 → 但不可直接访问业务接口，泄漏后也只能续命（且可被撤销）
- Refresh 单次使用 + 轮换 → 重放攻击可被检测

```java
// JwtIssuer.issueTokens
public TokenPair issueTokens(Long userId, String deviceId) {
    String accessJti = UUID.randomUUID().toString();
    String refreshJti = UUID.randomUUID().toString();

    // access token claims
    Map<String, Object> accessClaims = new HashMap<>();
    accessClaims.put(TYPE_CLAIM, ACCESS_TYPE);     // "type"="access"
    accessClaims.put(DEVICE_ID_CLAIM, deviceId);   // "device_id"=xxx

    Instant accessExpiry = now.plus(jwtProperties.getAccessTokenExpirySeconds(), ChronoUnit.SECONDS);  // now + 15min
    String accessToken = Jwts.builder()
            .subject(userId.toString())
            .id(accessJti)
            .issuer(jwtProperties.getIssuer())
            .issuedAt(Date.from(now))
            .expiration(Date.from(accessExpiry))
            .claims(accessClaims)
            .signWith(keyPair.getPrivate())
            .compact();

    // refresh token claims（多带一个 access_jti 用于关联）
    Map<String, Object> refreshClaims = new HashMap<>();
    refreshClaims.put(TYPE_CLAIM, REFRESH_TYPE);   // "type"="refresh"
    refreshClaims.put(DEVICE_ID_CLAIM, deviceId);
    refreshClaims.put("access_jti", accessJti);

    Instant refreshExpiry = now.plus(jwtProperties.getRefreshTokenExpiryDays(), ChronoUnit.DAYS);  // now + 7d
    String refreshToken = Jwts.builder()...signWith(keyPair.getPrivate()).compact();

    return new TokenPair(accessToken, refreshToken, accessJti, refreshJti);
}
```

### 实现细节

- **Access Token claims**：`sub`=userId, `jti`=access UUID, `type`="access", `device_id`=xxx, `exp`=now+15min
- **Refresh Token claims**：`sub`=userId, `jti`=refresh UUID, `type`="refresh", `device_id`=xxx, `access_jti`=关联 access, `exp`=now+7d
- **type 区分**：JwtVerifier 验签时检查 `type=access`，refresh token 不能直接访问业务接口（即使泄漏也只能走 `/auth/refresh`）
- **Opaque refresh**：refresh token 用 `UUID.randomUUID()` 而非 userId 等可猜测字段 → 不可预测

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 单 token 长过期 | 简单 | 泄漏风险大 |
| 单 token 短过期 | 安全 | 用户频繁登录 UX 差 |
| **双 token（本方案）** | 兼顾安全 + UX | 实现复杂、要管理 refresh 表 |

→ 选双 token：access 15min 足够短（泄漏窗口小），refresh 7d 足够长（用户不感知登录）。

### 关联知识

- 知识点八：refresh token 轮换 + 重放检测
- 知识点九：Redis 黑名单（主动撤销 access）
- 设计文档 §5.1 鉴权流

---

## 知识点四：ThreadLocal RequestContext + gRPC Metadata 透传

### 背景/场景

下游业务服务（user / post / match）的 gRPC 接口需要知道"当前请求是哪个 user 在操作"。传统做法：
- 每个 gRPC 方法加 `Long userId` 参数 → 污染业务代码（每个方法都多一个参数）
- 每个方法都从 token 解 userId → 重复劳动，且下游要持有 token

### 问题分析

**业务代码被鉴权参数污染**：
```java
// 传统做法：每个 service 方法都加 userId
public PostDetailVO getPostDetail(Long userId, Long postId);  // userId 业务无关
public CommentListVO listComments(Long userId, Long postId, int pageSize);
// 假设有 50 个接口 → 写 50 次 userId，污染业务方法签名
```

**下游持有 token 的安全风险**：
- token 一旦落到下游 4 个服务，泄漏面 × 4
- 下游要重复实现 JWT 验签 + 黑名单查询 → 重复造轮子

### 解决方案

**ThreadLocal + 拦截器双层机制**：

```java
// RequestContext：ThreadLocal 持有请求作用域数据
public class RequestContext {
    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();
    private final Long userId;
    private final String deviceId;
    private final String traceId;
    private final String accessJti;

    public static void set(Long userId, String deviceId, String traceId, String accessJti) {
        CONTEXT.set(new RequestContext(userId, deviceId, traceId, accessJti));
    }
    public static RequestContext current() {
        RequestContext ctx = CONTEXT.get();
        return ctx != null ? ctx : new RequestContext(null, null, null, null);
    }
    public static void clear() { CONTEXT.remove(); }
}
```

```java
// JwtAuthFilter：写入 RequestContext
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request, ...) {
        // 解析 token → 写 RequestContext
        var claims = jwtVerifier.verifyAccessToken(token).get();
        RequestContext.set(claims.userId(), claims.deviceId(), traceId, claims.jti());
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();  // 必须清理，避免线程复用污染
        }
    }
}
```

```java
// GrpcClientMetadataInterceptor：透传到下游 gRPC
@Component
public class GrpcClientMetadataInterceptor implements ClientInterceptor {
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        Metadata metadata = new Metadata();
        RequestContext ctx = RequestContext.current();
        if (ctx.getUserId() != null) {
            metadata.put(Metadata.Key.of("user_id", Metadata.ASCII_STRING_MARSHALLER),
                    ctx.getUserId().toString());
        }
        if (ctx.getDeviceId() != null) {
            metadata.put(Metadata.Key.of("device_id", Metadata.ASCII_STRING_MARSHALLER), ctx.getDeviceId());
        }
        if (ctx.getTraceId() != null) {
            metadata.put(Metadata.Key.of("trace_id", Metadata.ASCII_STRING_MARSHALLER), ctx.getTraceId());
        }
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.merge(metadata);
                super.start(responseListener, headers);
            }
        };
    }
}
```

业务代码调用就变成：
```java
// controller 一行拿 userId，service 不传 userId 参数
@PostMapping("/api/v1/posts")
public Result<Map<String, Long>> createPost(@Valid @RequestBody CreatePostReq req) {
    Long userId = RequestContext.current().getUserId();  // ← ThreadLocal
    Long postId = postService.createPost(req);  // 不传 userId，service 内部从 ThreadLocal 拿
    return Result.ok(Map.of("postId", postId));
}
```

### 实现细节

- **Metadata key**：约定为 `x-user-id` / `x-device-id` / `x-trace-id`（设计文档 §5.7）
- **下游协议**：下游业务服务从 gRPC Metadata `x-user-id` 取 userId，不持有 token
- **线程复用清理**：`RequestContext.clear()` 在 `finally` 中调用，避免 Tomcat 线程复用导致污染下一个请求
- **MDC 注入**：TraceIdFilter 同时把 traceId 写 SLF4J MDC → logback pattern 自动 `%X{traceId}` 输出

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 方法参数传 userId | 显式、易理解 | 污染业务方法、每个方法都加参数 |
| ThreadLocal + Metadata | 业务代码干净、自动透传 | 隐式、调试时看不到来源 |
| AOP 切面注入 | 业务代码最干净 | 实现复杂、debug 栈断点难定位 |

→ 选 ThreadLocal + Metadata：实现简单，业务代码侵入最小，且 Tomcat 线程复用通过 `clear()` 兜底。

### 关联知识

- 知识点五：Filter 链执行顺序（@Order(1) JwtAuthFilter + @Order(2) TraceIdFilter）
- 知识点六：BFF 聚合 + 线程池隔离

---

## 知识点五：Servlet Filter 链顺序与白名单设计

### 背景/场景

gateway 入口有多个 Filter 需要按顺序执行：
1. JWT 鉴权（必须最先，未登录直接 401）
2. TraceId 注入 MDC（让后续所有 log 都有 traceId）
3. CORS（如果放在鉴权前，跨域预检 OPTIONS 也要先放行）
4. Resilience4j 限流（在业务执行前）
5. 全局异常兜底（最后兜底）

如果顺序乱了，可能出现：
- 未鉴权就执行了业务 → 安全漏洞
- traceId 注入在 log 输出之后 → 看不到 traceId
- CORS preflight 被鉴权拦截 → 跨域失败

### 问题分析

**Filter 顺序敏感性**：
- Spring `@Order` 数字越小优先级越高
- 但 CORS preflight `OPTIONS` 请求可能不带 Authorization header → 被 JwtAuthFilter 拦截 → 跨域失败

### 解决方案

```java
// JwtAuthFilter：@Order(1) 最先执行
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/sms/send",         // 登录前
            "/api/v1/auth/refresh",          // refresh 登录前
            "/api/v1/auth/login/phone",      // 登录前
            "/api/v1/auth/login/device",     // 登录前
            "/api/v1/auth/login/third-party",// 登录前
            "/api/v1/health",                // 健康检查
            "/swagger-ui", "/v3/api-docs",   // API 文档
            "/actuator"                      // 监控
    );

    protected void doFilterInternal(HttpServletRequest request, ...) {
        // 1. 拿/生成 traceId
        String traceId = request.getHeader("X-Trace-ID");
        if (traceId == null || traceId.isEmpty()) traceId = UUID.randomUUID().toString();

        // 2. 公开路径 → 跳过鉴权
        String path = request.getRequestURI();
        if (isPublicPath(path)) {
            RequestContext.set(null, null, traceId, null);
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 解析 Authorization Header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorizedResponse(response, 401, "Missing or invalid Authorization header");
            return;
        }

        // 4. 验签
        String token = authHeader.substring("Bearer ".length());
        var claimsOpt = jwtVerifier.verifyAccessToken(token);
        if (claimsOpt.isEmpty()) {
            sendUnauthorizedResponse(response, 401, "Invalid or expired token");
            return;
        }

        // 5. 写 RequestContext + 透传 traceId 到响应头
        var claims = claimsOpt.get();
        RequestContext.set(claims.userId(), claims.deviceId(), traceId, claims.jti());
        response.setHeader("X-Trace-ID", traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();  // 必须清理
        }
    }
}

// TraceIdFilter：@Order(2) 在 JwtAuthFilter 之后
@Component
@Order(2)
public class TraceIdFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String traceId = request.getHeader("X-Trace-ID");
        if (traceId == null || traceId.isEmpty()) traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);  // 注入 SLF4J MDC → logback 自动输出
        response.setHeader("X-Trace-ID", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

### 实现细节

- **白名单 set**：`PUBLIC_PATHS` 硬编码（生产可放 Nacos），用 `Set.of` 避免重复
- **白名单匹配**：用 `path::startsWith` 前缀匹配（覆盖 `/swagger-ui/index.html` 等子路径）
- **错误响应格式**：鉴权失败也走 `Result<T>` 包装格式（code=401, message="xxx", data=null），HTTP status 200
- **traceId 响应头**：把 traceId 写回 `X-Trace-ID` 响应头，App 可拿到用于客服反馈 / 问题排查
- **MDC + ThreadLocal 双写**：JwtAuthFilter 写 ThreadLocal，TraceIdFilter 写 MDC，两者都写是因为下游 gRPC 走 ThreadLocal，本进程 log 走 MDC

### 权衡取舍

| 设计选择 | 优点 | 缺点 |
|---------|------|------|
| 白名单硬编码 | 简单、启动期可见 | 改路径要改代码 |
| 白名单放 Nacos | 动态生效 | 增加 Nacos 依赖 |
| 鉴权失败返 401 | 语义清晰 | App 要处理 status code |
| **鉴权失败返 200 + 业务码** | App 只看 body 一个分支 | HTTP 状态码语义弱化 |

→ 选**白名单硬编码 + 鉴权失败返 200 + 业务码**：(1) gateway 入口路径稳定，不需要动态改；(2) 与 gateway-design.md §5.6 错误约定一致（HTTP 200 + 业务码）；(3) App 端处理逻辑统一。

### 关联知识

- 知识点四：ThreadLocal RequestContext
- 知识点十：业务码段位化（鉴权失败 code=`UNAUTHORIZED=40100`）

---

## 知识点六：BFF 聚合 + CompletableFuture 并发执行

### 背景/场景

移动端首页需要展示"推荐用户卡片"，单次页面渲染需要 4 个下游数据：
1. match-service `getRecommendations` 拿 10 个推荐 userId
2. user-service `batchGetUserProfiles` 拿 10 个用户的 bio / 资料
3. im-service `ListOnlineUsers` 拿 10 个用户的在线状态
4. user-service `getRelation` 拿当前用户对 10 个目标的关注关系

如果串行调用，总耗时 = t1 + t2 + t3 + t4。

### 问题分析

**串行调用的延迟问题**：
- 每个 gRPC 平均 50ms，串行 4 个 = 200ms
- 移动端首页滑一屏就要等 200ms → 卡顿感

### 解决方案

**CompletableFuture + 专用 BFF 线程池**：

```java
// 设计文档 §5.3 样例代码（待落地）
@Service
public class HomeService {
    private final UserInfoClient userInfoClient;
    private final RelationClient relationClient;
    private final ImClient imClient;
    private final Executor bffExecutor;  // BFF 专用线程池，与 Tomcat 隔离

    public HomeCardVO getHomeCard(Long viewerId, Long targetId) {
        // 三个 RPC 并发执行
        var p = CompletableFuture.supplyAsync(
                () -> userInfoClient.getProfile(targetId), bffExecutor);
        var r = CompletableFuture.supplyAsync(
                () -> relationClient.getRelation(viewerId, targetId), bffExecutor);
        var o = CompletableFuture.supplyAsync(
                () -> imClient.getOnline(targetId), bffExecutor);
        CompletableFuture.allOf(p, r, o).join();  // 阻塞等全部完成
        return HomeCardConverter.assemble(p.join(), r.join(), o.join());
    }
}
```

**总耗时 = max(t1, t2, t3)**（理想情况 ≈ 50ms），而不是 sum。

### 实现细节

- **线程池隔离**：`bffExecutor` 用 `Executors.newVirtualThreadPerTaskExecutor()`（JDK 21 虚拟线程）或固定大小线程池，**不复用 Tomcat 线程**
- **超时控制**：单接口总超时 800ms（Nacos 可配），单 RPC 子调用超时 500ms
- **降级策略**：
  - 关键 RPC 失败（match）→ 抛业务异常
  - 可降级 RPC 失败（user/im） → 兜底默认值 + WARN 日志
- **当前实现 gap**：`HomeServiceImpl.getHomeCards` 当前是同步串行调用，未用 CompletableFuture（已知 gap）

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 串行调用 | 简单 | 延迟高 |
| CompletableFuture 并发 | 延迟 = max | 实现复杂、线程池要隔离 |
| 响应式 WebFlux | 节省线程 | 调试栈断、生态成本 |
| **CompletableFuture + 虚拟线程**（JDK 21） | 简单 + 低延迟 | 需要 JDK 21 |

→ 选 CompletableFuture + 虚拟线程：JDK 21 已在用（CLAUDE.md 技术栈约束），虚拟线程栈 KB 级，可轻松承载万级并发，且代码仍是同步风格（`supplyAsync + join`），不写响应式链。

### 关联知识

- 知识点一：BFF 模式
- 设计文档 §5.3 BFF 聚合

---

## 知识点七：Refresh Token 哈希存 PG + Token 轮换

### 背景/场景

refresh token 是 7 天有效期的字符串，比 access token 价值高（能换新 access）。如果明文存 PG：
- 数据库泄漏 → 攻击者直接拿 refresh token → 7 天内可以无限续命
- 内部人员能看到 → 内部威胁

### 问题分析

**明文存储的威胁**：
- PG 备份泄漏 → 整库 token 泄漏
- DBA/开发误查 → 看到所有用户的 refresh token
- SQL 注入 → 直接读出 token

### 解决方案

**SHA-256 哈希存 PG + 单次使用轮换**：

```java
// AuthServiceImpl.createLoginResult：保存 refresh token 时哈希
private String hashToken(String token) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);  // 输出 hex 字符串
}

// 保存到 PG 时只存哈希
AuthRefreshTokenEntity entity = new AuthRefreshTokenEntity();
entity.setUserId(userId);
entity.setDeviceId(deviceId);
entity.setTokenHash(tokenHash);  // ← SHA-256 hex，不存明文
entity.setJti(tokens.refreshJti());
entity.setExpiresAt(jwtIssuer.getRefreshTokenExpiry());
authRefreshTokenManager.saveRefreshToken(entity);
```

```java
// AuthRefreshTokenManager.findValidByTokenHash：刷新时按哈希查
public Optional<AuthRefreshTokenEntity> findValidByTokenHash(String tokenHash) {
    return Optional.ofNullable(
            refreshTokenMapper.selectOne(
                    new LambdaQueryWrapper<AuthRefreshTokenEntity>()
                            .eq(AuthRefreshTokenEntity::getTokenHash, tokenHash)
                            .isNull(AuthRefreshTokenEntity::getUsedAt)    // 未用过
                            .isNull(AuthRefreshTokenEntity::getRevokedAt) // 未撤销
                            .eq(AuthRefreshTokenEntity::getDeleted, 0)
            )
    );
}
```

```sql
-- auth_refresh_token 表结构
CREATE TABLE auth_refresh_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,  -- SHA-256 hex
    jti VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,              -- 单次使用标志
    revoked_at TIMESTAMPTZ,          -- 主动撤销标志
    ...
);
CREATE INDEX idx_auth_refresh_token_hash ON auth_refresh_token(token_hash);  -- 哈希查
```

### 实现细节

- **SHA-256 单向**：PG 泄漏 → 攻击者拿到的是哈希，反推原 token 需要暴力破解（SHA-256 不可逆）
- **token_hash 索引**：`idx_auth_refresh_token_hash` 保证 refresh 时 O(log n) 查询
- **轮换机制**：每次刷新，旧 refresh 标 `used_at = now()`，新 refresh 写新行
- **重放检测**：旧 refresh 被用过 → 第二次用会触发"used_at != null"分支 → revoke 该用户全部 refresh

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 明文存 | 简单、能反查 | 泄漏即失陷 |
| 加密存（AES） | 可反查 | 密钥管理复杂 |
| **SHA-256 哈希（本方案）** | 不可逆、简单 | 不可反查（refresh 只能用哈希匹配） |
| Bcrypt 哈希 | 加盐抗彩虹表 | 慢（refresh 高频查询不友好） |

→ 选 SHA-256：refresh token 是高熵随机 UUID（128 bit 熵），不需要 Bcrypt 加盐；哈希不可逆 + 索引查询快。

### 关联知识

- 知识点三：双 token 设计
- 知识点八：重放攻击检测

---

## 知识点八：Refresh Token 轮换 + 重放攻击检测

### 背景/场景

refresh token 是 7 天长有效期，比 access token 价值高。攻击场景：
- 攻击者拿到 refresh token → 7 天内可以换新 access → 等于永久登录
- 即使 refresh 过期前被用过一次，再拿来用也能继续换（无轮换机制）

### 问题分析

**refresh token 滥用风险**：
- 单一 refresh 多次使用 → 无重放检测
- refresh 泄漏 → 7 天长期续命
- 用户在 A 设备登出 → B 设备的 refresh 仍有效

### 解决方案

**三件套防御**：
1. **单次使用 + 轮换**：每个 refresh 只能用一次，用完标 `used_at`
2. **重放检测**：旧 refresh 被用过 → 第二次用会触发 → 自动撤销该用户全部 refresh
3. **设备绑定**：refresh 的 deviceId 必须匹配当前请求的 deviceId

```java
// AuthServiceImpl.refreshToken（设计文档 §5.1 鉴权流）
public LoginResultVO refreshToken(RefreshTokenReq req) {
    String tokenHash = hashToken(req.getRefreshToken());

    // 1. 查哈希找记录
    var entityOpt = authRefreshTokenManager.findValidByTokenHash(tokenHash);
    if (entityOpt.isEmpty()) {
        throw new GatewayException(INVALID_REFRESH_TOKEN, "无效的 refresh token");
    }
    var entity = entityOpt.get();

    // 2. 校验：未用过 + 未撤销 + 未过期 + 设备匹配
    if (entity.getUsedAt() != null) {
        // 已用过 → 触发重放检测 → 撤销该用户全部 refresh
        authRefreshTokenManager.revokeAllForUser(entity.getUserId(), entity.getDeviceId());
        throw new GatewayException(REFRESH_TOKEN_REUSED, "refresh token 已失效，请重新登录");
    }
    if (entity.getRevokedAt() != null) {
        throw new GatewayException(TOKEN_REVOKED, "refresh token 已被撤销");
    }
    if (entity.getExpiresAt().isBefore(Instant.now())) {
        throw new GatewayException(TOKEN_EXPIRED, "refresh token 已过期");
    }

    // 3. 设备匹配（设计文档约定，代码层 TODO）
    // if (!entity.getDeviceId().equals(currentDeviceId)) {
    //     throw new GatewayException(REFRESH_TOKEN_DEVICE_MISMATCH, "设备不匹配");
    // }

    // 4. 旧 refresh 标 used
    authRefreshTokenManager.markUsed(entity);

    // 5. 签发新 access + refresh
    JwtIssuer.TokenPair newTokens = jwtIssuer.issueTokens(entity.getUserId(), entity.getDeviceId());
    String newTokenHash = hashToken(newTokens.refreshToken());
    AuthRefreshTokenEntity newEntity = new AuthRefreshTokenEntity();
    newEntity.setUserId(entity.getUserId());
    newEntity.setDeviceId(entity.getDeviceId());
    newEntity.setTokenHash(newTokenHash);
    newEntity.setJti(newTokens.refreshJti());
    newEntity.setExpiresAt(jwtIssuer.getRefreshTokenExpiry());
    authRefreshTokenManager.saveRefreshToken(newEntity);

    return toLoginResultVO(newTokens, entity.getUserId());
}
```

### 实现细节

- **`markUsed`**：UPDATE `auth_refresh_token SET used_at=NOW() WHERE id=?`
- **`revokeAllForUser`**：UPDATE `auth_refresh_token SET revoked_at=NOW() WHERE user_id=? AND device_id=?`
- **重放触发链**：攻击者用旧 refresh 第二次调 → `used_at != null` → 撤销该用户该 device 全部 refresh → 攻击者即使有再多旧 refresh 全部失效
- **设备匹配**（设计文档约定）：refresh 的 deviceId 必须等于 access token 解析出来的 deviceId，否则视为异常（待代码层补）

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 单次使用 + 轮换 | 重放可检测 | 用户要管理最新 refresh（App 自动覆盖无感） |
| 滑动续期 | refresh 永不过期 | 攻击者可以无限续命 |
| **轮换 + 重拉黑（本方案）** | 安全 | 实现复杂、refresh 行数累计 |

→ 选**轮换 + 拉黑**：用户每次刷新用最新 refresh（App 自动存），旧 refresh 失效也无所谓；攻击者即使拿到旧 refresh 也只能用一次。

### 关联知识

- 知识点七：哈希存 PG
- 知识点九：Redis 黑名单（access 主动撤销）

---

## 知识点九：Redis 黑名单 + Access Token 主动撤销

### 背景/场景

JWT 一旦签发就不可撤销（设计上 stateless）。但业务上需要"主动登出"和"风控踢人"：
- 用户在 A 设备点"登出" → 立即让 A 设备的 access token 失效
- 风控系统发现某用户被盗号 → 立即让该用户全部 access 失效

### 问题分析

**JWT 不可撤销的局限**：
- 设计上 stateless → 服务端不存状态
- 但登出 / 风控场景必须能立即作废 token

### 解决方案

**Redis 黑名单 + 短 TTL**：

```java
// JwtVerifier：验签时查黑名单
public Optional<JwtClaims> verifyAccessToken(String token) {
    Claims claims = parseToken(token);
    String type = claims.get("type", String.class);
    if (!"access".equals(type)) return Optional.empty();

    String jti = claims.getId();
    if (isBlacklisted(jti)) {
        log.debug("Token is blacklisted");
        return Optional.empty();  // 黑名单命中 → 视为无效
    }
    // ...
}

public boolean isBlacklisted(String jti) {
    return Boolean.TRUE.equals(redisTemplate.hasKey("gateway:auth:blacklist:" + jti));
}

public void blacklist(String jti, long ttlSeconds) {
    String key = "gateway:auth:blacklist:" + jti;
    redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
    // TTL 与 access token 剩余有效期一致（最多 15 分钟）
}
```

```java
// AuthServiceImpl.logout：登出时写黑名单
public void logout(String refreshToken) {
    String tokenHash = hashToken(refreshToken);
    var entity = authRefreshTokenManager.findValidByTokenHash(tokenHash)
            .orElseThrow(() -> new GatewayException(INVALID_REFRESH_TOKEN, "无效 token"));

    // 1. 撤销该用户该 device 全部 refresh token
    authRefreshTokenManager.revokeAllForUser(entity.getUserId(), entity.getDeviceId());

    // 2. 把当前 access token 的 jti 写黑名单（TTL = access 剩余有效期）
    // （从 RequestContext.current().getAccessJti() 拿当前 jti）
    String currentAccessJti = RequestContext.current().getAccessJti();
    long remainingTtl = 15 * 60;  // 简化为 access token 完整 TTL
    jwtVerifier.blacklist(currentAccessJti, remainingTtl);
}
```

### 实现细节

- **黑名单 key 格式**：`gateway:auth:blacklist:<jti>`，遵守 CLAUDE.md Redis 前缀规范（`putao:<service>:<domain>:<id>`，gateway 服务的 key 前缀约定为 `gateway:`，设计文档 §3 表格）
- **TTL = 剩余有效期**：access token 过期后自动从黑名单删除（Redis TTL 自动清理），不会无限堆积
- **验签顺序**：先验签（公钥验签），再查黑名单 → 黑名单命中视为无效

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 不撤销（纯 stateless） | 简单 | 无法主动登出 / 踢人 |
| 全量黑名单（PG 存） | 持久 | 每次验签查 PG，慢 |
| **Redis 黑名单 + 短 TTL（本方案）** | 快速、自动清理 | Redis 不可用时验签失败（fallback 到允许） |

→ 选 Redis 黑名单：每次验签 O(1) Redis 查询，TTL 自动清理，Redis 不可用时降级到允许（兜底）。

### 关联知识

- 知识点三：双 token 设计
- 知识点七：refresh token 哈希

---

## 知识点十：业务码段位化（看码即知归属）

### 背景/场景

5 个业务服务（user/post/match/im/payment）+ 1 个 BFF 网关（mobile-gateway），错误码不分区会出大问题：
- App 看到 `code=10001` 不知道是 user-service 还是 mobile-gateway 的
- 客服定位问题要查 5 个服务的日志

### 问题分析

**错误码混乱的代价**：
- 同一错误码被多个服务复用 → 排查时混淆
- App 端 if/else 分支无法按服务维度聚合
- 监控 / 告警无法按服务过滤

### 解决方案

**业务码段位化**（mobile-gateway-design.md §5.6）：

| 段位 | 归属服务 | 说明 |
|------|---------|------|
| `10001-10499` | user-service | 用户域 |
| `10500+` | mobile-gateway | BFF 网关 |
| `105xx` | gateway | Token 系列 |
| `106xx` | gateway | 短信 / 三方 |
| `109xx` | gateway | 上游不可用 |

```java
public final class ErrorCodes {
    // Auth（1000x）
    public static final int SMS_RATE_LIMITED = 10002;
    public static final int INVALID_SMS_CODE = 10003;
    public static final int INVALID_REFRESH_TOKEN = 10004;

    // Profile（101xx）
    public static final int PROFILE_NOT_FOUND = 10101;
    public static final int PROFILE_UPDATE_FAILED = 10102;
    public static final int PROFILE_GET_USERS_FAILED = 10103;

    // Match（103xx）
    public static final int MATCH_SWIPE_FAILED = 10301;
    public static final int MATCH_SUPER_HI_FAILED = 10302;
    public static final int MATCH_LIST_FAILED = 10303;
    public static final int MATCH_HISTORY_FAILED = 10304;

    // Home（104xx）
    public static final int HOME_CARDS_FAILED = 10401;

    // Post（109xx）— 复用 upstream unavailable 段
    public static final int POST_OPERATION_FAILED = 10901;

    // IM（106xx）
    public static final int IM_TOKEN_FAILED = 10601;
    public static final int CALL_TOKEN_FAILED = 10602;

    // 通用
    public static final int INTERNAL_ERROR = 50000;
    public static final int UNAUTHORIZED = 40100;
    public static final int FORBIDDEN = 40300;
}
```

```java
// GlobalExceptionHandler：兜底转 Result（注意当前未加 @RestControllerAdvice 注解，是 gap #9）
public class GlobalExceptionHandler {
    public Result<Void> handleGatewayException(GatewayException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }
    public Result<Void> handleException(Exception e) {
        return Result.fail(50000, "Internal server error: " + e.getMessage());
    }
}
```

### 实现细节

- **HTTP status 默认 200**：错误码在 body 的 `code` 字段，App 端只看 code 判断业务是否成功
- **401 / 429 / 500 例外**：鉴权失败 / 限流命中 / 内部错误用 HTTP status 表达（语义清晰）
- **错误码 → 用户文案映射**：App 端根据 code 映射到本地化提示文案（设计文档约定）
- **traceId 返回**：500 错误返回 traceId，App 上报客服便于排查

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| HTTP status 分错误 | 标准化 | App 要 switch status |
| 业务码分错误 | App 只看 body 一个分支 | HTTP 语义弱化 |
| **HTTP 200 + 业务码 + 例外 status**（本方案） | 兼顾 HTTP 语义和 App 简洁 | 实现要约定哪些用 status 哪些用 body |

→ 选 HTTP 200 + 业务码 + 例外 status：(1) App 端处理逻辑统一，(2) 鉴权/限流/500 用 status 语义清晰，(3) 业务错误码段位化便于排查。

### 关联知识

- 知识点五：Filter 链错误响应
- 设计文档 §5.6 错误约定

---

## 知识点十一：Resilience4j 三档限流（接口级 + 用户级 + IP 级）

### 背景/场景

mobile-gateway 是唯一 HTTPS 入口，最容易成为攻击目标：
- 短信验证码接口被刷 → 短信费用暴涨
- 登录接口被刷 → 撞库
- Feed 接口被刷 → 带宽 / DB 压力

### 问题分析

**单一限流的盲区**：
- 仅接口级（每个接口 1000 req/s）→ 单用户可以疯狂刷单一接口
- 仅用户级（每用户 100 req/s）→ 单 IP 多账号绕过
- 仅 IP 级（每 IP 100 req/s）→ 代理池绕过

### 解决方案

**三档组合防御**：

```java
// ResilienceRateLimiterConfig：Resilience4j 限流器
@Configuration
public class ResilienceRateLimiterConfig {

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }

    @Bean
    public RateLimiter globalRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("global");  // 全局粗粒度
    }

    @Bean
    public RateLimiter authRateLimiter(RateLimiterRegistry registry) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(60))  // 60 秒窗口
                .limitForPeriod(20)                          // 20 个许可
                .timeoutDuration(Duration.ofMillis(500))     // 等许可最多 500ms
                .build();
        return registry.rateLimiter("auth", config);  // auth 接口细粒度
    }
}
```

| 维度 | 实现 | 备注 |
|------|------|------|
| 接口级 | Resilience4j `@RateLimiter(name="auth")` | 配置在 Nacos 动态生效 |
| 用户级 | Redis + Lua 滑动窗口 | key `lock:gateway:rate:<userId>:<api>`（设计约定） |
| IP 级 | Nginx 层 | 不在 gateway 重复实现 |

### 实现细节

- **限流器命名**：`global` / `auth` / `payment` 等，按业务接口分组
- **超时不等待**：`timeoutDuration(500ms)` → 拿不到许可就快速失败，不阻塞请求
- **降级响应**：限流命中返回 `Result.fail(429, TOO_MANY_REQUESTS)`，HTTP status 429（设计约定）
- **当前实现**：配置类已建好，但 `@RateLimiter` 注解未加在 controller 上（已知 gap）

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| Redis 计数器 | 集群共享 | 每次请求 1 次 Redis |
| 令牌桶（Resilience4j） | 内存快、无外部依赖 | 单机限流 |
| **Redis + Resilience4j 双层** | 集群共享 + 内存快 | 实现复杂 |

→ 选 **Resilience4j 内存限流（接口级）+ Redis 滑动窗口（用户级）+ Nginx IP 限流**：三层防御，按粒度分工。

### 关联知识

- 设计文档 §5.4 限流
- 知识点十：业务码段位化（429 返回）

---

## 附录：gateway 持久层边界（红线 #2 实践）

gateway 持久层仅覆盖鉴权域：

| 表名 | 用途 | 字段数 | 索引 |
|------|------|--------|------|
| `auth_device` | 设备指纹 | 13 | `(user_id, device_id)` / `device_id` |
| `auth_refresh_token` | refresh token 生命周期 | 11 | `token_hash` / `user_id` / `jti` |

**严守 CLAUDE.md 红线 #2**（跨服务直连别人家的库表/Redis/对象桶）：
- ❌ gateway 不查 user_service.users 表（调 user-service gRPC）
- ❌ gateway 不查 post_service.posts 表（调 post-service gRPC）
- ❌ gateway 不读 match_service.recommendation 表（调 match-service gRPC）
- ❌ gateway 不读 business Redis key（仅自用 `gateway:*` 前缀）
- ❌ gateway 不直连 MinIO 业务 bucket（仅 avatar 走 presign + confirm）

**业务数据流转**：
```
mobile-gateway → gRPC UserClient.getUserProfile(userId) → user-service → PG users
              ↘ gRPC PostClient.getPostDetail(postId) → post-service → PG posts
              ↘ gRPC MatchClient.getRecommendations(...) → match-service → Redis ZSet + PG
              ↘ gRPC ImClient.getImToken(userId) → im-service → OpenIM API
```