# mobile-gateway 功能设计文档（类 PRD）

> 配套：[`README.md`](./README.md)、[`knowledge.md`](./knowledge.md)、[`interview-qa.md`](./interview-qa.md)
>
> mobile-gateway 是 dating app 后端的"REST → gRPC BFF 网关"，为移动端 RN App 提供唯一 HTTPS 入口，承担 JWT 鉴权、协议转换、BFF 聚合、限流、traceId 透传五件事。本 PRD 按 7 大模块梳理全部 REST 接口 + 数据流转 + 鉴权闭环 + 边界情况。

---

## 模块概述

### 业务定位

mobile-gateway 在 dating app 系统中的位置：

```
                    ┌──────────────┐  HTTPS / REST + JWT
    RN App ────────┤  Nginx (TLS) ├─────────────────────────┐
                    └──────────────┘                          │
                                                              ▼
                                  ┌─────────────────────────────────────┐
                                  │       mobile-gateway (本服务)        │
                                  │   ┌─────────────────────────────┐   │
                                  │   │ Controller (REST 入口)      │   │
                                  │   ├─────────────────────────────┤   │
                                  │   │ Service (业务编排)           │   │
                                  │   │  ├─ Manager (鉴权域持久化)   │   │
                                  │   │  └─ Client (gRPC stub)      │   │
                                  │   ├─────────────────────────────┤   │
                                  │   │ Filter: JWT / TraceId       │   │
                                  │   │ Interceptor: gRPC Metadata  │   │
                                  │   └─────────────────────────────┘   │
                                  └───┬─────────────────────────┬──────┘
                                      │                         │
                              PG (auth_device,         Redis (黑名单 / 限流)
                              auth_refresh_token)              │
                                                                   │ gRPC
                                                                   ▼
                                          ┌──────┬──────┬──────┬──────┐
                                          ▼      ▼      ▼      ▼      ▼
                                       user   post   match  im    payment
                                       -svc   -svc   -svc   -svc  -svc (stub)
```

### 核心价值

解决五件事：

1. **JWT 鉴权闭环**：access (RS256, 15min) + refresh (opaque, 7d) 双 token 设计，签发/验签/黑名单/refresh 轮换全部内聚。
2. **REST → gRPC 协议转换**：移动端 REST 入参 → proto request → 下游 gRPC → proto response → 移动端 VO 裁剪；下游服务不感知 HTTP / 移动端字段风格。
3. **BFF 聚合**：移动端"首页用户卡片"等聚合场景在网关内部用 `CompletableFuture` 并发调用多个 gRPC 拼装 VO，减少 App 端多次往返。
4. **限流 + CORS + traceId 透传**：Resilience4j 限流 / CORS 全局配置 / gRPC Metadata 透传 `x-user-id` `x-device-id` `x-trace-id`。
5. **鉴权域元数据透传**：下游业务服务从 gRPC Metadata 拿 `x-user-id`，完全不持有 token，业务代码无感鉴权。

### 用户角色

| 角色 | 使用场景 |
|------|---------|
| 新用户（未登录） | 调 `/api/v1/auth/sms/send` 拿验证码 → `/api/v1/auth/login/phone` 拿 token |
| 已登录用户（access 过期） | 调 `/api/v1/auth/refresh` 用 refresh token 换新 access |
| 多设备用户（设备 A + 设备 B 同时登录） | 两个 deviceId 独立发 token，互不干扰；登出时单设备 revoke |
| RN App 前端 | 调 `/api/v1/posts/feed`、`/api/v1/match/feed`、`/api/v1/profile/me` 等业务接口 |
| 运营 / 风控 | 强制登出某用户 → gateway 写黑名单 + revoke 该用户全部 refresh token |
| Nginx / 监控 | 调 `/actuator/health` 做健康检查；调 `/actuator/prometheus` 拉 metrics |

---

## 功能清单

| 功能编号 | 模块 | 功能名称 | 功能描述 | 优先级 |
|---------|------|---------|---------|--------|
| F001 | Auth | 短信验证码下发 | `/api/v1/auth/sms/send` 发送 SMS 验证码到手机号 | P0 |
| F002 | Auth | 手机验证码登录 | `/api/v1/auth/login/phone` 用 phone + smsCode + deviceId 登录 | P0 |
| F003 | Auth | 设备快速登录 | `/api/v1/auth/login/device` 用 deviceId 快速登录（已注册设备） | P0 |
| F004 | Auth | 三方授权登录 | `/api/v1/auth/login/third-party` Google / Apple 等三方授权登录 | P0 |
| F005 | Auth | Refresh Token 刷新 | `/api/v1/auth/refresh` 用 refresh token 换新 access + refresh | P0 |
| F006 | Auth | 登出 | `/api/v1/auth/logout` 写黑名单 + revoke refresh token | P0 |
| F007 | Auth | 用户引导信息提交 | `/api/v1/auth/onboarding` 提交昵称/年龄/性别/位置等 onboarding 信息 | P1 |
| F008 | Profile | 获取我的资料 | `/api/v1/profile/me` 拿当前用户 profile | P0 |
| F009 | Profile | 获取指定用户资料 | `/api/v1/profile/{userId}` 拿任意用户 profile | P0 |
| F010 | Profile | 更新我的资料 | `PUT /api/v1/profile/me` 更新昵称/简介/职业/学历等 | P0 |
| F011 | Profile | 批量获取用户资料 | `/api/v1/profile/users?userIds=1,2,3` 拿多个用户 profile | P0 |
| F012 | Match | 获取匹配 Feed | `/api/v1/match/feed?count=5` 拿推荐用户卡片列表 | P0 |
| F013 | Match | 滑动（右滑/左滑） | `/api/v1/match/swipe` 滑动行为 | P0 |
| F014 | Match | Super Hi（超级喜欢） | `/api/v1/match/super-hi` 强提醒（消耗金币） | P1 |
| F015 | Match | 获取已配对列表 | `/api/v1/match/matches` 已匹配用户列表（TODO 占位） | P1 |
| F016 | Match | 获取滑动历史 | `/api/v1/match/history` 滑动历史（TODO 占位） | P2 |
| F017 | Post | 创建动态 | `POST /api/v1/posts` 发新动态（文字 + 图片 keys） | P0 |
| F018 | Post | 获取动态详情 | `GET /api/v1/posts/{postId}` 拿单条动态详情 | P0 |
| F019 | Post | 删除动态 | `DELETE /api/v1/posts/{postId}` 删除自己发布的动态 | P0 |
| F020 | Post | 点赞 / 取消点赞 | `POST/DELETE /api/v1/posts/{postId}/like` | P0 |
| F021 | Post | 创建评论 | `POST /api/v1/posts/{postId}/comments` | P0 |
| F022 | Post | 获取评论列表 | `GET /api/v1/posts/{postId}/comments` | P0 |
| F023 | Post | 删除评论 | `DELETE /api/v1/posts/comments/{commentId}` | P0 |
| F024 | Post | 获取用户动态 | `GET /api/v1/posts/users/{userId}` 看指定用户动态 | P0 |
| F025 | Post | 获取推荐 Feed | `GET /api/v1/posts/feed` 推荐动态流 | P0 |
| F026 | IM | 获取 IM Token | `GET /api/v1/im/token` 拿 OpenIM 长连接 Token | P0 |
| F027 | IM | 获取通话 Token | `GET /api/v1/call/token?peerId=xxx` 拿 LiveKit 通话 JWT（TODO 占位） | P1 |
| F028 | Upload | 文件上传预签名 | `POST /api/v1/upload/presign` MinIO presigned PUT URL | P0 |
| F029 | Upload | 上传确认 | `POST /api/v1/upload/confirm` 确认文件已上传 | P0 |
| F030 | Home | 首页卡片聚合 | `/api/v1/home/cards` 推荐 + 用户资料 + 在线状态 BFF 聚合 | P1 |
| F031 | Health | 健康检查 | `GET /api/v1/health` Spring Boot Actuator | P0 |

> **状态说明**：F001-F005、F008-F013、F017-F025、F026、F028-F029、F031 是 P0 已实现（含部分占位）；F006/F007/F014-F016/F027/F030 是 P1/P2 部分实现或 TODO。详见 README 已知 gap。

---

## 功能详情

### 模块 1：Auth 鉴权（F001-F007）

#### F002 手机验证码登录（最核心流程）

##### 1. 功能描述
用户输入手机号 + 短信验证码 + 设备信息，gateway 校验验证码 → 调 user-service `ResolveOrCreateByPhone` 拿到 userId → 签发 access + refresh 双 token → 返回给 App。

##### 2. 业务规则
- **验证码**：6 位数字，5 分钟有效，每个手机号 5 次/小时（设计上，代码层目前只打 log）
- **三种登录方式互斥**：同一 deviceId 同一时刻只有一种登录态
- **设备指纹**：登录成功后 upsert 到 `auth_device` 表，`login_count++`
- **新设备首次登录**：标记 `newlyCreated=true`，触发 onboarding 流程
- **黑名单**：refresh token 用过的会被标 `used_at`，重放攻击可被检测

##### 3. 用户交互流程

```
1. 用户打开 App → 输入手机号 → 点"获取验证码"
2. App → POST /api/v1/auth/sms/send { phone: "13800138000" }
3. gateway → SMS 服务商 → 发验证码
4. 用户输入 6 位验证码 → 点"登录"
5. App → POST /api/v1/auth/login/phone { phone, smsCode, deviceId, platform, ... }
6. gateway.AuthServiceImpl.loginPhone:
   ├─ Redis 校验 smsCode（设计上）
   ├─ user-service.ResolveOrCreateByPhone(phone) → userId
   ├─ user-service.CheckBan(userId)        // 命中则抛 401
   ├─ AuthDeviceManager.upsert(userId, deviceId, ...)  // 设备记录
   ├─ JwtIssuer.issueTokens(userId, deviceId) → (access, refresh, jti1, jti2)
   ├─ AuthRefreshTokenManager.save(hash(refresh), jti, expiresAt)  // 落 PG
   └─ 返回 LoginResultVO
7. App 拿到 access + refresh → 存 Keychain
```

##### 4. 数据流转

```
App 请求 ──► AuthController.loginPhone ──► AuthServiceImpl.loginPhone
                                                  │
                            ┌─────────────────────┼─────────────────────┐
                            ▼                     ▼                     ▼
                   AuthDeviceManager.upsert   JwtIssuer.issueTokens   AuthRefreshTokenManager.save
                            │                     │                     │
                            ▼                     ▼                     ▼
                  PG auth_device         内存 RSA 私钥         PG auth_refresh_token
                  (login_count+1)        签发 access+refresh    (token_hash=SHA-256(refresh))
                                                                  + jti + expires_at
```

##### 5. 接口设计

**REST**：`POST /api/v1/auth/login/phone`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | E.164 手机号 |
| smsCode | String | 是 | 6 位短信验证码 |
| deviceId | String | 是 | 设备唯一标识（UUID） |
| platform | Integer | 是 | 1=iOS / 2=Android / 3=Web |
| deviceModel | String | 否 | "iPhone 15" |
| osVersion | String | 否 | "17.2" |
| appVersion | String | 否 | "1.0.0" |
| pushToken | String | 否 | APNs/FCM Token |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "refreshToken": "opaque-random-string-uuid",
    "userId": 1024,
    "pending": false,
    "newlyCreated": true,
    "accessExpiresAtMs": 1722153600000,
    "refreshExpiresAtMs": 1722758400000
  }
}
```

##### 6. 边界情况
- **验证码错误**：返回 `Result.fail(INVALID_SMS_CODE=10601, "验证码错误")`
- **验证码过期**：返回 `Result.fail(SMS_CODE_EXPIRED=10602, "验证码已过期")`
- **用户在风控黑名单**：返回 401，code=`UNAUTHORIZED=40100`
- **短信发送频率限制**：返回 `Result.fail(SMS_RATE_LIMITED=10002, "发送过于频繁")`
- **user-service 不可用**：抛 `GatewayException(UPSTREAM_UNAVAILABLE=10901)`，全局兜底

##### 7. 监控埋点
- `auth.login.phone.success`：手机登录成功计数
- `auth.login.phone.latency`：手机登录接口耗时
- `auth.token.issued`：token 签发计数（按 type 拆 access / refresh）
- `auth.refresh_token.persisted`：refresh token 落 PG 计数

---

#### F005 Refresh Token 刷新

##### 1. 功能描述
access token 过期后，App 用 refresh token 调 `/api/v1/auth/refresh`，gateway 校验 refresh 未过期 / 未撤销 / 未使用 / 设备匹配 → 旧 refresh 标 used → 签发新 access + 新 refresh → 返回。

##### 2. 业务规则
- **单次使用**：每个 refresh token 只能换一次新 token，第二次用同一 refresh 会触发重放检测 → 撤销该用户全部 refresh token
- **设备绑定**：refresh 必须与 access 在同一 deviceId 签发，跨设备使用视为异常
- **轮换**：每次刷新都生成全新 access + refresh，旧 refresh 标 `used_at`，新 refresh 写入新行
- **过期**：refresh 过期（默认 7 天）后无法续命，必须重新登录

##### 3. 用户交互流程

```
1. App 收到 401（access 过期）→ 自动调 /api/v1/auth/refresh
2. App → POST /api/v1/auth/refresh { refreshToken: "..." }
3. gateway.AuthServiceImpl.refreshToken:
   ├─ SHA-256(refreshToken) → tokenHash
   ├─ AuthRefreshTokenManager.findValidByTokenHash(tokenHash)
   │    ├─ 找不到 → 抛 INVALID_REFRESH_TOKEN
   │    ├─ 找到但 used_at != null → 触发重放检测 → revokeAllForUser → 抛 REFRESH_TOKEN_REUSED
   │    ├─ 找到但 revoked_at != null → 抛 REFRESH_TOKEN_REVOKED
   │    └─ 找到但 expires_at < now → 抛 REFRESH_TOKEN_EXPIRED
   ├─ deviceId 不匹配 → 抛 REFRESH_TOKEN_DEVICE_MISMATCH
   ├─ markUsed(oldEntity)              // used_at = now
   ├─ JwtIssuer.issueTokens(userId, deviceId) → (newAccess, newRefresh, newJti1, newJti2)
   └─ AuthRefreshTokenManager.save(newEntity)  // 新行
4. 返回 { accessToken, refreshToken, ... }
```

##### 4. 数据流转

```
refresh 请求 ──► AuthServiceImpl.refreshToken
                         │
                         ▼
              SHA-256(refresh) → tokenHash
                         │
                         ▼
              SELECT FROM auth_refresh_token WHERE token_hash=? AND used_at IS NULL AND revoked_at IS NULL AND deleted=0
                         │
                         ▼
              UPDATE auth_refresh_token SET used_at=NOW() WHERE id=?
                         │
                         ▼
              INSERT INTO auth_refresh_token (token_hash, jti, expires_at, ...) VALUES (?, ?, NOW()+7d, ...)
                         │
                         ▼
              返回新 (access, refresh)
```

##### 5. 接口设计

**REST**：`POST /api/v1/auth/refresh`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| refreshToken | String | 是 | refresh token opaque 字符串 |

**返回**：`LoginResultVO`（同 loginPhone）

##### 6. 边界情况
- **refresh 不存在**：返回 `Result.fail(INVALID_REFRESH_TOKEN=10004, "无效的 refresh token")`
- **refresh 已被用过**：触发重放检测 → 撤销该用户全部 refresh → 返回 `Result.fail(REFRESH_TOKEN_REUSED=10504, "refresh token 已失效，请重新登录")`
- **refresh 已撤销**：返回 `Result.fail(TOKEN_REVOKED=10503, "refresh token 已被撤销")`
- **refresh 设备不匹配**：返回 `Result.fail(REFRESH_TOKEN_DEVICE_MISMATCH=10505, "refresh token 设备不匹配")`
- **refresh 过期**：返回 `Result.fail(TOKEN_EXPIRED=10502, "refresh token 已过期")`

---

#### F006 登出

##### 1. 功能描述
用户主动登出（或风控强制下线）→ gateway 把当前 access token 的 jti 写 Redis 黑名单（TTL = access 剩余有效期）+ 撤销该用户该 device 的所有 refresh token。

##### 2. 业务规则
- **黑名单粒度**：按 jti 撤销单个 access token；按 (userId, deviceId) 撤销该设备全部 refresh token
- **黑名单 TTL**：与 access token 剩余有效期一致（最多 15 分钟），过期后自动从 Redis 删除
- **跨设备不影响**：只撤销当前 deviceId 的 refresh token，其他设备登录态保留

##### 3. 用户交互流程

```
1. App → POST /api/v1/auth/logout { refreshToken: "..." }
2. gateway.AuthServiceImpl.logout:
   ├─ SHA-256(refreshToken) → tokenHash
   ├─ 查 auth_refresh_token WHERE token_hash=? → 拿 userId, deviceId
   ├─ AuthRefreshTokenManager.revokeAllForUser(userId, deviceId)  // PG 标 revoked_at
   ├─ JwtVerifier.blacklist(currentAccessJti, ttl)  // Redis 写黑名单
   └─ 返回 success
3. App 清除本地 token → 跳回登录页
```

---

### 模块 2：Profile 用户档案（F008-F011）

#### F008 获取我的资料

##### 1. 功能描述
App 调 `/api/v1/profile/me`，gateway 从 `RequestContext.current().getUserId()` 拿当前用户 userId → 调 user-service gRPC `getUserProfile(userId)` → 返回 `UserProfileVO`。

##### 2. 业务规则
- **必须登录**：JWT 鉴权失败返回 401
- **用户不存在**：user-service 返回 NOT_FOUND → gateway 抛 `PROFILE_NOT_FOUND=10101`

##### 3. 数据流转
```
App → JwtAuthFilter 验签 → 写 RequestContext.userId
   → ProfileController.getMyProfile
   → ProfileServiceImpl.getProfile(userId)
   → UserClient.getUserProfile(userId) ──gRPC──► user-service
   ◄── UserProfileResponse(proto)
   → 字段裁剪 UserProfileVO（不返 avatarKey 等内部字段给前端以外的其他）
```

##### 4. 接口设计

**REST**：`GET /api/v1/profile/me`

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "userId": 1024,
    "nickname": "Alice",
    "avatar": "avatar/1024/202607/abc.jpg",
    "age": 25,
    "bio": "I love coding"
  }
}
```

##### 6. 边界情况
- **未登录**：JwtAuthFilter 拦截 → 401 `UNAUTHORIZED=40100`
- **用户不存在**：user-service NOT_FOUND → gateway 抛 `PROFILE_NOT_FOUND=10101`
- **user-service 不可用**：抛 `GatewayException(UPSTREAM_UNAVAILABLE=10901)`

---

#### F010 更新我的资料

##### 1. 功能描述
App 调 `PUT /api/v1/profile/me`，body 传 `UpdateProfileReq { nickname, age, height, bio, occupation, education, location }` → gateway 调 user-service `updateUserProfile` gRPC → 返回更新后 VO。

##### 2. 业务规则
- **部分更新**：body 中字段为 null 表示不更新；非 null 字段覆盖更新
- **鉴权**：必须登录
- **写一致性**：更新后清除 user-service 端 profile 缓存（cache aside）

##### 5. 接口设计

**REST**：`PUT /api/v1/profile/me`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| nickname | String | 否 | 昵称 1-20 字符 |
| age | Integer | 否 | 年龄 |
| height | Integer | 否 | 身高 cm |
| bio | String | 否 | 个人简介 ≤ 200 字符 |
| occupation | String | 否 | 职业 |
| education | String | 否 | 学历 |
| location | String | 否 | 所在城市 |

##### 6. 边界情况
- **nickname 长度校验**：gateway 不校验，依赖 user-service 端 JSR-380 校验（设计上）
- **越权更新**：当前实现 userId 从 token 解析，App 无法伪造 userId
- **user-service 失败**：抛 `PROFILE_UPDATE_FAILED=10102`

---

### 模块 3：Match 匹配（F012-F016）

#### F012 获取匹配 Feed

##### 1. 功能描述
App 调 `/api/v1/match/feed?count=5`，gateway 调 match-service gRPC `getRecommendations(userId, count)` → 返回推荐用户列表 → 转 `MatchCardVO` 列表。

##### 2. 业务规则
- **count 默认 5**：单次拉 5 张推荐卡片（默认）
- **过滤自己**：match-service 端过滤当前 userId
- **过滤已滑动**：match-service 端过滤 7 天内已滑动过的 user
- **过滤 DH**：match-service 端过滤数字人 user（业务规则决定）
- **在线状态**：当前 VO 未含 `isOnline`，后续补 im-service `ListOnlineUsers` 聚合

##### 3. 数据流转
```
App → MatchController.getFeed(count=5)
   → MatchServiceImpl.getFeed(userId, count)
   → MatchClient.getRecommendations(userId, count) ──gRPC──► match-service
   ◄── GetRecommendationsResponse { users: [RecommendedUser{userId, nickname, age, avatarKey}, ...] }
   → 转 MatchCardVO { targetUserId, nickname, age, photoKeys: [avatarKey], bio, distanceKm }
```

##### 5. 接口设计

**REST**：`GET /api/v1/match/feed?count=5`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| count | Integer | 否 | 拉几张卡片，默认 5，上限 50 |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": [
    { "targetUserId": 1025, "nickname": "Bob", "age": 27, "photoKeys": ["avatar/1025/202607/xyz.jpg"], "bio": "", "distanceKm": null },
    ...
  ]
}
```

##### 6. 边界情况
- **推荐为空**：返回 `[]`
- **match-service 不可用**：抛 `MATCH_LIST_FAILED=10303`

---

#### F013 滑动

##### 1. 功能描述
App 调 `/api/v1/match/swipe { targetUserId, direction }`，direction = "LEFT"（pass）或 "RIGHT"（like） → gateway 调 match-service `matchAction(fromUserId, toUserId, action)` → 返回 `isMatched` 是否配对成功。

##### 2. 业务规则
- **方向映射**：direction="RIGHT" → action="like"，direction="LEFT" → action="pass"
- **互配**：双向 like 才算 `isMatched=true`，返回 `MatchSuccessVO { isMatched: true, matchId: 12345, conversationId: 67890 }`（设计上，当前只返 boolean）
- **金币扣费**：当前不扣费，付费功能再设计

##### 5. 接口设计

**REST**：`POST /api/v1/match/swipe`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| targetUserId | Long | 是 | 被滑动的 user_id |
| direction | String | 是 | "LEFT" / "RIGHT" |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": true   // true=互配成功 / false=未配对
}
```

##### 6. 边界情况
- **重复滑动**：match-service 端幂等（同 (from, to) 第二次滑动直接返回历史结果）
- **滑动自己**：match-service 端拒绝（业务异常 → gateway 转 400）

---

### 模块 4：Post 动态（F017-F025）

#### F017 创建动态

##### 1. 功能描述
App 调 `POST /api/v1/posts { content, imageKeys: [...] }`，gateway 调 post-service `createPost(content, imageKeys)` gRPC → 返回新 postId。

##### 2. 业务规则
- **content 必填**：1-1000 字符（设计上，gateway 端无 JSR-380 注解）
- **图片数量**：最多 9 张（post-service 端校验）
- **图片 key 格式**：`avatar/xxx/yyyymm/uuid.ext`（MinIO object key）
- **登录校验**：必须登录
- **敏感词**：post-service 端异步检测（gateway 不做）

##### 3. 数据流转
```
App → PostController.createPost(req)
   → PostServiceImpl.createPost(userId, req)
   → PostClient.createPost(content, imageKeys) ──gRPC──► post-service
   ◄── CreatePostResponse { postId: 12345 }
   → 返回 { postId: 12345 }
```

##### 5. 接口设计

**REST**：`POST /api/v1/posts`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | String | 是 | 动态文字 |
| imageKeys | List<String> | 否 | MinIO object keys（最多 9） |

**返回**：`{ "postId": 12345 }`

##### 6. 边界情况
- **未登录**：401
- **post-service 失败**：抛 `POST_OPERATION_FAILED=10901`

---

#### F020 点赞 / 取消点赞

##### 1. 功能描述
- `POST /api/v1/posts/{postId}/like` 点赞
- `DELETE /api/v1/posts/{postId}/like` 取消点赞

gateway 调 post-service `actionLike(postId, action=LIKE/UNLIKE)` gRPC。

##### 2. 业务规则
- **幂等**：重复点赞 post-service 端幂等返回 success=true
- **点赞数累加**：post-service 内部 Redis INCR 计数 + 待刷盘 Set
- **取消点赞点赞数 -1**：必须先有 like 才能 unlike（设计上）
- **自己帖子也能点赞**：业务上不限制

##### 6. 边界情况
- **postId 不存在**：post-service NOT_FOUND → gateway 抛 10901
- **重复点赞**：幂等返回 success=true

---

#### F025 获取推荐 Feed

##### 1. 功能描述
App 调 `GET /api/v1/posts/feed?pageSize=20&cursor=xxx`，gateway 调 post-service `getRecommendFeed(pageSize, cursor)` gRPC → 返回推荐动态列表 + 下一页 cursor。

##### 2. 业务规则
- **cursor 分页**：服务端游标分页（不返 offset），避免深分页性能问题
- **pageSize 默认 20**：上限 50
- **推荐算法**：post-service 端基于用户兴趣标签 + 热度排序
- **过滤已删除**：post-service 端过滤 `deleted=1` 的帖子

##### 5. 接口设计

**REST**：`GET /api/v1/posts/feed`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| pageSize | Integer | 否 | 默认 20，上限 50 |
| cursor | String | 否 | 下一页游标（首次不传） |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": [
    { "postId": 12345, "userId": 1024, "content": "...", "imageKeys": [...], "likeCount": 100, "commentCount": 5, "isLiked": false, "createdAtSeconds": 1722153600 },
    ...
  ]
}
```

---

### 模块 5：IM 即时通讯（F026-F027）

#### F026 获取 IM Token

##### 1. 功能描述
App 首次进入聊天页面 → 调 `GET /api/v1/im/token` → gateway 调 im-service `getImToken(userId)` gRPC → 返回 OpenIM 长连接 Token → App 用此 Token 连接 OpenIM WebSocket。

##### 2. 业务规则
- **Token 有效期**：5000 秒（OpenIM 默认，约 1.4 小时）
- **懒注册**：im-service 端在 OpenIM 500 错误时自动注册用户 → 重试一次
- **gateway 不持 IM AppKey**：所有 OpenIM 相关凭证在 im-service
- **gateway 不维护 WS**：长连由 App → OpenIM，gateway 不参与（红线 #7）

##### 3. 数据流转
```
App → ImController.getImToken
   → ImServiceImpl.getImToken(userId)
   → ImClient.getImToken(userId, "", "") ──gRPC──► im-service
   ◄── GetImTokenResponse { imToken: "..." }
   → 返回 ImTokenVO { userId, imToken }
```

##### 5. 接口设计

**REST**：`GET /api/v1/im/token`

**返回**：`{ "userId": 1024, "imToken": "eyJ..." }`

##### 6. 边界情况
- **im-service 不可用**：当前 ImServiceImpl catch 异常后返回空 VO（吞异常）→ 应当抛 IM_TOKEN_FAILED=10601
- **用户不存在**：im-service NOT_FOUND → 抛 10601

---

### 模块 6：Upload 文件上传（F028-F029）

#### F028 文件上传预签名

##### 1. 功能描述
App 上传头像前调 `POST /api/v1/upload/presign { ext: "jpg", expectedSizeBytes: 1024000 }` → gateway 生成 MinIO object key + presigned PUT URL → 返回 App → App 用 URL 直传 MinIO（gateway 不读文件流）。

##### 2. 业务规则
- **object key 格式**：`avatar/<user_id>/<yyyymm>/<uuid>.<ext>`（遵守规范）
- **presigned URL 有效期**：默认 15 分钟（设计上）
- **gateway 不持 MinIO 凭证**：当前实现硬编码 `https://minio-api.jianjiange.site` 拼接 URL（不是真正 SDK 签名 URL，存在 gap #10）
- **大文件直传**：App → MinIO，gateway 不转发

##### 3. 数据流转
```
App → UploadController.presign(req)
   → UploadServiceImpl.presign(userId, req)
   → 生成 objectKey = "avatar/1024/202607/uuid.jpg"
   → 拼 uploadUrl = "https://minio-api.jianjiange.site/putao/avatar/1024/202607/uuid.jpg?presigned=true"
   → 返回 PresignAvatarUploadVO
App → 用 uploadUrl 直接 PUT 到 MinIO（带文件二进制）
```

##### 5. 接口设计

**REST**：`POST /api/v1/upload/presign`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ext | String | 否 | 文件扩展名，默认 jpg |
| expectedSizeBytes | Long | 否 | 预期文件大小（用于服务端校验） |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "uploadUrl": "https://minio-api.jianjiange.site/putao/avatar/1024/202607/uuid.jpg?presigned=true",
    "objectKey": "avatar/1024/202607/uuid.jpg",
    "expiresAt": 1722154500000
  }
}
```

##### 6. 边界情况
- **未登录**：401
- **ext 不在白名单**：当前无校验，生产需校验（png/jpg/jpeg/webp）

---

#### F029 上传确认

##### 1. 功能描述
App 上传文件到 MinIO 后调 `POST /api/v1/upload/confirm { objectKey: "..." }` → gateway 返回 `AvatarVO { originalKey, minKey, midKey, width, height }`（设计上，当前只返 originalKey）。

##### 2. 业务规则
- **缩略图生成**：MinIO 端事件触发缩略图（小/中/大三种尺寸）→ gateway 确认后返回三个 key
- **当前实现**：只回 originalKey，缩略图生成链路未实现（gap #10）

---

### 模块 7：Home 首页聚合（F030）

#### F030 首页卡片聚合（BFF 范式）

##### 1. 功能描述
App 打开首页调 `/api/v1/home/cards?pageSize=10` → gateway 内部并发调：
1. match-service `getRecommendations(userId, pageSize)` → 推荐用户列表
2. user-service `batchGetUserProfiles(userIds)` → 批量拿用户资料（bio 等）
3. （设计上）im-service `ListOnlineUsers(userIds)` → 在线状态

→ 拼装 `HomeCardVO` 列表返回。

##### 2. 业务规则
- **并发执行**：用 `CompletableFuture` 并行调用下游，三个 RPC 同时进行（总耗时 = max 而非 sum）
- **BFF 线程池隔离**：专用线程池 `bffExecutor`，不复用 Tomcat 线程
- **单接口总超时 800ms**：单 RPC 子调用超时 500ms（设计中）
- **降级策略**：关键 RPC（match）失败 → 抛异常；可降级 RPC（user/im）失败 → 兜底默认值 + WARN 日志

##### 3. 数据流转

```
App → HomeController.getHomeCards(pageSize=10)
   → HomeServiceImpl.getHomeCards(userId, pageSize):
        ┌──────────────────────────────────────────────────────┐
        │ CompletableFuture.supplyAsync(() ->                  │
        │     matchClient.getRecommendations(userId, pageSize),│
        │     bffExecutor                                      │
        │ ).thenCompose(recs -> {                              │
        │     List<Long> ids = recs.stream()...toList();       │
        │     return CompletableFuture.supplyAsync(() ->       │
        │         userClient.batchGetUserProfiles(ids),        │
        │         bffExecutor);                                │
        │ }).thenApply(profiles ->                             │
        │     HomeCardConverter.assemble(recs, profiles));     │
        └──────────────────────────────────────────────────────┘
   → List<HomeCardVO>
```

##### 4. 接口设计

**REST**：`GET /api/v1/home/cards?pageSize=10`

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": [
    { "targetUserId": 1025, "nickname": "Bob", "age": 27, "gender": 1, "bio": "...", "avatar": "avatar/1025/..." },
    ...
  ]
}
```

##### 6. 边界情况
- **match 返回空**：直接返回 `[]`，不调 user-service
- **match 失败**：抛 `HOME_CARDS_FAILED=10401`（关键 RPC 不可降级）
- **user 失败**：当前实现 catch 后返回 List.of()（吞异常，gap #8）

---

### 模块 8：Health 健康检查（F031）

#### 1. 功能描述
Nginx / K8s / 监控定期调 `/api/v1/health` 检查 gateway 是否存活。

#### 5. 接口设计

**REST**：`GET /api/v1/health`

**返回**：`{ "code": 0, "message": "success", "data": { "status": "UP" } }`

> 公开接口（不走 JwtAuthFilter），JwtAuthFilter 中 `PUBLIC_PATHS` 白名单含 `/api/v1/health`。

---

## 数据模型

### auth_device 设备表

```sql
CREATE TABLE auth_device (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    device_id     VARCHAR(128) NOT NULL,
    platform      INTEGER NOT NULL,        -- 1=iOS, 2=Android, 3=Web
    device_model  VARCHAR(128),
    os_version    VARCHAR(32),
    app_version   VARCHAR(32),
    push_token    VARCHAR(512),
    login_count   INTEGER DEFAULT 1,
    last_login_at TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted       INTEGER DEFAULT 0
);
CREATE INDEX idx_auth_device_user_device ON auth_device(user_id, device_id);
CREATE INDEX idx_auth_device_device ON auth_device(device_id);
```

### auth_refresh_token 刷新 Token 表

```sql
CREATE TABLE auth_refresh_token (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    device_id  VARCHAR(128) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,       -- SHA-256(refresh_token)
    jti        VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,                -- 单次使用标志
    revoked_at TIMESTAMPTZ,                -- 主动撤销标志
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted    INTEGER DEFAULT 0
);
CREATE INDEX idx_auth_refresh_token_hash ON auth_refresh_token(token_hash);
CREATE INDEX idx_auth_refresh_token_user ON auth_refresh_token(user_id);
CREATE INDEX idx_auth_refresh_token_jti ON auth_refresh_token(jti);
```

---

## 鉴权状态机

```
                  登录成功
   未登录 ────────────────────► 已登录(access 有效)
     ▲                              │
     │                              │ access 过期
     │ refresh 过期/撤销            ▼
     │                          access 无效
     │                              │
     │  refresh 有效                │ refresh 有效
     │  (单次使用)                  │ (轮换)
     │                              ▼
     │                          refresh 成功
     │                              │
     │ 登出                         ▼
     └──────────────────────── 已登录(access 重新有效)
                  登出/黑名单
```

### Token 生命周期

| 状态 | 字段 | 含义 |
|------|------|------|
| 初始签发 | `used_at=NULL, revoked_at=NULL, expires_at=NOW()+7d` | 有效 |
| 正常使用 | 同上，且 `now < expires_at` | 可用来换新 token |
| 已用过 | `used_at != NULL` | 单次使用，第二次用触发重放检测 → revoke 全部 |
| 已撤销 | `revoked_at != NULL` | 主动登出或风控触发 |
| 已过期 | `expires_at < now` | 需重新登录 |

---

## 错误码分区

| 段位 | 归属服务 | 说明 |
|------|---------|------|
| `10xxx` | mobile-gateway 独占 | 鉴权 / 业务编排错误 |
| `10002` | gateway | SMS_RATE_LIMITED |
| `10003` | gateway | INVALID_SMS_CODE |
| `10004` | gateway | INVALID_REFRESH_TOKEN |
| `101xx` | gateway | PROFILE 系列（10101 NOT_FOUND / 10102 UPDATE_FAILED / 10103 BATCH_FAILED） |
| `103xx` | gateway | MATCH 系列（10301 SWIPE_FAILED / 10302 SUPER_HI_FAILED） |
| `10401` | gateway | HOME_CARDS_FAILED |
| `105xx` | gateway | Token 系列（10501 INVALID / 10502 EXPIRED / 10503 REVOKED / 10504 REUSED / 10505 DEVICE_MISMATCH） |
| `106xx` | gateway | 短信/三方（10601 SMS_INVALID / 10602 SMS_EXPIRED / 10603 THIRD_PARTY_INVALID） |
| `10901` | gateway | UPSTREAM_UNAVAILABLE |
| `40100` | gateway | UNAUTHORIZED |
| `42900` | gateway | TOO_MANY_REQUESTS |
| `50000` | gateway | INTERNAL_ERROR |

> **设计原则**（来自 mobile-gateway-design.md §5.6）：mobile-gateway 独占 `10500+`，user-service 独占 `10001–10499`，互不重叠，看码即知归属。

---

## 监控指标

### Micrometer Counter / Timer

| 指标 | 类型 | 标签 | 含义 |
|------|------|------|------|
| `auth.login.phone.success` | Counter | - | 手机登录成功次数 |
| `auth.login.device.success` | Counter | - | 设备登录成功次数 |
| `auth.login.thirdparty.success` | Counter | platform | 三方登录成功次数 |
| `auth.token.issued` | Counter | type=access/refresh | token 签发次数 |
| `auth.refresh.success` | Counter | - | refresh 成功次数 |
| `auth.refresh.replay_detected` | Counter | - | 重放攻击触发次数 |
| `auth.logout.success` | Counter | - | 登出成功次数 |
| `http.server.requests` | Timer | uri, method, status | 所有 HTTP 请求耗时（Spring Boot Actuator 自动埋点） |
| `grpc.client.requests` | Timer | service, method, status | 所有 gRPC 调用耗时 |
| `resilience4j.ratelimiter.available` | Gauge | name | Resilience4j 限流器可用许可数 |

### 健康检查

- `/actuator/health`：Spring Boot Actuator 默认健康端点，含 DB / Redis / Disk Space
- `/api/v1/health`：业务自定义健康端点（返回 `{status: UP}`）
- Docker `HEALTHCHECK` 指令：`wget http://localhost:18080/actuator/health`

### Prometheus 暴露

`management.endpoints.web.exposure.include: health,info,metrics,prometheus`

---

## 学习重点小结

1. **JWT RS256 + 双 token 设计**是 gateway 鉴权闭环的核心（access 15min + refresh 7d + 黑名单 + 轮换）
2. **ThreadLocal RequestContext + gRPC Metadata 透传**让下游业务服务零感知鉴权（业务代码无 userId 参数）
3. **BFF 聚合**（HomeService 用 `CompletableFuture` 并发调多服务）是 gateway 与路由网关的最大差异点
4. **gateway 持久层仅鉴权域 2 张表**，严守红线 #2：业务数据一律 gRPC 调下游
5. **错误码段位化**（gateway `10500+` / user `10001-10499` / 通用 `50000+`）便于按码定位服务归属
