# User Service 特殊场景设计

> 本文只讨论 user-service 中具有系统设计价值的场景。普通功能的逐步代码链路见《user-service-业务流程详解》，产品能力见《user-service-业务清单》。
>
> 事实基线：`dating-server/user-service` 当前源码、`proto/user/user.proto`、Flyway V1 迁移和仓库内调用方。设计稿只用于解释意图，凡与代码冲突均以代码为准。

## 一、场景总览

| 场景 | 核心冲突 | 当前方案 | 当前状态 |
|---|---|---|---|
| 多入口身份解析 | 同一凭证并发注册与重复用户 | Redisson 细粒度锁 + 数据库唯一约束 + 冲突后重查 | 已实现，但创建用户与绑定凭证不原子 |
| 资料聚合缓存 | 高频读取、低频写入与多块数据独立变化 | 主资料 24h、兴趣 7d 的 Cache Aside | 已实现，但删除缓存发生在事务提交前 |
| 兴趣全量替换 | 前端提交完整选择集，服务端要保持顺序和原子性 | 事务内 DELETE + 逐行 INSERT | 已实现，存在并发覆盖语义 |
| 多来源封禁 | 数据库正式状态与 Redis 运营集合并存 | 短缓存 → 运营集合 → DB | 部分实现，故障降级和 reason 映射有缺口 |
| 头像直传 | 避免服务端承载图片流量，同时保证归属和落库 | 两阶段 presign/confirm | 仅占位，未真实签名、验对象或持久化头像 |
| 契约先于实现 | match/im 已依赖用户类型与召回能力 | Proto 预留、调用方降级 | 服务端 3 个 RPC 未覆写，实际返回 UNIMPLEMENTED |
| 分布式业务 ID | 跨服务使用稳定 userId | 进程内 Snowflake | 已实现，实例编号分配和时钟回拨需运维保证 |

## 二、多入口身份解析：锁不是事务的替代品

### 2.1 业务背景和数据特点

用户可以用手机号、第三方账号或设备标识进入系统。每种外部凭证在一个 App 维度内只能指向一个活动用户，但一次请求要写两类数据：

1. `user_info` 中的 placeholder 用户；
2. 对应的手机号、第三方或设备绑定表。

并发请求可能来自重试、双击、多个网关实例或客户端超时后的再次提交。

### 2.2 方案比较

| 方案 | 优点 | 代价 |
|---|---|---|
| 只查后写 | 代码最少 | 明显存在并发重复创建 |
| 只靠数据库唯一约束 | 最终不会出现重复活动绑定 | 冲突请求体验差，仍可能产生孤儿 placeholder |
| 只靠分布式锁 | 能串行化同一凭证 | Redis 故障即不可用，且锁配置错误时仍可能穿透 |
| 锁 + 唯一约束 + 原子事务 | 应用层减少冲突，DB 兜底，写入一致 | 实现和故障处理更复杂 |

当前代码采用前三项的组合，但缺少最后一项中的“原子事务”。

### 2.3 当前实现

锁粒度由外部凭证和 App 共同决定：

```java
putao:user:lock:register:phone:{phoneE164}:{appName}
putao:user:lock:register:tp:{platform}:{thirdPartyUserId}:{appName}
putao:user:lock:register:device:{deviceId}:{platform}:{appName}
```

`UserIdentityServiceImpl.tryLock` 使用固定参数：

```java
lock.tryLock(3, 30, TimeUnit.SECONDS);
```

- 最多等待 3 秒；
- 固定租期 30 秒，未使用看门狗续期；
- 获取失败直接返回业务异常；
- Redisson 自身不可用时没有降级，身份解析失败关闭。

拿锁后再次查询绑定，未命中才创建 placeholder 并插入绑定。数据库用三类约束兜底：

```sql
UNIQUE (phone_e164, app_name)

EXCLUDE (platform WITH =, third_party_user_id WITH =, app_name WITH =)
WHERE (deleted = 0)

EXCLUDE (device_id WITH =, platform WITH =, app_name WITH =)
WHERE (deleted = 0)
```

手机号约束是普通物理唯一约束，因此即使未来把手机号记录标为 `deleted=1`，同一手机号和 App 仍不能重新绑定；第三方和设备约束则允许软删除后重绑。三者语义并不完全一致。

### 2.4 真实事务边界

`createPlaceholder()` 虽然标了 `@Transactional`，但它是同一个 Bean 内部的自调用：

```java
Long userId = createPlaceholder();
userLoginPhoneManager.insert(userId, phoneE164, appName);
```

Spring 默认代理无法拦截这种 self-invocation，而且外层三条 `resolveOrCreate*` 方法也没有事务。因此当前事实是：

1. placeholder 插入通常独立提交；
2. 凭证绑定随后独立提交；
3. 若绑定失败，placeholder 不会自动回滚；
4. 捕获 `DuplicateKeyException` 后可以返回并发赢家，但输家的 placeholder 可能残留为 `pending=1`。

这也是为什么不能把“有分布式锁”描述成“注册链路已经强一致”。

### 2.5 幂等、失败与演进

- 同一凭证的重复请求通常返回同一 userId，业务上接近幂等。
- 数据库唯一约束是最后防线；分布式锁主要减少冲突，不是正确性的唯一来源。
- 现有绑定命中时会更新 `last_open_at`，但 `UserInfoMapper.touchLastOpenAt` 使用了 `@Select` 注解承载 `UPDATE` SQL，存在运行时执行失败风险，需要真实 PG 集成测试确认。
- 手机号会经 libphonenumber 二次校验并格式化为 E.164；第三方 ID 和设备 ID 只检查非空。
- gRPC 层把 `AppName` 的枚举名直接写入 DTO，因此实际存储值是 `APP_VIBE`，并未使用常量类中定义的 `vibe` 映射。

演进时应把“创建 placeholder + 插入绑定”移入一个可被 Spring 代理的独立事务方法；锁仍保留作削峰，唯一约束继续作最终防线。若要清理历史孤儿用户，应先定义“无任何活动绑定且长期 pending”的判定规则，再用可审计任务处理。

## 三、资料聚合缓存：拆分缓存降低耦合，也扩大一致性边界

### 3.1 为什么拆成主资料和兴趣

资料读取会组合 `user_info` 与 `user_interest`，但两者变化频率不同。当前使用：

| Key | TTL | 内容 | 真实读写状态 |
|---|---:|---|---|
| `putao:user:profile:{userId}` | 24h | 主资料 JSON，不含兴趣 | 已读、已写 |
| `putao:user:interest:{userId}` | 7d | 兴趣实体列表 JSON | 单用户读取使用 |
| `putao:user:profile:big:{userId}` | 24h | 预留大字段 | 有读写方法，但业务链路未使用 |
| `putao:user:profile:batch:{tag}` | 未定义 | 预留批次键 | 只有 key 工厂，无调用 |

拆分的好处是兴趣变化不必重建整份主资料；代价是一次完整资料读取最多需要两个缓存或数据库来源，必须接受组合时刻不完全一致。

### 3.2 单用户读取

`UserProfileServiceImpl.getProfile` 的实际顺序是：

1. `GET profile key`；
2. 主资料 miss 时查 `user_info`，序列化回填 24h；
3. `GET interest key`；
4. 兴趣 miss 时查 `user_interest`，回填 7d；
5. 组装后返回。

主资料缓存只存基础 VO，兴趣在每次返回前重新挂载。没有空值缓存和互斥重建，因此热点不存在用户或热点缓存同时过期时，可能发生缓存穿透或击穿。

### 3.3 批量读取不是 MGET

批量读取会逐个执行 Redis `GET` 收集 miss，而不是一次 `MGET`。miss 用户再通过一次 `WHERE user_id IN (...)` 查询，随后逐个 `SET` 回填。若需要兴趣，则对全部去重后的 userId 再执行一次兴趣表 IN 查询，完全绕过兴趣缓存。

这种实现避免了主表 N+1，但仍有最多 200 次 Redis 往返和最多 200 次回填写。结果会：

- 对输入去重；
- 按首次出现顺序返回；
- 静默跳过不存在或已逻辑删除的用户；
- 不返回缺失项占位。

### 3.4 写后删缓存的真实时序

资料编辑、onboarding 和兴趣替换都采用“先写 DB，再删缓存”，但删除动作发生在 Spring 事务提交之前：

```text
事务内写 DB
→ 删除 Redis
→ 某些流程立刻 getProfile 并重新写 Redis
→ 方法返回
→ 事务代理提交 DB
```

这会产生两个重要窗口：

1. 删除缓存后、事务提交前，其他请求可能从旧 DB 重建旧缓存，旧值最长保留到 TTL；
2. 当前事务内的 `getProfile` 能读到自己的新值并提前写入缓存，如果最终提交失败，缓存可能保留未提交数据。

Redis 删除失败还会抛出运行时异常，使数据库事务回滚；但已经发生的 Redis 操作不会随数据库一起回滚。

### 3.5 演进方向

当前规模下可以先保持 Cache Aside，但应把缓存失效安排到 `afterCommit`，或发布可靠的资料变更事件后由消费者失效。热点场景再评估互斥重建、逻辑过期或短期空值缓存。批量接口可改为 pipeline/MGET，但要保留“部分用户不存在”的明确结果语义。

## 四、兴趣全量替换：简单状态模型下的原子覆盖

### 4.1 为什么适合全量替换

兴趣数量有明确上限，前端天然持有当前完整选择集。相较“新增/删除/调序”三套增量命令，全量替换更容易表达最终状态：

```sql
DELETE FROM user_interest WHERE user_id = ?;
-- 按前端顺序逐条 INSERT，sort_order 从 0 递增
```

`UserInterestServiceImpl.replaceUserInterests` 是有效的公共事务方法，删除和逐行插入会在同一个数据库事务内提交或回滚。唯一约束 `(user_id, tab_key, tag_key)` 阻止重复标签。

### 4.2 校验口径并不完全一致

- gRPC 入口：图片标签最多 9 个，标签总数最多 50 个；
- service 层：图片最多 9 个、文字最多 50 个，理论上允许总数达到 59；
- onboarding 走 profile service 内部替换逻辑，也采用“图片 9 + 文字 50”的口径；
- 空列表在独立兴趣入口会清空全部兴趣；
- onboarding 只有兴趣列表非空才替换，空列表表示“保留原兴趣”。

因此“空列表”和“最大总量”在两个入口中语义不同，文档和客户端不能把它们视为同一命令。

### 4.3 并发和失败

没有用户级锁或版本号。两个并发全量替换都会各自成功执行，最终结果取决于数据库提交顺序，即 last-commit-wins。单次事务内任意重复标签或字段约束失败会整体回滚，不会留下半套兴趣。

演进选择取决于产品：若允许最后提交覆盖，当前模型足够；若前端需要发现“我编辑期间资料已被其他端修改”，应增加版本号并使用乐观锁，而不是再加长时间分布式锁。

## 五、多来源封禁：缓存顺序决定生效语义

### 5.1 当前判定链

`UserBanServiceImpl.checkBan` 的顺序是：

1. 读取 `putao:user:ban:status:{userId}`，TTL 5 分钟；
2. miss 后检查永久 Redis Set `putao:user:ban:thirdparty-set`；
3. 再读取 `user_info.regulation_status`，其中 2 表示 banned、5 表示 suspended；
4. 把派生结果写回 5 分钟短缓存。

缓存中会同时保存 NORMAL 与封禁结果。这样能降低读放大，但也意味着缓存优先级高于运营集合：若 NORMAL 已缓存，刚加入运营集合的用户最长可能继续正常 5 分钟，除非写入方主动清理短缓存。仓库内没有运营集合写入方，也没有状态更新后调用 `evictBanStatus` 的完整链路。

### 5.2 Redis 故障不是完整降级

只有运营集合的 `SISMEMBER` 被 try/catch 包裹，失败时按“未命中运营封禁”继续。短缓存 `GET` 和结果 `SET` 没有捕获 Redis 异常，因此 Redis 整体不可用时，请求可能在第一步或回填时失败，而不是稳定回源 DB。

这是安全策略选择上的不完整状态：

- 运营集合查询失败目前是 fail-open；
- 短缓存基础设施失败却可能让整个检查 fail-closed；
- 不存在的 userId 会被当成正常用户，而不是“用户不存在”。

### 5.3 返回映射缺陷

service 返回的 reason 字符串来自 Proto 枚举名，例如 `BAN_REASON_USER_BANNED`；gRPC 转换器却匹配 `USER_BANNED`、`USER_SUSPENDED` 和 `OPERATIONAL`。结果可能出现：

```text
banned = true
reason = BAN_REASON_NONE
message = "Account banned"
```

`banned_at_ms` 也始终为 0，因为当前表结构和服务逻辑没有封禁时间来源。

演进时应先统一一个内部枚举，消除字符串二次映射；再明确 Redis 故障到底 fail-open 还是 fail-closed，并让运营写入与短缓存失效成为同一条受控链路。

## 六、头像两阶段上传：契约完整，持久化闭环尚未形成

### 6.1 理想冲突

头像二进制不应经过 user-service，否则会占用服务线程和网络带宽；但客户端也不能任意写对象路径或冒充他人头像。因此采用：

1. 服务端生成限定在 `avatar/{userId}/` 下的对象 key 和短期上传凭证；
2. 客户端直传对象存储；
3. 客户端 confirm；
4. 服务端验证对象并把 key 写入用户资料。

### 6.2 当前实际实现

已实现的部分：

- 扩展名只允许 jpg/jpeg/png/webp；
- 声明大小超过 10MB 会拒绝；
- key 使用 `avatar/{userId}/{uuid}.{ext}`；
- 返回的过期时间为当前时间后 5 分钟；
- confirm 校验 key 必须以当前 userId 的目录开头。

尚未实现的部分：

- presigned URL 是带 `PLACEHOLDER` 签名的固定字符串，没有调用对象存储；
- 没有把 Content-Length、Content-Type 等限制绑定到真实签名；
- confirm 不执行 headObject，不验证对象存在、大小或媒体类型；
- V1 表没有头像列，confirm 不写数据库；
- `getProfile` 固定 `avatar=null`，确认后仍看不到新头像。

所以该能力应标记为“部分实现/占位”，不能描述为已经完成对象存储直传闭环。

### 6.3 演进顺序

先补数据库迁移和对象存储适配，再实现真实 presign 与 confirm 验证；数据库提交后失效缓存。缩略图可作为后续异步能力，但必须通过 outbox 或可重试任务保证状态可追踪，不能仅凭“未来有 worker”描述成当前能力。

## 七、Proto 预留与跨服务降级

Proto 定义了用户类型查询、DH 候选召回、附近 BH 召回，但 `UserGrpcService` 没有覆写对应方法。grpc-java 基类会返回 UNIMPLEMENTED。

影响已经进入调用链：

- match-service 查询用户类型失败时返回 `-1`；
- match-service 两类召回失败时返回空列表；
- im-service 查询用户类型失败时按 BH 处理；
- 这些降级避免调用方直接崩溃，却会让 DH 识别、推荐召回和后续业务退化。

这是“字段已预留”与“能力已支持”的典型区别。演进时要么尽快补齐服务实现和集成测试，要么从当前稳定契约中移除/标注实验接口，避免调用方把 UNIMPLEMENTED 当普通空结果长期吞掉。

## 八、Snowflake ID：算法正确不等于部署正确

`SnowflakeIdGenerator` 使用 41 位时间、5 位 datacenter、5 位 worker 和 12 位序列，方法加 `synchronized`，单进程同毫秒序列溢出时等待下一毫秒，检测到时钟回拨则直接失败。

当前 workerId 和 datacenterId 默认都是 1，由环境变量配置。多实例若复用同一组合，算法无法保证跨实例唯一；系统时钟回拨也会中断新用户创建。

因此项目事实只能说“已使用 Snowflake 生成业务 ID”。生产演进还需要实例编号分配、启动冲突检查、时钟监控和回拨处置，或者迁移到集中式/数据库序列化的 ID 服务。
