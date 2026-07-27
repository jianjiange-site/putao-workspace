# im-service 知识点整理

> 配套：[`README.md`](./README.md)、[`prd.md`](./prd.md)、[`interview-qa.md`](./interview-qa.md)
>
> 本文按"场景 → 问题 → 方案 → 权衡 → 实现"结构组织。每个知识点都给出 im-service 真实代码引用 + 为什么这么设计的解释。

---

## 知识点一：IM Provider 适配器模式（抹平多 IM 引擎）

### 背景/场景

dating app 可能切换/并存多个 IM 引擎：OpenIM（开源）、腾讯 IM（商用）、声网 Agora（商用）。每个引擎的回调 JSON 字段名、嵌套结构都不同。如果 im-service 直接在业务代码里写 `if provider == "openim" { ... } else if provider == "tencent" { ... }`，每次新增引擎都要改业务 handler。

### 问题分析

如果 callback 处理逻辑直接绑死在 OpenIM：
- 切换引擎 → 改一堆 if/else；
- 多引擎并存（A/B 灰度） → 业务代码双倍维护；
- 引擎升级回调字段变化 → 业务 handler 跟着改。

### 解决方案

**`ImProviderAdaptor` 接口 + `ImProviderAdaptorManager` 分发 + `ImEvent` sealed interface 归一化**。

```java
// 接口定义
public interface ImProviderAdaptor {
    boolean supports(String provider);
    ImEvent parse(byte[] rawPayload);
}

// 当前 OpenIM 实现
@Component
public class OpenImAdaptor implements ImProviderAdaptor {
    public boolean supports(String provider) {
        return "openim".equalsIgnoreCase(provider);
    }
    public ImEvent parse(byte[] rawPayload) {
        // JSON 解析 → MessageBeforeSendEvent / MessageSentEvent / ...
    }
}

// 分发管理器
@Component
public class ImProviderAdaptorManager {
    private final List<ImProviderAdaptor> adaptors;
    public ImEvent parse(String provider, byte[] rawPayload) {
        for (ImProviderAdaptor adaptor : adaptors) {
            if (adaptor.supports(provider)) {
                return adaptor.parse(rawPayload);
            }
        }
        return UnknownEvent;
    }
}

// 归一化事件（sealed interface）
public sealed interface ImEvent permits
        ImEvent.MessageBeforeSendEvent, ImEvent.MessageSentEvent,
        ImEvent.UserOnlineEvent, ImEvent.UserOfflineEvent, ImEvent.UnknownEvent { ... }
```

```java
// 业务 handler switch pattern matching — 编译期穷尽
return switch (event) {
    case MessageBeforeSendEvent e -> beforeSendHandler.handle(e);
    case MessageSentEvent e -> { messageSentHandler.handle(e); yield 0; }
    case UserOnlineEvent e -> { presenceService.online(...); yield 0; }
    case UserOfflineEvent e -> { presenceService.offline(...); yield 0; }
    case UnknownEvent e -> 0;
};
```

### 实现细节

- **`sealed interface` 强制业务代码穷尽**：Java 17+ sealed + pattern matching switch，编译期保证所有 case 都处理。新增 ImEvent 子类型时，所有 switch 处编译失败提醒必须处理。
- **Spring 自动注入所有 adaptor**：manager 通过构造函数注入 `List<ImProviderAdaptor>`，新加 TencentAdaptor 只需加一个 `@Component`，无需改 manager。
- **unknown 不阻塞**：解析失败 → return OK（0），不让 IM 引擎因解析 bug 阻塞主流程。

### 权衡取舍

#### 为什么不直接做 OpenIM 单引擎？

- CLAUDE.md 红线 #6："业务服务自建 WebSocket 或绕开 im-service 调 OpenIM" — 即使当前只用 OpenIM，也要把 im-service 做成 OpenIM 的**唯一封装**，未来切换引擎不改业务。
- 抽象层很薄（一个接口 + 一个 manager），成本可接受。

#### 为什么不把 unknown 当成错误抛？

OpenIM 升级 / 字段变更时 unknown 会多起来。抛出 → 整个 OnRawCallback 返回非 0 → OpenIM 那边可能 retry 或报错 → 雪崩。
返回 0 + WARN 日志让 OpenIM 走完流程，业务侧观测 unknown 频次发现引擎兼容性问题。

---

## 知识点二：Before-Send Hook 在 IM 引擎内的位置与设计

### 背景/场景

IM 引擎（OpenIM）支持"消息发送前回调"——App 端调发消息时，OpenIM 不直接投递，而是先回调业务系统问"让不让发"。
业务系统根据返回值（0 = 允许、非 0 = 拒绝）决定 OpenIM 是否真正投递。

im-service 在这个钩子里做三件事：
1. 反导流（屏蔽站外联系方式）
2. BH 放行 / DH 放行
3. 异步扣费（默认）/ 同步扣费

### 问题分析

如果在 App 端做安检/扣费：
- App 可被反编译绕过 — 反导流形同虚设；
- 多端登录的扣费要 App 自己维护一致性。

如果在 OpenIM 服务端做：
- OpenIM 是通用引擎，不认识 "dating app 的扣费规则"。

最佳位置：**IM 引擎 hook 回调到 im-service，业务规则全在服务端**。

### 解决方案

**OpenIM callbackBeforeSendMsg → im-service BeforeSendHandler.handle(event) → 返回 code 给 OpenIM**。

```java
public int handle(ImEvent.MessageBeforeSendEvent event) {
    // 1. 解析失败 → 放行（防御性编程：解析 bug 不能误伤正常用户）
    if (event.getFromUserId() == null) {
        log.warn("Failed to parse senderId in beforeSend, allowing");
        return ImErrorCode.OK;
    }

    Long fromUserId = event.getFromUserId();

    // 2. DH → 放行（AI 自己的消息不扣费不安检）
    if (isDigitalHuman(fromUserId)) {
        return ImErrorCode.OK;
    }

    // 3. 反导流（仅文本消息）
    if (antiFunnelEnabled && event.getMsgType() == MessageType.TEXT) {
        String detected = contactInfoDetector.detect(event.getContent());
        if (detected != null) {
            return ImErrorCode.REJECT_CONTACT_INFO;
        }
    }

    // 4. 扣费
    if (chargeEnabled) {
        return checkAndCharge(fromUserId, coinCost, event.getMessageId());
    }

    return ImErrorCode.OK;
}
```

### 实现细节

- **DH 判定走缓存**：`UserServiceClient.isDigitalHuman(fromUserId)` 命中本地 ConcurrentHashMap，O(1)。
- **coin-cost 从 Nacos 读**：`im.message.charge.coin-cost: 6`，运营可热调。
- **code 设计**：5002 起跳（5xxx 业务拒发），与正常 0/1xxx 系统错误区隔。

### 权衡取舍

#### 为什么 sender 解析失败要放行？

IM 引擎可能因为字段缺失、字段格式变更返回不完整的 callback。如果严格按"无法判断"拒发，会大面积误伤正常用户。
放行 + WARN 日志是更保守的做法，让消息发出去，由后续监控发现解析 bug。

#### 为什么 DH 要放行不扣费？

DH 是数字人，它自己发的消息由 ai-chat-service 代发。如果扣费，会把"BH 用户付费"算到 DH 头上，金币账户系统也错乱。
DH 跳过反导流同理 —— DH 自己产生的回复不应该被反导流规则拦。

#### 为什么不强制同步扣费？

- 同步扣费：扣成功才放行；payment-service 抖动 → 消息发不出去 → 用户体验差
- 异步扣费：预检通过 → 放行 → 后台真扣；payment-service 抖动 → 消息正常发，扣费后台失败 → 损失 ≤ 6 金币/条

默认 async=true，是 dating app 体验优先的选择。

---

## 知识点三：异步扣费的幂等性设计（messageId 作 idempotent_key）

### 背景/场景

before-send 阶段预检通过 → 立即放行 → 后台异步扣费。如果同一个 messageId 被回调两次（OpenIM 重试、im-service 重启后任务重投）：
- 第一次：扣 6 金币
- 第二次：又扣 6 金币 → 用户被扣两次

用户感知是"我发了一条消息为什么被扣 12 金币"。

### 问题分析

异步扣费天然有重复风险：
- OpenIM 回调重试；
- im-service 重启后任务恢复；
- 网络抖动导致 retry。

解决思路：幂等键。

### 解决方案

**用 OpenIM 的 `serverMsgID`（每条消息全局唯一）作为 payment-service 的 `idempotent_key`**。

```java
// CoinChargeDispatcher.dispatch
public void dispatch(Long userId, int amount, String messageId) {
    chargeExecutor.execute(() -> {
        var result = paymentClient.consumeCoins(userId, amount, messageId);
        // ...日志
    });
}

// PaymentServiceClient.consumeCoins
public ChargeResult consumeCoins(Long userId, int amount, String messageId) {
    ConsumeCoinsRequest request = ConsumeCoinsRequest.newBuilder()
            .setUserId(userId)
            .setAmount(amount)
            .setIdempotentKey("im-msg:" + messageId)  // ← 关键
            .setDescription("Chat message charge")
            .build();
    // ...
}
```

payment-service 收到 `idempotent_key="im-msg:<serverMsgID>"`，**第一次 INSERT 到 coin_consume_log 表，第二次 UNIQUE 冲突 → 视为已扣，返回 OK**（见 `payment-service.knowledge.md` 的幂等性设计）。

### 实现细节

- **idempotent_key 加前缀 `im-msg:`**：与 match-service 用的 `super_hi:<user>:<target>`、post-service 的 `like:<post>:<user>` 区分开。
- **chat_messages 表本身也有 `message_id UNIQUE`**：before-send 不会落 chat_messages（消息还没发），after-send 时 messageId UNIQUE 兜底重复落库。
- **两个幂等层级**：
  - coin 扣减幂等（payment-service 端）；
  - 消息落库幂等（chat_messages UNIQUE）；
  
  两者都用同一个 messageId，统一幂等键源。

### 权衡取舍

#### 为什么不用 userId + 时间戳作幂等键？

- 用户同一秒可能发多条消息（连续打字）— 用时间戳粒度太粗；
- serverMsgID 是 OpenIM 全局唯一雪花 ID，最稳定。

#### 为什么不重试异步扣费？

- 单条消息 6 金币损失小；
- 扣费重试失败可能有部分扣成功（DB 已扣但 RPC 超时未确认），retry 会更难处理；
- ERROR 日志够运营发现。

#### payment-service 扣失败为什么不让消息撤回？

消息已经在 OpenIM 那边发出，撤回是给用户显示"消息已撤回"，体验更差。
不如"让消息发出去 + ERROR 日志 + 用户被漏扣 6 金币"损失小。

---

## 知识点四：双层在线状态 — Redis ZSet + PG 表

### 背景/场景

im-service 要给 match-service 的 DH 模拟计划提供两类查询：
- **最近上线用户**（OnlinePlanGenerator）：用于"用户新上线时 drip 一批 DH like"
- **最近下线用户**（OfflinePlanGenerator）：用于"用户离线超过 20 分钟 drip DH visit"

两类查询的访问模式不同：
- 在线用户查询 → 高频、低延迟、范围按 score（上线时间）→ Redis ZSet 完美匹配
- 下线用户查询 → 低频、需要持久化历史、按 offline_at 范围 → PG 表 + 索引

### 问题分析

如果只用 Redis ZSet：
- Redis 抖动/重启 → 在线状态丢失；DH 计划取不到数据；
- ZSet 没有"曾经在线过"的历史（ZADD 只能查当前在线）。

如果只用 PG：
- 每分钟高频率 ListOnlineUsers → PG range 扫描慢；
- ZSet 毫秒级 score 排序 vs PG B-tree 索引范围扫描 → PG 慢 10x+。

### 解决方案

**Redis ZSet 缓存当前在线 + PG 表持久化历史**。

| 维度 | Redis ZSet | PG user_online_session |
|------|-----------|------------------------|
| 数据 | 当前在线（offline_at IS NULL） | 所有上下线历史 |
| 用途 | ListOnlineUsers 高频读 | ListRecentOfflineUsers + sweep 兜底 |
| 索引 | score=onlineAt epoch ms | offline_at 部分索引（IS NOT NULL） |
| 写入 | ZADD NX（多端去重） | INSERT（上线）/ UPDATE offline_at（下线） |

```java
// 上线
public void online(Long userId, Integer platform, long onlineAt) {
    boolean first = redisManager.markOnline(userId, onlineAt);  // ZADD NX
    if (first) {  // 首次上线
        UserOnlineSessionEntity session = new UserOnlineSessionEntity();
        session.setOfflineAt(null);  // NULL = 在线
        sessionMapper.insert(session);
    }
}

// 下线
public void offline(Long userId, Integer platform, long offlineAt) {
    Optional<Long> sinceOpt = redisManager.onlineSince(userId);
    if (sinceOpt.isPresent()) {
        // 回填 offline_at + duration_seconds
        // ZREM
    }
}
```

### 实现细节

- **`ZADD NX` 去重**：同 user 多端登录（iOS+Android 同时在线），ZSet 只算一个 member，score 取第一次上线时间；PG 也只 INSERT 一次。
- **PG offline_at 部分索引**：`WHERE offline_at IS NOT NULL AND deleted = 0`，sweep 和 ListRecentOfflineUsers 都走这个索引（NULL 行不参与扫描）。
- **Redis 抖动兜底**：sweep 任务每 30 分钟扫 PG offline_at IS NULL 且 online_at < now-26h 的孤儿 → 强制 close + ZREM 兜底。

### 权衡取舍

#### 为什么不用 PG 单一源？

ListOnlineUsers 是高频读（match-service 每 60 秒调用一次，量级 = 当前在线人数）。
PG 范围扫描 + B-tree 索引 vs Redis ZSet rangeByScore → Redis 快 10x+。

#### 为什么不用 Redis 单一源？

Redis 不是 source of truth，重启丢数据。
DH 计划（OfflinePlanGenerator）依赖"曾经上线过的历史用户"，Redis 满足不了。

#### 为什么 offline_at 不直接写 NULL 让 Redis 单一源？

- sweep 找不到孤儿（Redis 丢了）；
- 运营查"上周平均 session 时长" / "DAU/MAU"等历史指标需要 PG；
- offline_at 部分索引已经够小，PG 写入开销可控。

---

## 知识点五：ShedLock 分布式锁替代 Redisson 在调度任务中的选择

### 背景/场景

im-service 有定时任务 `PresenceSweepJob`（每 30 分钟扫孤儿会话）。多实例部署时（如灰度 2 个 pod），需要保证同一时刻只有一个实例在执行 —— 否则两个实例都跑 sweep 会：
- 重复 UPDATE 同一行 PG（虽然 idempotent，但浪费）；
- 多扫多 sweep，浪费 IO。

### 问题分析

常见方案：

| 方案 | 优点 | 缺点 |
|------|------|------|
| `@Scheduled` 注解 + 单实例 | 简单 | 部署多实例 → 重复执行 |
| Redis SETNX 锁 | 跨服务统一锁源 | 需要写 unlock 逻辑 / 锁过期处理 |
| **ShedLock + JDBC** | 标准库、@SchedulerLock 一行注解、JDBC 锁持久化 | 引入 shedlock 依赖 |

### 解决方案

**ShedLock + PG 表 shedlock + `@SchedulerLock` 注解**。

```sql
-- V2__init_shedlock_table.sql
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)   PRIMARY KEY,
    lock_until TIMESTAMPTZ   NOT NULL,
    locked_at  TIMESTAMPTZ   NOT NULL,
    locked_by  VARCHAR(255)  NOT NULL
);
```

```java
// ShedLockConfig
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class ShedLockConfig {
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}

// PresenceSweepJob
@Scheduled(cron = "${im.presence.sweep.cron:0 */30 * * * *}")
@SchedulerLock(name = "presenceSweep", lockAtMostFor = "PT5M")
public void sweep() {
    // ...
}
```

### 实现细节

- **`lockAtMostFor="PT5M"`**：任务最长 5 分钟，超时自动释放锁（兜底防死锁）。
- **`lockAtLeastFor`** 未设：默认 0，任务完成后立即释放。
- **`usingDbTime()`**：用 DB 时钟而非应用时钟，避免多实例时钟漂移导致锁误判。
- **`@EnableSchedulerLock(defaultLockAtMostFor="PT2M")`**：所有 @SchedulerLock 注解的默认最长时间，可逐个覆写。

### 权衡取舍

#### 为什么 im-service 用 ShedLock 而 match-service 用 Redisson？

| 维度 | im-service | match-service |
|------|-----------|---------------|
| 任务数 | 1 个 sweep | 5 个 scheduler（D1 cron / DH plan online/offline / executor / outbox retry） |
| 锁粒度 | 任务级（sweep） | 用户级 / 业务级（每用户 5s 锁） |
| 锁源 | PG（已有 schema） | Redis（高频） |

im-service 任务少 + 已有 PG → ShedLock JDBC 简单。
match-service 锁粒度细 + 高频 → Redisson 更合适。

#### 为什么不直接用 Redisson？

- ShedLock 是调度任务标准方案，注解语义清晰；
- 锁数据持久化在 PG 不依赖 Redis 单独存活；
- im-service 没有 Redisson 强需求（不像 match-service 的 swipe 锁需要快速续约）。

---

## 知识点六：IM 服务与 payment / user / match 服务的边界设计

### 背景/场景

im-service 需要：
- 查 user 是否是 DH（UserServiceClient）；
- 扣用户金币（PaymentServiceClient）；
- 接收 match-service 的配对回调（gRPC server）。

CLAUDE.md 红线：
- ❌ 跨服务直连别人家的 PG/Redis；
- ❌ 服务间用 HTTP/Feign/RestTemplate 代替 gRPC；
- ❌ 业务服务自建 WebSocket 或绕开 im-service 调 OpenIM。

### 解决方案

**im-service 通过 gRPC 调用 user-service 和 payment-service，对外提供 gRPC 供 match-service 调用**。

| 资源 | 访问方式 |
|------|---------|
| user-service 用户类型 | `UserServiceGrpc.UserServiceBlockingStub.getUserType` |
| payment-service 金币 | `PaymentServiceGrpc.PaymentServiceBlockingStub.getBalance / consumeCoins` |
| match-service 配对回调 | im-service gRPC server（EnsureConversation / SendSystemMessage 等） |
| OpenIM 引擎 | HTTP REST（OpenImApiClient），im-service 是唯一封装 |
| LiveKit 引擎 | HTTP API（签发 JWT），im-service 是唯一封装 |

```java
// UserServiceClient — 缓存 BH/DH 判断
public boolean isDigitalHuman(Long userId) {
    Boolean cached = userTypeCache.get(userId);
    if (cached != null) return cached;
    try {
        GetUserTypeRequest request = GetUserTypeRequest.newBuilder().setUserId(userId).build();
        GetUserTypeResponse response = stub.getUserType(request);
        boolean isDh = response.getUserType() == UserType.USER_TYPE_DH;
        userTypeCache.put(userId, isDh);
        return isDh;
    } catch (Exception e) {
        log.warn("Failed to get user type, fallback to false", userId, e);
        userTypeCache.put(userId, false);
        return false;
    }
}
```

```java
// PaymentServiceClient — 聊天扣费 + 幂等键
public ChargeResult consumeCoins(Long userId, int amount, String messageId) {
    ConsumeCoinsRequest request = ConsumeCoinsRequest.newBuilder()
            .setUserId(userId)
            .setAmount(amount)
            .setIdempotentKey("im-msg:" + messageId)
            .build();
    // ...
}
```

### 实现细节

- **UserServiceClient 本地缓存**：BH/DH 类型稳定（DH 是预设），无 TTL 不影响业务；命中省 RPC，O(1) 返回。
- **失败 fallback BH**：`isDigitalHuman` 失败 → 缓存 false → 当 BH 用。保守策略，避免错误地把 BH 当 DH 跳过安检扣费。
- **PaymentServiceClient 区分读超时（800ms）/ 写超时（2000ms）**：getBalance 是高频读，配 800ms 短超时；consumeCoins 是写，配 2000ms 长超时。

### 权衡取舍

#### 为什么调 user-service 用缓存，调 payment-service 不用缓存？

- BH/DH 类型不会变（缓存 hit 率高，省 RPC）；
- 金币余额会变（每条消息扣一次，缓存了就有 stale data）。

#### 为什么调 OpenIM / LiveKit 用 HTTP 而非 gRPC？

- OpenIM / LiveKit 是外部商用/开源引擎，它们的协议是 HTTP REST（OpenIM）/ WebSocket（LiveKit），不是 gRPC；
- im-service 自封装后，业务侧只看到 im-service 的 gRPC 接口，对 OpenIM 引擎切换透明。

#### 为什么失败 fallback BH 而非抛错？

- 反导流 + 扣费前先判 DH → 失败抛错会让消息发不出去；
- Fallback BH 是保守的（最坏情况是 BH 被扣了费，但消息正常发），不影响 UX；
- 真正的 BH 失败会被 `ContactInfoDetector` 检测，损失 ≤ 单条 6 金币。

---

## 知识点七：typing 续命机制模拟真人持续输出

### 背景/场景

DH（数字人）收到 BH（真人）消息后，AI 自动回复。AI 生成的回复可能 100~500 字，如果一整条发出去，体验是"一秒收到完整回复"——不像真人。
真人打字是"边想边发"，所以 App 端应该有"对方正在打字..."动画几秒，然后分段收到回复。

### 问题分析

如果不发 typing：
- App 端等 2~3 秒没反应 → 以为没回复 → 切走；
- 然后突然一条完整消息到达 → "机器人的味道"。

如果发 typing 但只发一次：
- OpenIM typing 信号不持久，5 秒后 App 端自动消失；
- AI 回复耗时可能 10+ 秒（LLM 慢），typing 失效。

### 解决方案

**typing 续命：每 3 秒重发一次 typing=true，直到 AI 回复真正发送**。

```java
// NotificationService
public void sendTyping(String senderId, String receiverId) {
    sendBusinessNotification(senderId, receiverId, NotificationKeys.TYPING,
            Map.of("typing", true));
}

public void sendStopTyping(String senderId, String receiverId) {
    sendBusinessNotification(senderId, receiverId, NotificationKeys.TYPING,
            Map.of("typing", false));
}
```

```yaml
# application.yml
im:
  typing:
    refresh-interval-ms: 3000      # 每 3 秒续命
    onset-delay-min-ms: 2000        # 收到消息后 2~5 秒开始打 typing（防"秒回"穿帮）
    onset-delay-max-ms: 5000
```

### 实现细节

- **typing 用 custom msg（msgType=100）**：不是 OpenIM 的内置 typing，而是 im-service 主动下发的 custom notification；
- **App 端收到 typing=true → 显示动画**；收到 typing=false → 隐藏动画；
- **分段发送**：AI 生成 N 段文本（按句子切分），每段之间用 typing=true 续命，发送完一段 typing=false；
- **发送失败不抛**：typing 信号是体验增强，不是关键路径。

### 权衡取舍

#### 为什么 typing 是 im-service 发，不是 ai-chat-service 发？

im-service 是 OpenIM 唯一封装层；ai-chat-service 只生成文本，由 im-service 负责把所有"通知 + 消息"下发到 OpenIM。

#### 为什么 3 秒一次？

- App 端 typing 动画默认 5 秒消失；
- 3 秒续命频率：最坏情况下 App 端有 2~3 秒的"空白"——可接受；
- 频率再低（如 5 秒）会出现明显断档；频率再高（如 1 秒）浪费 RPC。

#### onset-delay 2~5 秒是为了什么？

收到 BH 消息 → 立刻打 typing → BH 用户立刻看到"对方秒回了"。
真人不可能秒回。所以加 2~5 秒随机延迟，模拟"看到消息 → 想一下 → 开始打字"。

---

## 知识点八：route_type 维度设计（BH/DH 矩阵）

### 背景/场景

dating app 同时有 BH（真人）和 DH（数字人）。一条消息的发送方和接收方可以是任意组合：

| 方向 | 语义 | 是否触发 AI 回复 |
|------|------|----------------|
| BH → BH | 真人互发 | 否 |
| BH → DH | 真人发给数字人 | **是**（AI 自动回复） |
| DH → BH | 数字人回复真人 | 否（ai-chat 已生成） |
| DH → DH | 数字人互发（理论上不发生） | 否 |

运营/分析需要按 BH/DH 维度统计消息量、扣费金额、AI 触发频次。

### 解决方案

**`chat_messages.route_type` 字段记录消息方向** + **`MessageManager.determineRouteType` 在落库时计算**。

```java
public String determineRouteType(Long fromUserId, Long toUserId, 
                                  boolean fromIsDh, boolean toIsDh) {
    if (fromIsDh && toIsDh) return RouteType.DH_DH;
    else if (fromIsDh) return RouteType.DH_BH;
    else if (toIsDh) return RouteType.BH_DH;
    else return RouteType.BH_BH;
}
```

PG 索引：
```sql
CREATE INDEX idx_chat_messages_route ON chat_messages(route_type, timestamp DESC) 
    WHERE deleted = 0;
```

### 实现细节

- **`MessageSentHandler.handle` 计算 routeType**：after-send 阶段调用 `UserServiceClient.isDigitalHuman` 两次（from + to）。
- **落库字段**：route_type 作为 message 表的一列，配合索引可快速聚合"BH→DH 消息总数"等指标。
- **`RouteType` 用 String 常量而非 Java enum**：与 match-service 同款考虑 —— 后续可加新维度（如 "BH → DH → BH 转发"）无需改 enum。

### 权衡取舍

#### 为什么不存 BH/DH 类型在 user 表关联？

- BH/DH 类型在 user-service 端，`isDigitalHuman` 一次 RPC + 缓存查询足够；
- chat_messages 表要存 from_user_id 和 to_user_id 两列，分别 JOIN user_service 取类型太重；
- 用 enum-like String 字段做聚合，运营可读可查。

#### 为什么 BH → DH 触发 AI，其他方向不触发？

- DH → BH：ai-chat-service 已经处理过；再触发会循环；
- BH → BH：真人，不需要 AI；
- DH → DH：DH 不会主动发消息给另一个 DH，理论上不发生。

---

## 知识点九：OpenIM 懒注册 + 重试一次策略

### 背景/场景

用户首次进入聊天面板时，OpenIM 那边可能还没有这个用户（未注册）。直接调 `/auth/get_user_token` 会拿到 HTTP 500。

App 端的体验：
- 用户进聊天 → 拿到错误 → 看到 loading → 失败 → 重试 → 还是失败。

理想体验：
- 用户进聊天 → 后端自动注册 + 拿 token → 用户无感知。

### 解决方案

**`getUserToken` 拿 500 → 自动 `registerUser` → retry 一次**。

```java
public Optional<TokenResult> getUserToken(String userId, String nickname, String faceURL) {
    // ... HTTP POST ...
    if (response.getStatusCode().value() == 500) {
        // 用户未注册,先注册再重试
        if (registerUser(userId, nickname, faceURL)) {
            return getUserToken(userId, nickname, faceURL);  // ← 递归 retry
        }
    }
    // ...
}
```

`registerUser`：
```java
public boolean registerUser(String userId, String nickname, String faceURL) {
    // POST /user/user_register { users: [{ userID, nickname, faceURL }] }
    // 200/2xx → success
    // body contains "registered" / "exist" → already exists, treat as success
    // 其他 → false
}
```

### 实现细节

- **只 retry 一次**：避免 OpenIM 持续 500 导致无限递归；
- **重复注册判宽容**："registered" / "exist" 视为成功 —— OpenIM 那边可能是 admin token 已注册过；
- **faceURL 自动拼接**：`avatarKey` 是 MinIO object key → `https://minio-api.jianjiange.site/<key>` 拼成完整 URL。

### 权衡取舍

#### 为什么不启动时批量预注册所有用户？

- 用户量大（几万+），启动时全 register 慢；
- 真正进入聊天的用户是少数；
- 懒注册 + 缓存命中（OpenIM 端也有缓存）→ 二次进聊天无延迟。

#### 为什么不引入本地缓存"已注册"？

- im-service 重启缓存丢；
- OpenIM 端有自己的注册缓存，重复 register 是 idempotent；
- 加一层本地缓存得不偿失。

---

## 知识点十：OpenImApiClient 用 RestTemplate 而非 OpenFeign

### 背景/场景

im-service 调 OpenIM 的 REST API（`/auth/user_token` / `/msg/send_msg` / `/user/user_register`）。Spring 生态常用 OpenFeign 做 HTTP 客户端。

### 问题分析

CLAUDE.md 红线 #3："服务间用 HTTP/Feign/RestTemplate 代替 gRPC"。

注意这条红线**指的是服务间调用**（如 im-service → user-service）。
**OpenIM / LiveKit 是外部引擎**，不是 dating app 内的微服务，调它们用 HTTP 是合理的。

### 解决方案

**OpenImApiClient 用原生 RestTemplate 调 OpenIM**。

```java
@Component
public class OpenImApiClient {
    private final RestTemplate restTemplate;
    
    public Optional<TokenResult> getUserToken(String userId, String nickname, String faceURL) {
        // POST /auth/get_user_token
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        // ...
    }
}
```

### 实现细节

- **`headers.set("token", adminToken)`**：OpenIM 调 `/user/user_register` 和 `/msg/send_msg` 需要 admin token 在 header（不是 Authorization Bearer）；
- **`Optional<String>` 返回**：所有调用都可能失败（OpenIM 抖动），用 Optional 让调用方显式处理；
- **失败 ERROR 日志**：不抛 — OpenIM 抖动不应阻塞 im-service 主流程。

### 权衡取舍

#### 为什么不引入 OpenFeign？

- OpenIM 接口少（3~4 个），用 RestTemplate 直接调更轻；
- OpenFeign 需要定义 `@FeignClient` interface + 注解 + Nacos 服务发现配置 —— 当前 OpenIM 不在 Nacos 注册（外部引擎），OpenFeign 用不上服务发现；
- RestTemplate 失败处理更灵活（response.getStatusCode() 判 500/200）。

#### 为什么不抽到 OpenImAdaptor 接口里？

- Adaptor 只负责**解析 callback**（IM → im-service 方向）；
- OpenImApiClient 负责**调用 OpenIM API**（im-service → IM 方向）；
- 两个方向解耦，未来如果 OpenIM 改协议不影响出站调用。

---

## 知识点十一：LiveKit JWT 设计（roomName 命名 + grants 权限）

### 背景/场景

用户发起语音/视频通话 → im-service 用 jjwt 签发 LiveKit JWT → App 拿 JWT 连 LiveKit wss:// → LiveKit 验证 JWT 后允许进入房间。

### 问题分析

JWT 设计关键：
- **room 命名**：必须唯一（同一对人每次通话一个房间）；
- **TTL**：太短 → 通话中途 JWT 过期被踢；太长 → 安全风险；
- **grants 权限**：用户需要 publish（麦克风/摄像头）+ subscribe（听/看对方），但不能 room admin（不能踢人）。

### 解决方案

**room 命名 `call_<userId>_<peerId>` + TTL 30 分钟 + grants `{roomJoin, canPublish, canSubscribe}`**。

```java
public String generateCallToken(Long userId, String peerId) {
    String roomName = "call_" + userId + "_" + peerId;
    long ttl = 30 * 60; // 30 min

    Map<String, Object> grants = new HashMap<>();
    grants.put("roomJoin", true);
    grants.put("canPublish", true);
    grants.put("canSubscribe", true);
    grants.put("video", Map.of(
        "roomAdmin", true,
        "roomCreate", true,
        "roomJoin", true,
        "canPublish", true,
        "canSubscribe", true
    ));

    return Jwts.builder()
            .issuer(livekitApiKey)
            .subject(userId.toString())
            .claim("room", roomName)
            .claim("grants", grants)
            .issuedAt(new Date(now * 1000))
            .expiration(new Date((now + ttl) * 1000))
            .signWith(key)
            .compact();
}
```

### 实现细节

- **roomName 命名**：`<小userId>_<大userId>` 还是 `<发起方>_<接收方>`？当前实现是发起方在前，接收方在后 —— 不要求顺序（双向都能进），但便于排查。
- **TTL 30 min**：单次通话通常 < 30 min；通话超时让用户重新发起，体验可接受。
- **secret-key 来自 Nacos**：绝不在 application.yml 硬编码。

### 权衡取舍

#### 为什么用 roomAdmin=true？

roomAdmin 允许用户创建房间（LiveKit 默认不允许 join 不存在的房间）。开启 roomAdmin 让 App 端发起通话即可创建房间，无需后端预创建。

#### 为什么 TTL 30 分钟？

- 太短（5 min）：长通话被踢，体验差；
- 太长（24h）：JWT 被劫持风险高；
- 30 min 是 dating app 通话常见上限（行业惯例 Tinder/Bumble 也用类似 TTL）。

#### 为什么不支持群组通话？

MVP 只支持 1v1；群组通话需要 grants 设计更复杂（room 名空间 + 多方权限），后续迭代再做。

---

## 知识点十二：App 端 → OpenIM → im-service → OpenIM 的两跳架构

### 背景/场景

一条消息从 App 端到对方 App 端，要经过：
```
App (iOS/Android) 
  → OpenIM WebSocket (TLS, 长连接)
  → OpenIM callback BeforeSendMsg (HTTP) → im-service
  → OpenIM 真正投递消息 (内部)
  → 对方 OpenIM 长连接
  → 对方 App
```

### 问题分析

为什么不直接 App ↔ im-service ↔ 对方 App？

- im-service 不擅长长连接（每次断连/重连/心跳处理复杂）；
- OpenIM 已经解决了长连接、消息可靠投递、离线消息、多端同步等问题；
- im-service 自建 WebSocket 违反 CLAUDE.md 红线 #6。

为什么不直接 App ↔ OpenIM ↔ 对方 App（im-service 不参与）？

- 业务规则（反导流、扣费）必须有人执行；
- App 端可被绕过（反导流失效）；
- im-service 必须介入 before-send 钩子。

### 解决方案

**App 只连 OpenIM，im-service 通过 OpenIM 的 callback 钩子参与业务**。

```
App → OpenIM WS (长连接) ──────────────► OpenIM (内部投递) ────► 对方 OpenIM WS ──► 对方 App
                │                                                       ▲
                │                                                       │
                └─ before/after callback (HTTP, 经 mobile-gateway) ──► im-service
```

### 实现细节

- **OpenIM 是"管道"**：消息真正路由由 OpenIM 完成；
- **im-service 是"在管道上挂钩子的人"**：在管道前后插入业务逻辑；
- **mobile-gateway 是"管道管理员"**：转发 callback 到 im-service gRPC。

### 权衡取舍

#### 为什么不让 App 直接调 im-service gRPC？

- App 不能直接调 gRPC（协议不对，且需要 Nacos 服务发现）；
- App 调 im-service 的入口只有 `GetImToken` / `GenerateCallToken`（拿 token）；
- 业务消息通过 OpenIM WS 走，im-service 通过 callback 介入。

#### im-service 当前没实现的 gRPC 路径：

- `SendMessage` / `EnsureConversation` / `TriggerDhOpening` / `SendSystemMessage` 在 proto 中定义，但 `ImGrpcService` 的实现是 TODO（placeholder）；
- match-service 调这些 gRPC 时会落到 TODO 路径（生产环境必须补完）；
- 当前生产 match-service 通过 OpenIM HTTP 调（mobile-gateway 桥接）实现等价功能。

#### 为什么 OpenIM 而不是自研？

- 长连接、消息可靠投递、离线消息、多端同步是基础能力，研发成本高；
- OpenIM 是开源 + 商用均可，Tinder/Bumble 类应用常用；
- im-service 把 OpenIM 当底层依赖，业务专注。

---

## 知识点十三：CLAUDE.md 红线在 im-service 的落地

| 红线 | im-service 落地方式 |
|------|---------------------|
| ❌ 跨服务直连别人家的 PG/Redis | 所有跨服务访问走 gRPC（UserServiceClient / PaymentServiceClient） |
| ❌ 服务间用 HTTP/Feign/RestTemplate 代替 gRPC | im-service 对外 9 个接口全是 gRPC；仅 OpenIM/LiveKit（外部引擎）用 HTTP |
| ❌ 业务服务自建 WebSocket | im-service 不接受 App 长连接，全部走 OpenIM |
| ❌ 对外 API 暴露内部自增 id | proto 用业务主键 user_id，无内部 id 暴露 |
| ❌ DB 列用 `TIMESTAMP` 或 Asia/Shanghai | 所有时间列用 `TIMESTAMPTZ`，连接 `SET TIME ZONE 'UTC'`，统一 UTC |
| ❌ Redis 当数据库 | 仅缓存在线 ZSet，source of truth 在 PG `user_online_session` |
| ✅ Redis key 前缀 `putao:<service>:<domain>` | `putao:im:presence:online` |
| ✅ 敏感配置放 Nacos | OpenIM admin secret、LiveKit secret-key 都走 `@Value` 注入 |
| ✅ 命名 `dating-<service>-service` | `spring.application.name: dating-im-service` |

---

## 附录：A/B Test 数据期望

上线后，根据以下指标判断设计是否健康：

| 指标 | 健康预期 | 监控位置 |
|------|---------|---------|
| 异步扣费成功率 | > 99% | `im.message.charge.async_fail.count` |
| 反导流命中率 | 0.1% ~ 1% | `im.before_send.reject.contact_info.count` |
| OpenIM 回调处理 P99 | < 200ms | `im.callback.handle.duration` |
| 在线人数 | 与 App DAU 比例 30%~60% | `im.presence.online.count` |
| 平均 session 时长 | 5~15 min | PG `user_online_session.duration_seconds` AVG |
| 孤儿会话清扫数 | < 5/30min | sweep job log |
| AI 触发后 typing 续命次数 | 3~8 次/AI 回复 | ai-chat 集成后统计 |

如果指标不健康：
- 异步扣费成功率 < 99% → 检查 payment-service 健康度；
- OpenIM 回调 P99 > 200ms → OpenImAdaptor 解析逻辑需要缓存；
- 在线人数 < DAU 30% → OpenIM 回调链路异常，App 端 WS 连接有问题；
- 孤儿清扫 > 5/30min → OpenIM 离线回调丢失严重。