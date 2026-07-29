# user-service PRD - 用户服务技术文档

> 约会交友 App 后端微服务 - 用户身份解析 + 用户资料域
>
> **技术栈**: Java 21 / Spring Boot 3.3.5 / PostgreSQL / Redis / gRPC / Nacos
>
> **服务定位**: 承担用户的「我是谁」「我长什么样」「我喜欢什么」的全部持久化能力

---

## 1. 服务概述

### 1.1 核心职责

| 职责 | 说明 |
|------|------|
| **身份解析 (Identity)** | 三通道用户识别：手机号 / 第三方授权 / 设备 ID |
| **用户资料 (Profile)** | 主资料 CRUD：昵称 / 年龄 / 性别 / 城市 / 简介 / 职业等 |
| **兴趣标签 (Interest)** | 用户兴趣标签全量替换 |
| **头像上传 (Avatar)** | Presigned PUT 直传对象存储 |
| **封禁查询 (Ban)** | 用户封禁状态三级检查 |

### 1.2 技术架构图

```
[App] ──gRPC──▶ [mobile-gateway] ──gRPC──▶ [user-service]
                                                      │
                         ┌────────────────────────────┼────────────────────────────┐
                         │                            │                            │
                         ▼                            ▼                            ▼
                   PostgreSQL                     Redis                    对象存储(MinIO)
                   (5 张表)                   (资料/兴趣/锁/封禁)              (头像)
                         │                                                       │
                         └────────────────────────────┘
```

### 1.3 服务端口

| 端口 | 用途 |
|------|------|
| HTTP `18081` | Actuator 健康检查 |
| gRPC `19081` | 业务接口，对外暴露 |

---

## 2. 数据库设计

### 2.1 核心表结构

#### user_info — 用户主资料

```sql
CREATE TABLE user_info (
    id                  BIGSERIAL PRIMARY KEY,  -- 内部物理主键，不对外暴露
    user_id             BIGINT UNIQUE NOT NULL,  -- 雪花 ID，业务主键
    nickname            VARCHAR(64) NOT NULL DEFAULT '',
    age                 SMALLINT,                 -- 0=未填
    gender              SMALLINT NOT NULL DEFAULT 0,  -- 0=未知/1=男/2=女
    birthday            DATE,                     -- onboarding 一次性写入
    preferred_location  VARCHAR(128),             -- 城市文本
    bio                 VARCHAR(500),
    profession          VARCHAR(128),             -- UI 叫 Occupation
    education           VARCHAR(128),
    height              SMALLINT,                 -- cm
    email               VARCHAR(128),
    phone_number        VARCHAR(32),              -- 联系方式，非登录凭证
    city_id             BIGINT,
    lat                 NUMERIC(10, 6),          -- 地理位置
    lng                 NUMERIC(10, 6),
    beauty_score        SMALLINT,                 -- 颜值评分 0-100
    race                SMALLINT,                 -- 1=Asian/2=Black/3=Latino/4=White/5=MiddleEast/6=Indian
    regulation_status   SMALLINT NOT NULL DEFAULT 0,  -- 0=正常/2=Banned/5=Suspended
    pending             SMALLINT NOT NULL DEFAULT 1,  -- 1=placeholder/0=已完成
    user_type           SMALLINT NOT NULL DEFAULT 1,  -- 1=BH(真人)/2=DH(数字人)
    last_open_at        TIMESTAMPTZ,              -- 每次 ResolveOrCreate 更新
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT NOT NULL DEFAULT 0  -- 逻辑删除
);
```

#### user_login_phone — 手机号绑定

```sql
CREATE TABLE user_login_phone (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    phone_e164  VARCHAR(32) NOT NULL,  -- E.164 格式: +8613800138000
    app_name    VARCHAR(32) NOT NULL,  -- 应用名: vibe
    verified_at TIMESTAMPTZ,           -- 首次绑定时间
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_login_phone_e164_app UNIQUE (phone_e164, app_name)
);
```

#### user_third_party_registration — 第三方账号绑定

```sql
CREATE TABLE user_third_party_registration (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    platform             SMALLINT NOT NULL,  -- 1=Google/2=Facebook/3=Apple
    third_party_user_id  VARCHAR(128) NOT NULL,
    email                VARCHAR(128),       -- Google 登录带回
    app_name             VARCHAR(32) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              SMALLINT NOT NULL DEFAULT 0,
    -- 软删后允许重绑的 EXCLUDE 约束
    CONSTRAINT uq_user_third_party_active
        EXCLUDE (platform WITH =, third_party_user_id WITH =, app_name WITH =)
        WHERE (deleted = 0)
);
```

#### user_device_registration — 设备绑定（快速登录）

```sql
CREATE TABLE user_device_registration (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    device_id   VARCHAR(128) NOT NULL,  -- iOS IDFV/Android SSAID/Web cookie
    platform    SMALLINT NOT NULL,       -- 1=iOS/2=Android/3=Web
    app_name    VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_device_active
        EXCLUDE (device_id WITH =, platform WITH =, app_name WITH =)
        WHERE (deleted = 0)
);
```

#### user_interest — 兴趣标签

```sql
CREATE TABLE user_interest (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES user_info(user_id) ON DELETE CASCADE,
    tab_key     VARCHAR(32) NOT NULL,  -- 兴趣大类: music/sport
    tag_key     VARCHAR(32) NOT NULL,  -- 具体标签: rock/basketball
    pic_key     VARCHAR(128),          -- 图片标签的 object_key
    sort_order  SMALLINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_interest_tab_tag UNIQUE (user_id, tab_key, tag_key)
);
```

### 2.2 数据库约束设计亮点

1. **EXCLUDE 约束支持软删后重绑**: 第三方和设备表使用 PostgreSQL 的 `btree_gist` 扩展实现 `EXCLUDE` 约束，允许同一标识在 `deleted=0` 时唯一，软删后可重新绑定
2. **物理主键 vs 业务主键**: `id` 为内部物理主键（自增），`user_id` 为雪花 ID 业务主键对外暴露
3. **逻辑删除**: 所有表使用 `deleted` 字段标记逻辑删除
4. **触发器维护**: `updated_at` 由数据库触发器自动维护

---

## 3. gRPC 接口清单

### 3.1 Identity 域接口

| RPC | 用途 | 入参 | 返回 |
|-----|------|------|------|
| `ResolveOrCreateByPhone` | 手机号登录解析/创建 | phoneE164, appName | userId, pending, newlyCreated |
| `ResolveOrCreateByThirdParty` | 第三方登录解析/创建 | platform, thirdPartyUserId, appName, googleEmail? | userId, pending, newlyCreated |
| `ResolveOrCreateByDevice` | 设备快速登录解析/创建 | deviceId, platform, appName | userId, pending, newlyCreated |
| `CheckBan` | 查询封禁状态 | userId | banned, reason, message |

### 3.2 Profile 域接口

| RPC | 用途 | 入参 | 返回 |
|-----|------|------|------|
| `GetProfile` | 单用户读取 | userId | UserProfileProto |
| `BatchGetProfile` | 批量读取(≤200) | userIds, includeInterests | BatchGetProfilesResponse |
| `UpdateProfile` | 资料编辑(7字段) | nickname/age/location/bio/occupation/education/height | UserProfileProto |
| `UpsertOnboarding` | Onboarding 一次性写入 | 全量资料+兴趣 | OnboardingResponse |
| `ReplaceUserInterests` | 兴趣全量替换 | interests[] | count |
| `PresignAvatarUpload` | 头像预签名 | ext, sizeBytes | presignedUrl, objectKey |
| `ConfirmAvatarUpload` | 头像上传确认 | objectKey | UserProfileProto |

### 3.3 Match Service 召回接口

| RPC | 用途 | 入参 | 返回 |
|-----|------|------|------|
| `ListDhCandidates` | DH 数字人召回 | targetGender, ageRange, beautyRange, races, excludeIds, limit | DhCandidate[] |
| `NearbyUsers` | 附近真人召回 | userId, radiusKm, ageRange, beautyRange, races, lastActiveDays, limit | BhCandidate[] |

---

## 4. 核心业务流程

### 4.1 ResolveOrCreateByPhone 流程

```
1. libphonenumber 校验 + E.164 规范化
2. Redisson 加锁: lock:user:register:phone:{phoneE164}:{appName}, TTL 30s
3. 查询 user_login_phone:
   - 命中 → touchLastOpenAt(userId) → 返回 userId
   - 未命中 → 创建 placeholder + 插入绑定 → 返回 userId(pending=true)
4. 解锁
```

### 4.2 Placeholder 用户创建

新建用户初始状态：
- `nickname = "User_${userId}"`
- `gender = 0` (未知)
- `pending = 1` (待 onboarding)
- `regulation_status = 0` (正常)
- `user_type = 1` (BH 真人)
- `last_open_at = NOW()`

### 4.3 头像上传流程

```
[App] --POST /presign--> [gateway] --gRPC--> [user-service]
                                                  │ 生成 objectKey
                                                  │ "avatar/{userId}/{uuid}.{ext}"
                                                  │ 签 presigned PUT URL (5min TTL)
                                                  ▼
                                          {presignedUrl, objectKey}

[App] --PUT presignedUrl + 文件--> [对象存储(MinIO)]

[App] --POST /confirm--> [gateway] --gRPC--> [user-service]
                                                  │ 校验 objectKey 前缀
                                                  │ 清缓存
                                                  ▼
                                          {UserProfileProto}
```

---

## 5. Redis 缓存设计

### 5.1 Key 规范

所有 key 格式: `putao:user:<domain>:<id>`

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `putao:user:profile:{userId}` | String(JSON) | 24h | 主资料缓存 |
| `putao:user:profile:big:{userId}` | String(JSON) | 24h | 大字段(头像)缓存 |
| `putao:user:interest:{userId}` | String(JSON) | 7d | 兴趣标签缓存 |
| `putao:user:ban:status:{userId}` | String | 5min | 封禁状态短缓存 |
| `putao:user:ban:thirdparty-set` | Set | 永久 | 运营级封禁集合 |
| `putao:user:lock:register:phone:{phone}:{app}` | String(NX) | 30s | 手机注册锁 |
| `putao:user:lock:register:tp:{platform}:{id}:{app}` | String(NX) | 30s | 第三方注册锁 |
| `putao:user:lock:register:device:{device}:{platform}:{app}` | String(NX) | 30s | 设备注册锁 |

### 5.2 缓存策略

- **一致性策略**: 先写库，再删缓存（Cache Aside）
- **批量读优化**: BatchGetProfile 一次 IN 查询 + 批量回填缓存
- **禁止双写**: 不同时写库和缓存，避免数据不一致

---

## 6. 封禁与监管

### 6.1 三级封禁检查

```
CheckBan(userId):
  1. 查 Redis 短缓存 → 命中返回
  2. 查 Redis 运营封禁 Set → isMember → 命中返回 OPERATIONAL
  3. 查 DB regulation_status:
     - 2 = BANNED → 用户被封禁
     - 5 = SUSPENDED → 用户被暂停
     - 其他 = 正常
  4. 回填缓存 → 返回结果
```

### 6.2 封禁来源

| 来源 | 检查方式 | 状态值 |
|------|----------|--------|
| 用户级封禁 | DB regulation_status | 2=Banned / 5=Suspended |
| 运营级封禁 | Redis Set | OPERATIONAL |

---

## 7. 错误码设计

错误码段: **10001-10499**

| 段 | 错误码 | 说明 |
|----|--------|------|
| 100xx | USER_NOT_FOUND | 用户不存在 |
| | USER_BANNED | 用户被封禁 |
| | USER_SUSPENDED | 用户被暂停 |
| | OPERATIONAL_BANNED | 运营封禁 |
| 101xx | AVATAR_EXT_INVALID | 头像扩展名无效 |
| | AVATAR_SIZE_EXCEEDED | 头像超过 10MB |
| | AVATAR_OBJECT_KEY_MISMATCH | objectKey 前缀不匹配 |
| 102xx | INTEREST_PIC_LIMIT_EXCEEDED | 图片标签超过 9 个 |
| | INTEREST_TEXT_LIMIT_EXCEEDED | 文字标签超过 50 个 |
| 103xx | PHONE_INVALID | 手机号无效 |
| 104xx | BATCH_TOO_LARGE | 批量大小超限 |
| | ONBOARDING_GENDER_REQUIRED | onboarding 性别必填 |
| | ONBOARDING_BIRTHDAY_REQUIRED | onboarding 生日必填 |

---

## 8. 配置管理

### 8.1 Nacos 配置

| 配置项 | 来源 | 说明 |
|--------|------|------|
| Database URL/Username/Password | Nacos | Nacos 上 dating-user-service-dev.yaml |
| Redis Host/Port/Password | Nacos | Redis 6380 |
| 雪花 ID Worker ID | 环境变量 | snowflake.worker-id |
| 缓存 Key 前缀 | Nacos | app.cache.key-prefix |

### 8.2 启动检查清单

1. spring.cloud.nacos.config.server-addr 写了没？
2. Nacos 上 dating-user-service-dev.yaml 是否存在？
3. PG 库 putao_dating_dev 的 user schema 是否已建？
4. Redis 端口 6380、密码、database=1 是否正确？

---

## 9. 与其他服务的关系

### 9.1 调用方

| 服务 | 调用的接口 | 场景 |
|------|------------|------|
| mobile-gateway | ResolveOrCreate* | 登录流程 |
| mobile-gateway | CheckBan | 登录/会话校验 |
| mobile-gateway | GetProfile/BatchGetProfile | BFF 聚合 |
| mobile-gateway | UpdateProfile/UpsertOnboarding | 资料编辑 |
| post-service | GetUserProfile | 帖子作者信息 |
| match-service | ListDhCandidates/NearbyUsers | 推荐召回 |

### 9.2 不做的边界

- 不签发/验证 JWT
- 不做 BFF 聚合
- 不接 IM SDK
- 不做关系链（关注/拉黑）
- 不做支付/钱包

---

## 10. 目录结构

```
com.dating.user/
├── UserApplication.java              # 启动入口
├── grpc/
│   ├── UserGrpcService.java          # gRPC 入口（Mixin 宿主）
│   ├── UserIdentityGrpcImpl.java    # Identity 域实现
│   └── UserProfileGrpcImpl.java     # Profile 域实现
├── service/
│   ├── UserIdentityService.java     # 身份解析服务接口
│   ├── impl/UserIdentityServiceImpl.java
│   ├── UserProfileService.java
│   ├── impl/UserProfileServiceImpl.java
│   ├── UserInterestService.java
│   ├── impl/UserInterestServiceImpl.java
│   ├── UserAvatarService.java
│   ├── impl/UserAvatarServiceImpl.java
│   ├── UserBanService.java
│   └── impl/UserBanServiceImpl.java
├── manager/
│   ├── UserInfoManager.java         # 主资料读写
│   ├── UserLoginPhoneManager.java   # 手机绑定
│   ├── UserThirdPartyManager.java   # 第三方绑定
│   ├── UserDeviceManager.java        # 设备绑定
│   ├── UserInterestManager.java      # 兴趣标签
│   ├── UserBanManager.java           # 封禁状态
│   └── UserProfileCacheManager.java  # 缓存管理
├── mapper/
│   ├── UserInfoMapper.java
│   ├── UserLoginPhoneMapper.java
│   ├── UserThirdPartyRegistrationMapper.java
│   ├── UserDeviceRegistrationMapper.java
│   └── UserInterestMapper.java
├── entity/
│   ├── UserInfoEntity.java
│   ├── UserLoginPhoneEntity.java
│   ├── UserThirdPartyRegistrationEntity.java
│   ├── UserDeviceRegistrationEntity.java
│   └── UserInterestEntity.java
├── dto/
│   ├── ResolveOrCreateDTO.java
│   ├── UpdateProfileDTO.java
│   ├── OnboardingDTO.java
│   ├── InterestDTO.java
│   ├── PresignAvatarDTO.java
│   └── ConfirmAvatarDTO.java
├── vo/
│   ├── UserProfileVO.java
│   ├── AvatarVO.java
│   ├── UserInterestVO.java
│   ├── BanStatusVO.java
│   ├── ResolveOrCreateVO.java
│   └── PresignResultVO.java
├── converter/
│   ├── UserProfileConverter.java     # Entity → VO (MapStruct)
│   └── InterestConverter.java
├── config/
│   ├── RedisKeyPrefixConfig.java    # Key 前缀注入
│   ├── SnowflakeIdConfig.java       # 雪花 ID 生成器
│   ├── PhoneNumberConfig.java        # libphonenumber 单例
│   ├── UserIdContextInterceptor.java # gRPC 拦截器
│   ├── UserContext.java              # 用户上下文
│   ├── DateTimeMetaObjectHandler.java
│   └── MybatisPlusConfig.java
├── constant/
│   ├── ErrorCode.java               # 错误码枚举
│   ├── RedisKey.java                 # Redis Key 模板
│   ├── Platform.java                 # 设备平台枚举
│   ├── ThirdPartyPlatform.java       # 第三方平台枚举
│   └── AppName.java                 # App 名称枚举
└── exception/
    ├── BizException.java             # 业务异常基类
    ├── UserNotFoundException.java
    ├── UserBannedException.java
    └── GrpcExceptionAdvice.java     # gRPC 全局异常处理
```

---

## 11. 技术亮点

### 11.1 分布式锁防并发

三种 ResolveOrCreate 通道都使用 Redisson 分布式锁：
- 锁粒度: 按 phone/thirdParty/deviceId + appName 组合
- 锁超时: wait 3s / lease 30s
- 并发插入: DuplicateKeyException 兜底重查

### 11.2 Cache Aside 缓存模式

- 读: 缓存优先，未命中回源 DB 并回填
- 写: 先写库，再删缓存
- 批量: 一次 IN 查询，避免 N+1

### 11.3 gRPC Context 传值

通过 `UserIdContextInterceptor` 从 metadata 提取:
- `x-user-id`: 调用方用户 ID
- `x-device-id`: 调用方设备 ID
- `x-trace-id`: 链路追踪 ID（缺失生成 UUID）

### 11.4 对象存储直传

头像不走服务端转发流量：
- 服务端签 presigned PUT URL
- 客户端直接 PUT 到对象存储
- 服务端只存 object_key

### 11.5 雪花 ID 业务主键

- `user_id` 使用 Twitter Snowflake 算法
- workerId/datacenterId 可配置
- 64 位唯一 ID，跨库稳定

---

## 12. 部署信息

### 12.1 容器配置

```dockerfile
FROM eclipse-temurin:21-jre-alpine
EXPOSE 18081 19081
HEALTHCHECK --interval=30s CMD wget --no-verbose --tries=1 --spider http://localhost:18081/actuator/health
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "app.jar"]
```

### 12.2 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| USER_SERVICE_PORT | 18081 | HTTP 端口 |
| USER_SERVICE_GRPC_PORT | 19081 | gRPC 端口 |
| SPRING_PROFILES_ACTIVE | dev | 环境 |
| NACOS_SERVER_ADDR | 38.76.188.242:8848 | Nacos 地址 |
| NACOS_NAMESPACE | putao-dating-dev | Nacos 命名空间 |
| SNOWFLAKE_WORKER_ID | 1 | 雪花 Worker ID |
