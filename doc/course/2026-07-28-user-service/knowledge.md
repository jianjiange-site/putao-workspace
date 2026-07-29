# user-service 核心知识点整理

> 纯技术知识文档，不涉及面试场景
>
> 配套：[`prd.md`](./prd.md)（业务功能）、[`interview-qa.md`](./interview-qa.md)（面试问答）

---

## 知识点一：分布式锁实现（Redisson + 竞态条件处理）

### 背景/场景

用户注册流程（ResolveOrCreate）需要处理并发请求，防止同一手机号/第三方ID/设备ID被重复创建用户。

### 问题分析

无锁情况下的竞态条件：
```
时序问题:
T1: 请求A 查询手机号 → 未命中
T2: 请求B 查询手机号 → 未命中
T3: 请求A 创建用户 → userId=100
T4: 请求B 创建用户 → userId=101
T5: 请求A 绑定手机 → 成功
T6: 请求B 绑定手机 → 唯一约束冲突 或 两个用户绑定同一手机
```

### 解决方案

使用 Redisson 分布式锁，按注册通道粒度加锁：

```java
// UserIdentityServiceImpl.resolveOrCreateByPhone()
String lockKey = RedisKey.lockRegisterPhone(phoneE164, appName);
RLock lock = redissonClient.getLock(lockKey);
boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);

if (!acquired) {
    throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED);
}
```

### 实现细节

**锁粒度设计**：
```java
// 三种注册通道，锁 key 不同
putao:user:lock:register:phone:{phone}:{app}
putao:user:lock:register:tp:{platform}:{thirdPartyId}:{app}
putao:user:lock:register:device:{deviceId}:{platform}:{app}
```

**超时参数**：
```java
private static final long LOCK_WAIT_SECONDS = 3;   // 等待超时
private static final long LOCK_LEASE_SECONDS = 30; // 持有超时
```

**竞态兜底**：即使加了锁，仍有窗口期导致 DuplicateKeyException：
```java
try {
    userLoginPhoneManager.insert(userId, phoneE164, appName);
} catch (DuplicateKeyException dup) {
    // 锁释放到插入之间的窗口期，另一个请求先插入了
    // 降级：重查已有记录
    UserLoginPhoneEntity existing = userLoginPhoneManager.findByPhoneAndApp(phoneE164, appName);
    if (existing != null) {
        userInfoManager.touchLastOpenAt(existing.getUserId());
        return ResolveOrCreateVO.builder()
                .userId(existing.getUserId())
                .pending(false)
                .newlyCreated(false)
                .build();
    }
    throw dup; // 真的出问题了
}
```

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 无锁 | 性能好 | 并发创建重复用户 |
| 悲观锁（数据库） | 简单 | 影响吞吐量 |
| **Redisson 分布式锁** | 粒度可控 | 需要 Redis |
| Redisson + 兜底重查 | 容错 | 代码稍复杂 |

---

## 知识点二：PostgreSQL EXCLUDE 约束（软删后重绑）

### 背景/场景

第三方账号绑定和设备绑定需要支持「解绑后重新绑定」，但不能有「幽灵占用」问题：
- 用户 A 绑定了 Google ID = abc123
- 用户 A 解绑（deleted = 1）
- 用户 B 现在想绑定同一个 Google ID = abc123

### 问题分析

普通唯一索引无法处理软删场景：
```sql
-- 软删后 deleted=1，普通唯一索引仍然阻止新插入
UNIQUE (platform, third_party_user_id, app_name)
```

### 解决方案

使用 PostgreSQL EXCLUDE 约束 + WHERE 条件：
```sql
CREATE TABLE user_third_party_registration (
    ...
    deleted SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_third_party_active
        EXCLUDE (platform WITH =, third_party_user_id WITH =, app_name WITH =)
        WHERE (deleted = 0)  -- 只对 deleted=0 的行生效
);
```

### 实现细节

**原理**：
- `WHERE (deleted = 0)` 让约束只作用于活跃记录
- 软删后 `deleted=1`，约束自动失效
- 新插入时约束重新生效

**需要扩展**：
```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
```

**设备表同样**：
```sql
CONSTRAINT uq_user_device_active
    EXCLUDE (device_id WITH =, platform WITH =, app_name WITH =)
    WHERE (deleted = 0)
```

### 权衡取舍

| 方案 | 优点 | 缺点 |
|------|------|------|
| 软删不占索引 | 需要定期清理数据 | 不支持重绑 |
| 唯一索引 + 手动清理 | 简单 | 运维复杂 |
| **EXCLUDE 约束** | 自动处理重绑 | 依赖 PG + 扩展 |

---

## 知识点三：Cache Aside 缓存模式

### 背景/场景

用户资料查询高频（GetProfile/BatchGetProfile），但写入相对较少。需要高性能读 + 数据一致性。

### 问题分析

两种经典缓存策略：
1. **Read-Through**：缓存不存在时，缓存层自动加载
2. **Write-Through**：写入时同步更新缓存
3. **Cache Aside**：业务手动控制

### 解决方案

**读流程**：
```java
public UserProfileVO getProfile(Long userId) {
    // 1. 先查缓存
    UserProfileVO cached = profileCacheManager.get(userId);
    if (cached != null) {
        return cached;
    }
    // 2. 缓存未命中，回源 DB
    UserInfoEntity entity = userInfoManager.findByUserId(userId);
    if (entity == null) {
        return null;
    }
    // 3. 回填缓存
    UserProfileVO vo = converter.toVO(entity);
    profileCacheManager.set(userId, vo);
    return vo;
}
```

**写流程（先写库再删缓存）**：
```java
public void updateProfile(Long userId, UpdateProfileDTO dto) {
    // 1. 先写库
    userInfoManager.updateProfile(userId, dto);
    // 2. 再删缓存
    profileCacheManager.evict(userId);
    profileCacheManager.evictBig(userId);
}
```

### 权衡取舍

**为什么选 Cache Aside 而不是 Write-Through**：

| 维度 | Cache Aside | Write-Through |
|------|------------|---------------|
| 写性能 | 较快（只写 DB） | 较慢（写 DB + 写缓存） |
| 数据一致性 | 最终一致 | 强一致 |
| 复杂度 | 适中 | 较高 |
| 适用场景 | 读多写少 | 写多读多 |

**为什么先写库再删缓存而不是反过来**：

- **安全性**：先写库保证数据持久化，即使删缓存失败数据仍在 DB
- **风险**：先删缓存再写库失败会导致「缓存空 + DB 旧」的问题

---

## 知识点四：三级封禁检查

### 背景/场景

用户登录/操作前需要检查是否被封禁，封禁来源有两种：
1. **运营级封禁**：Redis Set，临时性、批量操作
2. **DB 级封禁**：regulation_status 字段，正式封禁

### 问题分析

单点查询无法覆盖所有场景：
- 只查 DB：运营操作需要修改数据库，慢
- 只查 Redis：Redis 不可用时失效

### 解决方案

```java
public BanStatusVO checkBan(Long userId) {
    // 1. Redis 短缓存 (TTL 5min)
    BanReason cached = userBanManager.queryBanReason(userId);
    if (cached != null) {
        return toBanStatusVO(cached);
    }

    // 2. Redis 运营封禁 Set
    if (userBanManager.isOperationalBanned(userId)) {
        BanReason reason = BanReason.builder()
                .status(BanStatus.OPERATIONAL_BANNED)
                .reason("Operational ban")
                .build();
        profileCacheManager.setBanCache(userId, reason);
        return toBanStatusVO(reason);
    }

    // 3. DB regulation_status
    UserInfoEntity entity = userInfoManager.findByUserId(userId);
    BanReason reason = userBanManager.reasonFromRegulationStatus(
            entity.getRegulationStatus());

    // 回填缓存
    profileCacheManager.setBanCache(userId, reason);
    return toBanStatusVO(reason);
}
```

### 权衡取舍

| 检查层级 | TTL | 适用场景 | 优点 |
|----------|-----|----------|------|
| Redis 短缓存 | 5min | 常规查询 | 极快，防抖 |
| Redis Set | 无 | 运营封禁 | 批量操作快 |
| DB regulation_status | 无 | 最终数据源 | 可靠 |

---

## 知识点五：Snowflake ID 生成

### 背景/场景

user-service 需要生成全局唯一的业务主键 user_id，不能用自增 ID（分库分表、跨库查询）。

### 问题分析

- 数据库自增 ID：单库没问题，分库分表时 ID 会冲突
- UUID：太长了（36 字符），无序
- Redis INCR：依赖 Redis 高可用

### 解决方案

Twitter Snowflake 算法：
```
1 bit: 固定为 0
41 bits: 时间戳（毫秒）
10 bits: 机器 ID（workerId + datacenterId）
12 bits: 序列号（每毫秒自增）
```

### 实现细节

```java
@Configuration
public class SnowflakeIdConfig {
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${snowflake.worker-id:1}") long workerId,
            @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        return new SnowflakeIdGenerator(workerId, datacenterId);
    }
}

// 生成 ID
public class SnowflakeIdGenerator {
    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public synchronized long nextId() {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 0xFFF; // 12 位溢出回绕
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - START_EPOCH) << 22)
                | (datacenterId << 17)
                | (workerId << 12)
                | sequence;
    }
}
```

### 权衡取舍

| 方案 | ID 长度 | 有序性 | 依赖 | 适用场景 |
|------|---------|--------|------|----------|
| 数据库自增 | 32bit | 有序 | DB | 单库 |
| UUID | 128bit | 无序 | 无 | 分散 |
| Redis INCR | 64bit | 有序 | Redis | 简单 |
| **Snowflake** | 64bit | 有序 | 无 | 分布式 |

---

## 知识点六：gRPC Context 传值

### 背景/场景

gRPC 请求中的 userId、traceId 等上下文信息需要在整个调用链中传递，但业务代码不应该直接依赖 metadata。

### 问题分析

- HTTP 请求可以通过 ThreadLocal 传递用户信息
- gRPC 使用虚拟线程，ThreadLocal 行为可能不符合预期
- gRPC 支持异步流，ThreadLocal 无法跨越异步边界

### 解决方案

使用 gRPC Context：
```java
// 1. Interceptor 提取 metadata 并注入 Context
public class UserIdContextInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(...) {
        // 提取 metadata
        String userIdStr = headers.get(USER_ID_METADATA_KEY);
        Long userId = userIdStr != null ? Long.parseLong(userIdStr) : null;

        String traceId = headers.get(TRACE_ID_METADATA_KEY);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }

        // 注入 Context
        Context context = Context.current()
                .withValue(USER_ID_CONTEXT_KEY, userId)
                .withValue(TRACE_ID_CONTEXT_KEY, traceId);

        return Contexts.interceptCall(context, call, headers, next);
    }
}

// 2. 业务代码使用
public class UserContext {
    public static Long callerUserId() {
        return USER_ID_CONTEXT_KEY.get();
    }

    public static String traceId() {
        return TRACE_ID_CONTEXT_KEY.get();
    }
}

// 3. 配置启用
@Configuration
public class GrpcServerConfig {
    @Bean
    public ServerInterceptor userIdContextInterceptor() {
        return new UserIdContextInterceptor();
    }

    @Bean
    public ServerInterceptor tracingServerInterceptor() {
        return new TracingServerInterceptor();
    }
}
```

### 权衡取舍

| 方案 | 虚拟线程兼容 | 异步支持 | 标准性 |
|------|-------------|----------|--------|
| ThreadLocal | ❌ | ❌ | 非标准 |
| **gRPC Context** | ✅ | ✅ | 官方推荐 |
| 请求对象传递 | ✅ | ✅ | 侵入性强 |

---

## 知识点七：Presigned URL 对象存储直传

### 背景/场景

用户上传头像，如果不走服务端中转，需要让客户端直接上传到对象存储（MinIO）。

### 问题分析

传统服务端中转：
```
[App] → [Server] → [MinIO]
问题: 服务端成为瓶颈，带宽浪费
```

### 解决方案

Presigned URL 模式：
```java
// 1. 生成 presigned PUT URL
public PresignResultVO presignAvatarUpload(Long userId, String ext, long sizeBytes) {
    // 校验扩展名
    if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
        throw new BizException(ErrorCode.AVATAR_EXT_INVALID);
    }

    // 校验大小
    if (sizeBytes > MAX_AVATAR_SIZE) {
        throw new BizException(ErrorCode.AVATAR_SIZE_EXCEEDED);
    }

    // 生成 objectKey
    String objectKey = String.format("avatar/%d/%s.%s",
            userId, UUID.randomUUID(), ext);

    // 签 presigned URL (TTL 5分钟)
    String presignedUrl = minioClient.getPresignedObjectPutUrl(
            BUCKET_NAME, objectKey, 5 * 60);

    return PresignResultVO.builder()
            .presignedUrl(presignedUrl)
            .objectKey(objectKey)
            .build();
}

// 2. 确认上传完成
public UserProfileVO confirmAvatarUpload(Long userId, String objectKey) {
    // 校验前缀防止越权
    String prefix = "avatar/" + userId + "/";
    if (!objectKey.startsWith(prefix)) {
        throw new BizException(ErrorCode.AVATAR_OBJECT_KEY_MISMATCH);
    }

    // 更新用户头像
    userInfoManager.updateAvatarKey(userId, objectKey);

    // 清缓存
    profileCacheManager.evict(userId);
    profileCacheManager.evictBig(userId);

    // 返回最新资料
    return getProfile(userId);
}
```

### 权衡取舍

| 方案 | 服务端负载 | 延迟 | 复杂度 |
|------|-----------|------|--------|
| 服务端中转 | 高 | 高 | 简单 |
| **Presigned URL** | 低 | 低 | 适中 |
| 分片上传 | 中 | 中 | 高 |

---

## 知识点八：MapStruct 对象转换

### 背景/场景

Entity → VO 的转换频繁，需要类型安全、高性能的转换方式。

### 问题分析

- BeanUtils/PropertyUtils：运行时反射，性能差
- BeanCopier：运行时字节码生成，仍有反射开销
- 手写转换：代码量大，不易维护

### 解决方案

MapStruct：编译时生成转换代码
```java
// 1. 定义转换器
@Mapper(componentModel = "spring")
public interface UserProfileConverter {
    UserProfileConverter INSTANCE = Mappers.getMapper(UserProfileConverter.class);

    UserProfileVO toVO(UserInfoEntity entity);
    UserProfileVO toVOBig(UserInfoEntity entity);
}

// 2. 编译生成（等价于手写）
public class UserProfileConverterImpl implements UserProfileConverter {
    @Override
    public UserProfileVO toVO(UserInfoEntity entity) {
        if (entity == null) return null;
        UserProfileVO vo = new UserProfileVO();
        vo.setUserId(entity.getUserId());
        vo.setNickname(entity.getNickname());
        // ... 编译时生成
        return vo;
    }
}
```

### 权衡取舍

| 方案 | 性能 | 类型安全 | 配置性 |
|------|------|----------|--------|
| BeanUtils | 慢（反射） | 运行时错误 | 有限 |
| BeanCopier | 中（运行时生成） | 运行时错误 | 有限 |
| **MapStruct** | 快（编译生成） | 编译时错误 | 强 |
| 手写 | 快 | 编译时错误 | N/A |

---

## 附录：Redis Key 规范速查

| Key Pattern | 数据结构 | TTL | 用途 |
|-------------|---------|-----|------|
| `putao:user:profile:{userId}` | String (JSON) | 24h | 主资料缓存 |
| `putao:user:profile:big:{userId}` | String (JSON) | 24h | 大字段缓存 |
| `putao:user:interest:{userId}` | String (JSON) | 7d | 兴趣标签缓存 |
| `putao:user:ban:status:{userId}` | String | 5min | 封禁状态短缓存 |
| `putao:user:ban:thirdparty-set` | Set | 永久 | 运营级封禁集合 |
| `putao:user:lock:register:phone:{phone}:{app}` | String (NX) | 30s | 手机注册锁 |
| `putao:user:lock:register:tp:{platform}:{id}:{app}` | String (NX) | 30s | 第三方注册锁 |
| `putao:user:lock:register:device:{device}:{platform}:{app}` | String (NX) | 30s | 设备注册锁 |

---

## 附录：gRPC Status 映射

| 业务异常 | gRPC Status | 说明 |
|----------|-------------|------|
| UserNotFoundException | NOT_FOUND (404) | 资源不存在 |
| UserBannedException | PERMISSION_DENIED (7) | 无权限 |
| BizException (通用) | FAILED_PRECONDITION (9) | 前置条件不满足 |
| 其他 Exception | INTERNAL (13) | 服务器内部错误 |
