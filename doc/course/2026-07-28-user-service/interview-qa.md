# user-service 面试问答

> 用户身份解析 + 用户资料域服务面试题库

---

## 一、架构与定位

### Q1: user-service 的定位是什么？它和 mobile-gateway 的职责边界在哪里？

**参考答案:**

user-service 是**用户身份解析 + 用户资料域服务**，承担用户的「我是谁」「我长什么样」「我喜欢什么」的全部持久化能力。

mobile-gateway 是**鉴权域服务**，关注「这一次会话」的凭证管理。

| 维度 | mobile-gateway | user-service |
|------|----------------|--------------|
| 关注问题 | 这一次会话凭证 | 我是谁、我长什么样 |
| 核心表 | auth_device / auth_refresh_token | user_info / user_login_phone 等 |
| 输入 | 短信验证码 / 第三方 token / 设备 ID | 已验证的 phone / thirdPartyId / deviceId |
| 输出 | access JWT + refresh token | userId + 用户资料 |
| 是否调对方 | 调 user-service | 不调网关 |

**登录闭环:**
```
[App] ──phone+sms──▶ [gateway]
                      │ 1. Redis 验短信码
                      │ 2. gRPC user-service.ResolveOrCreateByPhone
                      │ 3. CheckBan
                      │ 4. 签 JWT + refresh token
                      ▼
              {access_token, userId}
```

### Q2: 为什么 user-service 不自己签 JWT 而要通过 gateway？

**参考答案:**

1. **职责单一**: JWT 签发是会话管理的范畴，属于鉴权域；user-service 只负责用户数据的持久化
2. **安全隔离**: JWT 包含敏感会话信息（如设备指纹、登录时间），这些信息 user-service 不掌握
3. **无状态扩展**: Gateway 签发的 JWT 可以被任何服务验证（只需要知道密钥），而 user-service 签发的 token 会引入服务间耦合
4. **网关统一入口**: 所有外部请求都经过 gateway，gateway 是 TLS 终止点、请求路由中心

### Q3: 为什么用 gRPC 而不是 REST 作为内部服务通信协议？

**参考答案:**

1. **性能**: gRPC 基于 HTTP/2，支持多路复用、头部压缩，比 REST over HTTP/1.1 快 2-3 倍
2. **强类型**: Proto 文件定义服务接口，编译时校验，比 JSON Schema 更严格
3. **代码生成**: 三语言（Java/Go/TS）自动生成 stub，避免手写客户端代码
4. **流式支持**: gRPC 原生支持 Server Streaming / Client Streaming / Bidirectional Streaming
5. **双向认证**: gRPC 支持 TLS + mTLS，服务间通信更安全

---

## 二、数据库设计

### Q4: 为什么使用雪花 ID 作为业务主键而不是自增 ID？

**参考答案:**

1. **跨库唯一**: 自增 ID 在单库没问题，但分库分表时会有冲突；雪花 ID 可以在任何机器、任何数据库生成全局唯一 ID
2. **信息安全**: 自增 ID 会暴露业务量（如 1,2,3...），雪花 ID 是无规律的 64 位整数
3. **分布式生成**: 不依赖数据库 AUTO_INCREMENT，可以先在应用层生成 ID 再插入（减少数据库交互）
4. **时间有序**: 雪花 ID 包含时间戳部分，大致有序，有利于索引

**代价**: 
- 64 位 ID 比 32 位自增 ID 占用更多空间
- 需要独立的 ID 生成器（单机内存 / Redis / 独立服务）

### Q5: 第三方绑定表和设备绑定表为什么用 EXCLUDE 约束而不是唯一索引？

**参考答案:**

业务场景是**软删后允许重绑**：
- 用户 A 解绑了 Google 账号
- 用户 B 后面可能使用同一个 Google 账号注册
- 如果用普通唯一索引，第一次解绑后的 deleted=1 记录仍然占据索引，导致无法插入新记录

PostgreSQL 的 `EXCLUDE` 约束配合 `WHERE deleted = 0` 实现：
- 只对 `deleted=0` 的行强制唯一
- 软删后 `deleted=1`，约束自动失效，可以插入新记录
- 需要 `btree_gist` 扩展支持

```sql
CONSTRAINT uq_user_third_party_active
    EXCLUDE (platform WITH =, third_party_user_id WITH =, app_name WITH =)
    WHERE (deleted = 0)
```

### Q6: 为什么 user_info 表的 `regulation_status` 用 2 和 5 表示封禁/暂停，而不是 1 和 2？

**参考答案:**

这是**枚举值预留**的设计技巧：
- `0` = 正常
- `2` = Banned（封禁）
- `5` = Suspended（暂停）

预留 `1, 3, 4` 等值是为了未来扩展：
- `1` = 待验证
- `3` = 风险用户
- `4` = 限制功能

如果用 `1, 2` 表示，新增状态时需要修改现有代码。

### Q7: 为什么兴趣标签用全量替换而不是增量更新？

**参考答案:**

1. **简化逻辑**: 增量更新需要处理「新增」「删除」「修改」三种情况，全量替换只需要 DELETE + INSERT
2. **无状态操作**: 前端不需要记住上次提交了什么，只需提交当前想要的列表
3. **并发友好**: 全量替换不存在「基于旧数据修改」的竞态条件
4. **实现简单**: 服务层一个事务内完成，不容易出 bug

**代价**: 每次更新都要传全量数据，但兴趣标签数量有限（≤50），数据量可接受。

---

## 三、分布式锁与并发

### Q8: ResolveOrCreate 为什么要加分布式锁？

**参考答案:**

防止**并发注册**导致的重复创建问题：

```
时序问题示例（无锁）:
T1: 用户A 发请求，查询 phone 库，未命中
T2: 用户B 发请求，查询 phone 库，未命中
T3: 用户A 创建 placeholder，userId=100
T4: 用户B 创建 placeholder，userId=101
T5: 用户A 绑定 phone → userId=100
T6: 用户B 绑定 phone → 唯一约束冲突！或绑定成功但 phone 对应两个用户
```

加锁后的流程:
```
1. 加锁 lock:register:phone:{phone}:{app}
2. 查询
3. 命中 → 解锁返回
4. 未命中 → 创建 → 绑定 → 解锁返回
```

即使两个请求同时到达，第二个请求会在锁上等待，第一个完成后第二个查到数据直接返回。

### Q9: 锁的 wait 时间和 lease 时间分别设多少？为什么？

**参考答案:**

```java
private static final long LOCK_WAIT_SECONDS = 3;   // 等待 3 秒
private static final long LOCK_LEASE_SECONDS = 30; // 持有 30 秒
```

- **waitTime = 3s**: 大多数正常情况下，锁持有时间很短（毫秒级），3 秒足够等待。如果 3 秒还拿不到锁，说明有严重问题（死锁、长时间事务），不应该无限等待
- **leaseTime = 30s**: 保守值，覆盖可能的慢查询/慢网络。如果业务逻辑超过 30 秒还没执行完，锁会自动释放（防止死锁）

**如果业务真的需要 30 秒以上怎么办？**
- 方案 1: 拆解长事务为短事务
- 方案 2: 用 `lock.tryLock(0, TimeUnit.SECONDS)` 然后手动续期（Redisson 支持）

### Q10: 出现 DuplicateKeyException 时为什么不直接抛错而是重查？

**参考答案:**

这是**乐观锁 + 悲观锁混合**的降级策略：

```java
try {
    userLoginPhoneManager.insert(userId, phoneE164, appName);
} catch (DuplicateKeyException dup) {
    // 并发插入冲突，重查
    existing = userLoginPhoneManager.findByPhoneAndApp(phoneE164, appName);
    if (existing != null) {
        userInfoManager.touchLastOpenAt(existing.getUserId());
        return ResolveOrCreateVO.builder()
                .userId(existing.getUserId())
                .pending(false)
                .newlyCreated(false)
                .build();
    }
    throw dup;  // 真的出问题了
}
```

场景分析:
- **加锁后还有并发插入**: 锁是本服务的，但两个请求可能分别来自不同服务实例/不同服务，都调用了 ResolveOrCreate。第一个请求释放锁和第二个请求获取锁之间有窗口期
- **幂等处理**: 重查可以确认到底是哪个请求赢了，返回正确结果而不是报错

---

## 四、缓存策略

### Q11: 为什么用「先写库再删缓存」而不是「先删缓存再写库」？

**参考答案:**

两种策略的区别:

**方案 A: 先写库再删缓存（Cache Aside - 读多写少优化）**
```
写: 写库 → 删缓存
读: 缓存命中返回，未命中回源写缓存
```

**方案 B: 先删缓存再写库**
```
写: 删缓存 → 写库
读: 未命中回源
```

**选择方案 A 的原因:**

1. **数据安全性**: 先写库保证数据持久化，即使删缓存失败，数据还在数据库里，最终一致性有保障
2. **写失败的处理**: 如果先删缓存再写库，写库失败会导致缓存被删除但数据没更新，出现「缓存空，数据库旧」的问题
3. **读请求的影响**: 极端情况下「缓存被删 + 写库完成前 + 读请求」会导致缓存穿透到数据库，但这是瞬时的，很快会被回填

### Q12: 为什么封禁缓存只有 5 分钟而不是更长？

**参考答案:**

封禁状态的特殊性:
1. **实时性要求高**: 管理员封禁用户后，希望尽快生效。5 分钟是最长等待时间
2. **写入频率低**: 封禁操作不频繁（相对查询频率），即使缓存失效去查 DB，DB 压力也不大
3. **一致性要求**: 用户被封禁是「紧急事件」，不能等缓存过期

**对比兴趣缓存 7 天**:
- 兴趣标签变化频率低
- 用户量级大，缓存命中节省 DB 资源

### Q13: 批量查询为什么不用 Redis Hash 而用 DB IN 查询？

**参考答案:**

两种方案对比:

**方案 A: Redis Hash（MGET）**
```
1. 所有 userId 拼成 hash tag: user:profile:batch:{userId1,userId2,...}
2. MGET 批量获取
3. 未命中部分回源 DB
4. 回填缓存
```

**方案 B: 直接 DB IN 查询（当前实现）**
```
1. DB IN 一次查询
2. 组装结果返回
```

**选择方案 B 的原因:**

1. **实现简单**: 不需要处理 Redis key 的批量操作复杂性
2. **DB 性能足够**: 200 条数据的 IN 查询，PG 在 10ms 内可以完成
3. **避免大 key**: 批量数据如果存成一个大 Hash，序列化/反序列化成本高
4. **缓存收益有限**: 批量请求通常是一次性的（展示用户列表），缓存命中概率不高

---

## 五、gRPC 与 Context

### Q14: gRPC metadata 是怎么传递 userId 的？

**参考答案:**

```
[App] → [Gateway] → [user-service gRPC]
         │ 解析 JWT
         │ 拿到 userId
         │ 塞入 metadata: x-user-id
         ▼
[user-service]
  │ UserIdContextInterceptor 拦截
  │ 从 metadata 提取 x-user-id
  │ 注入 gRPC Context
  ▼
[Service 层] UserContext.callerUserId()
```

```java
// Interceptor 实现
@Override
public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(...) {
    // 1. 解析 metadata
    Long userId = parseLong(headers.get(USER_ID_METADATA_KEY));
    String traceId = headers.get(TRACE_ID_METADATA_KEY);
    if (traceId == null) traceId = UUID.randomUUID().toString();
    
    // 2. 注入 Context
    Context ctx = Context.current()
            .withValue(USER_ID_CONTEXT_KEY, userId)
            .withValue(TRACE_ID_CONTEXT_KEY, traceId);
    
    return Contexts.interceptCall(ctx, call, headers, next);
}

// 业务代码使用
Long userId = UserContext.callerUserId();
```

### Q15: 为什么用 gRPC Context 而不是 ThreadLocal 传递 userId？

**参考答案:**

1. **虚拟线程兼容**: gRPC 默认使用虚拟线程模型，ThreadLocal 在虚拟线程间的行为可能不符合预期
2. **异步调用**: gRPC 支持异步流，ThreadLocal 无法跨越异步边界
3. **Context 是标准做法**: gRPC 官方推荐使用 Context 传递请求级别的数据
4. **可传递性**: Context 可以传递给子 Context，支持拦截器链

---

## 六、头像上传

### Q16: 为什么要用 Presigned URL 而不是服务端转发？

**参考答案:**

```
方案 A: 服务端转发（传统方式）
[App] → [Server] → [Object Storage]
         ↑ 流量要过服务端
         问题: 服务端成为瓶颈，带宽浪费

方案 B: Presigned URL（当前方案）
[App] ← [Server: 签 URL] → [Object Storage]
                ↑ 只签 URL
                优点: 节省服务端带宽/流量
```

**好处:**
1. **减轻服务端负载**: 不需要中转二进制流
2. **减少延迟**: 客户端直连对象存储，网络路径更短
3. **横向扩展**: 对象存储天然支持水平扩展，不会有服务端中转的性能瓶颈
4. **CDN 友好**: 预签名 URL 可以直接指向 CDN

**安全性:**
- 预签名 URL 有 TTL（5 分钟），过期后无法使用
- URL 包含签名，防篡改

### Q17: Confirm 时为什么检查 objectKey 前缀？

**参考答案:**

```java
String prefix = "avatar/" + userId + "/";
if (!dto.getObjectKey().startsWith(prefix)) {
    throw new BizException(ErrorCode.AVATAR_OBJECT_KEY_MISMATCH);
}
```

防止**越权覆盖**:
- 用户 A 的 userId = 100
- 用户 B 的 userId = 200
- 如果不检查前缀，用户 A 可能传 `avatar/200/xxx.jpg`，修改用户 B 的头像

检查前缀确保 `avatar/{当前用户}/` 格式，防止跨用户攻击。

---

## 七、异常处理

### Q18: gRPC 异常是怎么转换成 Status 的？

**参考答案:**

```java
// GrpcExceptionAdvice.java
@GrpcExceptionHandler(UserNotFoundException.class)
public Status handleUserNotFound(UserNotFoundException e) {
    return Status.NOT_FOUND.withDescription(e.getGrpcDescription());
}

@GrpcExceptionHandler(UserBannedException.class)
public Status handleBanned(UserBannedException e) {
    return Status.PERMISSION_DENIED.withDescription(e.getGrpcDescription());
}

@GrpcExceptionHandler(BizException.class)
public Status handleBiz(BizException e) {
    return Status.FAILED_PRECONDITION.withDescription(e.getGrpcDescription());
}

@GrpcExceptionHandler(Exception.class)
public Status handleAny(Exception e) {
    return Status.INTERNAL.withDescription("Internal server error");
}
```

| 异常类型 | gRPC Status | 说明 |
|----------|-------------|------|
| UserNotFoundException | NOT_FOUND (404) | 资源不存在 |
| UserBannedException | PERMISSION_DENIED (7) | 无权限 |
| BizException (通用) | FAILED_PRECONDITION (9) | 前置条件不满足 |
| 其他 Exception | INTERNAL (13) | 服务器内部错误 |

---

## 八、可靠性设计

### Q19: 怎么保证注册接口的幂等性？

**参考答案:**

三层幂等保障:

**1. 数据库唯一约束（最终防线）**
```sql
-- user_login_phone 的唯一约束
CONSTRAINT uq_user_login_phone_e164_app UNIQUE (phone_e164, app_name)
```
即使所有应用层逻辑都失败，数据库唯一约束保证不会插入重复记录。

**2. 分布式锁（防止并发）**
```java
RLock lock = redissonClient.getLock(RedisKey.lockRegisterPhone(phoneE164, appName));
boolean acquired = lock.tryLock(3, 30, TimeUnit.SECONDS);
if (!acquired) throw new BizException("could not acquire lock");
```

**3. 重复请求处理（兜底）**
```java
try {
    userLoginPhoneManager.insert(userId, phoneE164, appName);
} catch (DuplicateKeyException dup) {
    // 并发插入冲突，查询已存在的记录返回
    existing = userLoginPhoneManager.findByPhoneAndApp(...);
    return existing;
}
```

### Q20: 如果 Redis 不可用，CheckBan 会怎样？

**参考答案:**

代码逻辑:

```java
public BanStatusVO checkBan(Long userId) {
    // 1. 短缓存
    BanReason cached = userBanManager.queryBanReason(userId);
    if (cached != null) {
        return toBanStatusVO(cached);  // Redis 不可用时 cached=null
    }
    
    // 2. 运营级封禁
    if (userBanManager.isOperationalBanned(userId)) {  // Redis 不可用时返回 false
        ...
    }
    
    // 3. DB regulation_status（最终数据源）
    UserInfoEntity info = userInfoManager.findByUserId(userId);
    BanReason reason = UserBanManager.reasonFromRegulationStatus(...);
    return toBanStatusVO(reason);
}
```

**降级策略:**
- Redis 不可用时，短缓存查询返回 null，降级到 DB 查询
- 运营级封禁（Redis Set）暂时失效，但 DB regulation_status 仍然有效
- 不会阻塞服务，只是缓存命中率下降

**业务影响:**
- 用户登录可能暂时无法被运营级封禁拦截（等 Redis 恢复）
- DB 查询会增加，但不会拒绝服务

### Q21: 封禁检查的完整流程是什么？

**参考答案:**

```
CheckBan(userId) 三级检查:
┌─────────────────────────────────────────────────────────────┐
│ 1. Redis 短缓存 (TTL 5min)                                  │
│    key: putao:user:ban:status:{userId}                     │
│    命中 → 直接返回                                          │
│    未命中 → 继续下一步                                      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. Redis 运营封禁 Set                                       │
│    key: putao:user:ban:thirdparty-set                       │
│    isMember(userId) → 命中 → 返回 OPERATIONAL               │
│    未命中 → 继续下一步                                      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. DB regulation_status                                     │
│    0 = NONE（正常）                                         │
│    2 = USER_BANNED（封禁）                                  │
│    5 = USER_SUSPENDED（暂停）                              │
│    其他 → NONE                                             │
│    回填缓存 → 返回                                         │
└─────────────────────────────────────────────────────────────┘
```

---

## 九、扩展问题

### Q22: 如果要支持同一手机号在不同 App 有不同用户，怎么设计？

**参考答案:**

当前设计已经支持:

```sql
-- user_login_phone 表
CONSTRAINT uq_user_login_phone_e164_app UNIQUE (phone_e164, app_name)
```

同一手机号 + 不同 app_name 可以各自对应不同 userId：
- phone=+8613800138000 + app_name=vibe → userId=100
- phone=+8613800138000 + app_name=chatvibe → userId=200

**如果未来要改成「全局唯一手机号」:**

需要数据迁移：
1. 新增临时表 `user_login_phone_migration`
2. 脚本将 `(phone, MAX(app_name))` 的映射导入
3. 删除 `(phone, app_name)` 唯一约束
4. 添加 `phone` 唯一约束
5. 应用层判断：如果 phone 已存在，返回「该手机号已被其他账户使用」

### Q23: 快速登录（设备登录）的安全隐患及缓解措施？

**参考答案:**

**风险:**
1. 刷号成本低：无短信/无三方，攻击者可以大量创建设备 ID 注册虚假用户
2. 设备 ID 不稳定：iOS IDFV 卸载重装会变，Android SSAID 工厂重置会变

**缓解措施（MVP 后）:**
1. **iOS DeviceCheck / Android Play Integrity**: 验证设备的真实性
2. **IP 限流**: 单 IP 每天限制注册次数
3. **行为分析**: 同一设备大量注册触发风控
4. **强制绑定手机**: 快速登录用户使用一段时间后，强制要求绑定手机号

### Q24: 如果 user_info 表数据量达到千万级，怎么优化查询？

**参考答案:**

**当前索引:**
```sql
CREATE INDEX idx_user_info_recall
    ON user_info (user_type, gender, city_id, age, beauty_score)
    WHERE deleted = 0;
```

**优化方向:**

1. **分表策略（按 user_type）:**
   - BH 表和 DH 表分开，减少扫描范围
   - DH 主要给 match-service 用，查询频率不同

2. **冷热分离:**
   - 最近 N 个月活跃的用户放热库
   - 历史用户归档到冷库

3. **读写分离:**
   - 主库写，备库读（备库服务 ListDhCandidates 等只读场景）

4. **ES 辅助:**
   - 复杂条件查询（如多维度筛选）走 Elasticsearch
   - user-service 只负责主键查询

### Q25: 如何设计一个高效的「附近的人」功能？

**参考答案:**

**方案 1: Redis GEO（当前方案预留）**
```java
GEOADD user:geo:bh {lng} {lat} {userId}
GEORADIUS user:geo:bh {lng} {lat} {radius_km} km
```
适合用户量 < 100 万的场景，性能极好。

**方案 2: 数据库 GEO 索引（当前表结构已支持）**
```sql
CREATE INDEX idx_user_info_geo ON user_info 
    USING gist (ll_to_earth(lat, lng));
-- 查询
SELECT * FROM user_info 
WHERE user_type = 1 
  AND deleted = 0
  AND earth_distance(
      ll_to_earth(lat, lng),
      ll_to_earth(?, ?)
  ) < 100000  -- 100km
ORDER BY last_open_at DESC
LIMIT 50;
```

**方案 3: ES / MongoDB（大规模）**
- 支持更复杂的地理位置查询
- 可以结合全文搜索

**召回流程（当前 proto 接口）:**
```proto
message NearbyUsersRequest {
    int64 user_id = 1;           // 中心点
    double radius_km = 2;         // 搜索半径
    int32 age_min = 3;
    int32 age_max = 4;
    int32 beauty_min = 5;
    int32 beauty_max = 6;
    repeated int64 exclude_user_ids = 9;
    int32 limit = 10;
}
```

---

## 十、代码质量

### Q26: 为什么用 MapStruct 而不是 BeanUtils？

**参考答案:**

| 特性 | MapStruct | BeanUtils |
|------|-----------|-----------|
| 转换时机 | 编译期生成字节码 | 运行时反射 |
| 性能 | 快（接近直接赋值） | 慢（反射开销） |
| 类型安全 | 编译期检查 | 运行时才发现错误 |
| 配置性 | 强大（@Mapping 注解） | 有限 |
| 空值处理 | 可配置 | 默认跳过 null |

```java
// MapStruct 编译生成的是这样的代码:
public UserProfileVO toVO(UserInfoEntity entity) {
    UserProfileVO vo = new UserProfileVO();
    vo.setUserId(entity.getUserId());
    vo.setNickname(entity.getNickname());
    // ...
    return vo;
}
```

### Q27: 为什么 proto builder 用手写而不是 MapStruct 生成？

**参考答案:**

设计文档红线 **"禁止引入 MapStruct protobuf 扩展"**:
1. protobuf 生成的是不可变 builder，不兼容 MapStruct 的 setter 模式
2. proto 字段到 VO 的映射太简单，手写更清晰
3. 避免引入第三方 protobuf-mapstruct-adapter 依赖
4. proto 接口变更时，手写转换更容易追踪变化

---

## 十一、系统设计

### Q28: 设计一个用户注册流程，从收到手机号开始到返回 userId

**参考答案:**

```
┌────────────────────────────────────────────────────────────────────┐
│                        用户注册流程                                    │
├────────────────────────────────────────────────────────────────────┤
│                                                                    │
│  [App] ──1. 发送验证码──▶ [gateway]                                │
│          ◀── 200 OK ──────                                        │
│                                                                    │
│  [App] ──2. 验证短信码──▶ [gateway]                                 │
│          ◀── 验证成功 ────                                         │
│                                                                    │
│  [App] ──3. gRPC ResolveOrCreateByPhone ──▶ [user-service]         │
│            phoneE164="+8613800138000", appName="vibe"              │
│            │                                                       │
│            │ 3.1 libphonenumber 规范化                             │
│            │ 3.2 Redisson 加锁                                     │
│            │ 3.3 查询 user_login_phone                             │
│            │     未命中 → 创建 user_info placeholder               │
│            │     插入 user_login_phone 绑定                        │
│            │ 3.4 解锁                                              │
│            ◀── ResolveOrCreateResponse {userId, pending=true} ────│
│                                                                    │
│  [App] ──4. gRPC CheckBan ──▶ [user-service]                       │
│            ◀── BanResult {banned=false} ────                     │
│                                                                    │
│  [App] ──5. 跳转到 Onboarding 页面                                 │
│                                                                    │
│  [App] ──6. gRPC UpsertOnboarding ──▶ [user-service]               │
│            nickname, gender, birthday, interests...                 │
│            │                                                       │
│            │ 6.1 校验必填字段                                       │
│            │ 6.2 更新 user_info (pending=0)                       │
│            │ 6.3 插入 user_interest                                │
│            │ 6.4 清缓存                                           │
│            ◀── OnboardingResponse {UserProfileProto} ─────────────│
│                                                                    │
│  [App] ──7. gRPC GetImToken ──▶ [im-service]                     │
│            ◀── {im_token} ──────────────────────────────────────│
│                                                                    │
│  [App] ──8. 登录完成                                              │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### Q29: 如何实现用户资料的版本控制和历史记录？

**参考答案:**

**方案 1: 审计表（Audit Log）**
```sql
CREATE TABLE user_profile_audit (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    changed_by BIGINT,          -- 操作人
    changed_at TIMESTAMPTZ,
    field_name VARCHAR(64),
    old_value TEXT,
    new_value TEXT,
    change_reason VARCHAR(256)
);

-- 触发器记录变更
CREATE TRIGGER trg_user_info_audit
    AFTER UPDATE ON user_info
    FOR EACH ROW EXECUTE FUNCTION log_user_profile_changes();
```

**方案 2: 快照表（Slowly Changing Dimension）**
```sql
CREATE TABLE user_profile_snapshot (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    snapshot_at TIMESTAMPTZ,
    profile_data JSONB,        -- 完整快照
    version INT                -- 版本号
);
```

**查询历史:**
```java
// 查询用户最近 3 次资料变更
SELECT * FROM user_profile_snapshot 
WHERE user_id = 100 
ORDER BY snapshot_at DESC 
LIMIT 3;
```

### Q30: 如何设计一个灰度发布机制，让新用户和老用户使用不同的匹配策略？

**参考答案:**

**方案 1: 用户分桶**
```java
// 按 userId 哈希分桶，10% 的桶走新策略
public boolean isNewStrategy(Long userId) {
    int bucket = Math.abs(userId.hashCode() % 10);
    return bucket == 0;  // 10% 用户
}
```

**方案 2: 配置中心控制**
```yaml
# Nacos 配置
match:
  strategy:
    dh-recall:
      enabled: true
      min-user-age: 18
    new-strategy:
      enabled: true
      rollout-percentage: 10  # 10% 灰度
```

**方案 3: A/B 测试平台**
- 用户打标签（bucket_id）
- 匹配服务根据标签路由到不同策略
- 收集指标（转化率、留存）对比效果

---

## 附录：错误码速查

| 错误码 | 说明 | 常见场景 |
|--------|------|----------|
| 10001 | USER_NOT_FOUND | userId 不存在或已删除 |
| 10002 | USER_BANNED | 用户被封禁 |
| 10003 | USER_SUSPENDED | 用户被暂停 |
| 10004 | OPERATIONAL_BANNED | 运营手动封禁 |
| 10101 | AVATAR_EXT_INVALID | 头像格式不支持 |
| 10102 | AVATAR_SIZE_EXCEEDED | 头像超过 10MB |
| 10201 | INTEREST_PIC_LIMIT_EXCEEDED | 图片标签超过 9 个 |
| 10202 | INTEREST_TEXT_LIMIT_EXCEEDED | 文字标签超过 50 个 |
| 10301 | PHONE_INVALID | 手机号格式错误 |
| 10401 | BATCH_TOO_LARGE | 批量大小超过 200 |
| 10410 | ONBOARDING_GENDER_REQUIRED | onboarding 性别必填 |
| 10411 | ONBOARDING_BIRTHDAY_REQUIRED | onboarding 生日必填 |
