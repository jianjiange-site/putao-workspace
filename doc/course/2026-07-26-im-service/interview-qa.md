# im-service 面试问答

> 配套：[`README.md`](./README.md)（学习入口）、[`prd.md`](./prd.md)（业务功能）、[`knowledge.md`](./knowledge.md)（技术原理）

## 使用说明

- **高频问题**：面试中 80% 概率被问到，准备 STAR 法则标准答案。
- **追问**：每个问题准备 2~4 个追问答案，对应"如果对方想得更深"的场景。
- **场景问题**：面试官让你设计一个系统时，怎么组织答案。
- **项目讲解话术**：30 秒 / 2 分钟 / 5 分钟三个版本。

---

## 项目讲解话术

### 30 秒版（一句话定位）

> 我做的 im-service 是 dating app 后端"IM + 实时音视频"的唯一中枢。它是 OpenIM 引擎和 LiveKit 通话引擎的唯一业务封装，对外提供 9 个 gRPC 接口给 match-service / user-service / payment-service 调用，内部实现 IM Provider 适配器抹平多引擎协议差异、before-send 钩子做反导流 + 异步扣费、after-send 钩子做消息落库 + AI 路由、双层 Redis ZSet + PG 表维护在线状态供 DH 模拟计划使用。

### 2 分钟版

> 我们做的是一个 dating app 后端微服务。App 内用户可以聊天、配对后 IM 沟通、发起语音/视频通话。
>
> 我负责的 im-service 是整个系统的"IM + 通话"中枢，扮演 OpenIM 引擎和 LiveKit 通话引擎的**唯一业务封装**。所有聊天消息都经过 im-service 的钩子：① **before-send 钩子** 做反导流检测（屏蔽 Instagram / WhatsApp / Telegram / 美号）+ DH 放行（AI 自己发的不扣费）+ 异步扣金币（用 OpenIM 的 serverMsgID 作幂等键丢给 payment-service）；② **after-send 钩子** 落库 `chat_messages` + 路由判断 BH→DH 时触发 AI 自动回复（通过 ai-chat-service）。
>
> 在架构上有几个关键设计：① **IM Provider 适配器模式**：用 sealed interface `ImEvent` + `ImProviderAdaptorManager` 分发，让 OpenIM 升级或切换到腾讯 IM / 声网时业务代码零改动；② **双层在线状态**：Redis ZSet `putao:im:presence:online` 撑住 match-service 高频 ListOnlineUsers 读，PG `user_online_session` 持久化历史供 OfflinePlanGenerator 用，孤儿 sweep 兜底 26h 没下线的会话；③ **ShedLock 分布式锁**（PG 表）替代 Redisson 用于定时任务 PresenceSweepJob，多实例部署不重复执行；④ **typing 续命**：DH 自动回复时每 3 秒发一次 typing=true 让 App 显示"对方正在打字"，模拟真人持续输出。
>
> CLAUDE.md 红线在 im-service 严守：服务间只用 gRPC（UserServiceClient / PaymentServiceClient），不直连别人家 PG/Redis；OpenIM / LiveKit 这种外部引擎允许 HTTP，因为它们不在 dating app 微服务体系内。

### 5 分钟版（完整版，详讲到每个关键决策）

> （参照上面的 2 分钟版 + PRD/knowledge 中各知识点的 why 部分）

---

## 高频问题

### Q1：请介绍一下你做的 im-service

**考察点**：项目讲解能力；是否理解 im-service 在 dating app 系统中的边界（"唯一封装 IM 引擎"）

**标准答案**（用 STAR）：

- **Situation**：dating app 内用户配对成功后要在 App 内聊天，可能还要发起语音/视频通话。我们选了 OpenIM 做 IM 引擎、LiveKit 做音视频引擎。
- **Task**：需要有一个服务把这些外部引擎封装成业务能力，对外屏蔽引擎细节，让其他服务（match / user / payment）只调 im-service 的 gRPC。
- **Action**：做了 im-service，关键模块：
  - 9 个 gRPC 接口（SendMessage / EnsureConversation / TriggerDhOpening / SendSystemMessage / GetImToken / GenerateCallToken / OnRawCallback / ListOnlineUsers / ListRecentOfflineUsers）；
  - `ImProviderAdaptor` + `ImProviderAdaptorManager` + sealed `ImEvent` 抹平 OpenIM 回调协议；
  - `BeforeSendHandler` 在 OpenIM callback 钩子里做反导流 + 异步扣费；
  - `MessageSentHandler` 做落库 + BH→DH 触发 AI 自动回复；
  - 双层在线状态（Redis ZSet + PG user_online_session）；
  - `PresenceSweepJob` + ShedLock 多实例兜底。
- **Result**：match-service 的 DH 模拟计划完全靠 im-service 的 ListOnlineUsers 跑起来；chat_messages 表稳定日落 N 万条；反导流命中拒发率 0.1%~1%。

**追问**：

- Q: 为什么 OpenIM 是外部引擎，im-service 还要封装一层而不是直接调？
  A: ① CLAUDE.md 红线 #6 禁止业务服务直连 OpenIM；② 切换引擎成本 — 现在 OpenIM，将来可能腾讯 IM / 声网；③ 业务规则（反导流 / 扣费）必须在服务端执行，App 端不可信。

- Q: im-service 跟其他服务（match / post / user）的关系？
  A: im-service **被调用方**：match-service 调 EnsureConversation / SendSystemMessage / ListOnlineUsers 等；im-service **调用方**：调 user-service 看用户类型、调 payment-service 扣金币。**对 App 不开放 HTTP 入口**，只有 gRPC + 通过 OpenIM WS 间接交互。

- Q: 如果让你重做，你会改什么？
  A: ① `ImGrpcService.sendMessage` 等 4 个方法是 TODO，应该改成真正走 OpenIM HTTP API（match-service 当前绕开 im-service 直接调 OpenIM 是技术债）；② `UserServiceClient.isDigitalHuman` 本地缓存加 Nacos invalidate 事件，避免进程内缓存不一致；③ AI 自动回复的 typing 续命和分段发送做成完整状态机（当前是 placeholder）。

---

### Q2：IM Provider 适配器模式是怎么设计的？为什么不直接绑死 OpenIM？

**考察点**：抽象层设计 + 多引擎兼容 + sealed interface / pattern matching

**标准答案**：

- **接口抽象**：
  ```java
  public interface ImProviderAdaptor {
      boolean supports(String provider);
      ImEvent parse(byte[] rawPayload);
  }
  ```
- **归一化事件**：Java 17 sealed interface `ImEvent`，permits `MessageBeforeSendEvent / MessageSentEvent / UserOnlineEvent / UserOfflineEvent / UnknownEvent`，编译期强制业务 switch 穷尽所有 case。
- **分发管理**：`ImProviderAdaptorManager` 遍历所有 adaptor，匹配 supports(provider) 的处理。
- **当前实现**：`OpenImAdaptor`（supports "openim"）；未来加 `TencentImAdaptor` 即可，无需改业务代码。

**为什么这样设计**：

| 维度 | 直接绑死 OpenIM | 适配器模式 |
|------|----------------|-----------|
| 切换引擎成本 | 改 if/else 散落在业务 | 加一个 @Component |
| 引擎升级字段变更 | 业务代码跟着改 | 只改对应 adaptor |
| 多引擎并存（A/B 灰度） | 业务代码双倍维护 | adaptor list 自动支持 |
| 抽象层成本 | 0 | 薄（一接口 + 一 manager） |

**追问**：

- Q: 为什么不直接用 Spring 抽象的策略模式（Strategy）？
  A: 策略模式是接口 + 一个 map 注入；这里用 List<ImProviderAdaptor> 注入 + supports() 匹配，本质一样，只是多了一个分发逻辑让 adaptor 自己声明"我处理什么 provider"。

- Q: Sealed interface 比起普通 interface 的好处？
  A: 编译期保证 switch 穷尽。新增 ImEvent 子类型时，所有 switch 处编译失败提醒必须处理 —— 防止漏 case 导致业务静默失败。

- Q: UnknownEvent 为什么不抛异常？
  A: OpenIM 升级/字段变更时 unknown 会多起来。抛出 → OnRawCallback 返回非 0 → OpenIM 可能 retry 或报错 → 雪崩。返回 0 + WARN 让 OpenIM 走完流程，业务侧观测 unknown 频次发现兼容性问题。

---

### Q3：Before-Send 钩子为什么放在 IM 引擎而不是 App 端？

**考察点**：业务规则落地点选择 + 安全 + 一致性

**标准答案**：

| 维度 | App 端 | IM 引擎钩子 |
|------|--------|-----------|
| 反导流可靠性 | App 可被反编译绕过 → 失效 | 服务端执行，无法绕过 |
| 多端登录一致性 | App 要自己维护扣费一致性 | 集中扣费，幂等键保证一次 |
| 业务可演进 | App 升级要走商店审核 | 服务端热发版 |
| 延迟 | 0（本地判断） | 一个 HTTP 回调 (~50ms) |

**最佳位置**：IM 引擎钩子回调到 im-service 业务规则全在服务端。

```java
public int handle(ImEvent.MessageBeforeSendEvent event) {
    if (event.getFromUserId() == null) return OK;          // 解析失败放行
    if (isDigitalHuman(fromUserId)) return OK;             // DH 放行
    if (antiFunnelEnabled && TEXT) {
        if (contactInfoDetector.detect(content) != null) 
            return REJECT_CONTACT_INFO;                    // 反导流命中拒发
    }
    if (chargeEnabled) return checkAndCharge(...);         // 扣费
    return OK;
}
```

**追问**：

- Q: OpenIM 回调钩子的延迟多少？能接受吗？
  A: 经验值 30~100ms（HTTP 回调）。dating app 聊天对延迟容忍度高（用户感知 < 200ms 没问题），可以接受。

- Q: 为什么 sender 解析失败要放行？
  A: 防御性编程 — IM 引擎字段缺失时严格拒发会大面积误伤。放行 + WARN 让消息发出去，由监控发现解析 bug。

- Q: DH 为什么不扣费？
  A: DH 自己的消息由 ai-chat-service 代发。如果扣费，会把"BH 用户付费"算到 DH 头上，金币账户系统错乱。

- Q: 反导流为什么只查文本消息？
  A: 图片/语音/视频是二进制，反导流检测（正则关键词）只能在文本上做。礼物/系统消息也不是用户主动内容。

---

### Q4：异步聊天扣费怎么保证幂等性？

**考察点**：幂等性设计 + 异步任务去重

**标准答案**：

- **幂等键**：`im-msg:<OpenIM serverMsgID>` — OpenIM 的 serverMsgID 全局唯一。
- **第一次**：payment-service INSERT `coin_consume_log` 表（idempotent_key UNIQUE）。
- **第二次（同 messageId）**：UNIQUE 冲突 → 视为已扣 → 返回 OK。
- **关键代码**：
  ```java
  ConsumeCoinsRequest request = ConsumeCoinsRequest.newBuilder()
      .setUserId(userId)
      .setAmount(amount)
      .setIdempotentKey("im-msg:" + messageId)  // ← 关键
      .build();
  ```

**为什么不用 userId + 时间戳**：

- 用户同一秒可能发多条消息（连续打字）；
- serverMsgID 是 OpenIM 雪花 ID，全局唯一最稳定。

**两个幂等层级**：

| 层级 | 兜底表 | 幂等键 |
|------|--------|--------|
| 扣金币幂等 | `coin_consume_log` | `im-msg:<serverMsgID>` |
| 消息落库幂等 | `chat_messages.message_id UNIQUE` | `<serverMsgID>` |

两者共用一个 messageId，幂等键源统一。

**追问**：

- Q: 为什么异步扣费失败不重试？
  A: ① 单条消息 6 金币损失小；② 扣费失败重试可能有部分成功（DB 已扣但 RPC 超时未确认），retry 处理复杂；③ ERROR 日志够运营发现。

- Q: 为什么不强制同步扣费？
  A: 同步扣费：payment-service 抖动 → 消息发不出去 → 用户体验差。异步：预检通过 → 放行 → 后台真扣；payment 抖动只损失单条 6 金币，体验优先。

- Q: OpenIM 重试回调导致同一 messageId 走两次 before-send，会被扣两次吗？
  A: 不会 — `idempotent_key="im-msg:<serverMsgID>"` 在 payment-service 端去重，第二次 UNIQUE 冲突返回 OK。

---

### Q5：在线状态为什么用 Redis ZSet + PG 双层？

**考察点**：缓存架构 + 数据冷热分层 + 索引设计

**标准答案**：

| 维度 | Redis ZSet | PG user_online_session |
|------|-----------|------------------------|
| 数据 | 当前在线（offline_at IS NULL） | 所有上下线历史 |
| 用途 | ListOnlineUsers 高频读 | ListRecentOfflineUsers + sweep 兜底 |
| 索引 | score=onlineAt epoch ms | offline_at 部分索引 |
| 写入 | ZADD NX（多端去重） | INSERT / UPDATE |

**为什么双层**：

- **只用 Redis**：Redis 抖动/重启丢数据；DH 计划取不到历史用户。
- **只用 PG**：高频 ListOnlineUsers range 扫描慢（PG B-tree vs Redis ZSet 差 10x+）。
- **双层组合**：Redis 撑高频读（毫秒级），PG 持久化历史 + sweep 兜底。

**关键实现**：

```java
// 上线：ZADD NX 去重 + 首次才插 PG
public void online(Long userId, Integer platform, long onlineAt) {
    boolean first = redisManager.markOnline(userId, onlineAt);  // ZADD NX
    if (first) {
        // INSERT user_online_session (offline_at=NULL)
    }
}

// 下线：回填 PG offline_at + ZREM
public void offline(Long userId, Integer platform, long offlineAt) {
    Optional<Long> sinceOpt = redisManager.onlineSince(userId);
    if (sinceOpt.isPresent()) {
        // UPDATE user_online_session SET offline_at=..., duration_seconds=...
        // ZREM
    }
}
```

**离线用户 PG 部分索引**：

```sql
CREATE INDEX idx_user_online_session_offline ON user_online_session(offline_at)
    WHERE offline_at IS NOT NULL AND deleted = 0;
```

只索引 offline_at 非 NULL 的行（已下线的），NULL 行（在线中）不占索引空间。

**追问**：

- Q: sweep 任务为什么是 26h 而不是 24h？
  A: 26h = 24h + 2h buffer；正常用户不会在线超过 24h，超过的视为异常（App crash / OpenIM 回调丢失）。

- Q: 为什么不直接用 Redis 单一源 + AOF 持久化？
  A: Redis AOF 重启恢复最多丢 1s；DH 计划（OFFLINE 模式）依赖"曾经上线过的历史用户"，Redis 不存历史。

- Q: 多端登录怎么处理？
  A: ZADD NX（同 member 不重写 score）+ PG 只在 first=true 时 INSERT → 多端只算一条 session。

---

### Q6：ShedLock 和 Redisson 在分布式定时任务里怎么选？

**考察点**：分布式锁方案选型 + 锁源选择

**标准答案**：

| 维度 | ShedLock | Redisson |
|------|----------|----------|
| 锁源 | PG（shedlock 表） | Redis |
| 注解 | `@SchedulerLock` | 手动 tryLock |
| 续期 | 不支持（lockAtMostFor 兜底） | 看门狗自动续期 |
| 适用场景 | 定时任务（cron） | 业务级锁（用户级 / 资源级） |
| im-service | ✅ PresenceSweepJob | ❌ 不需要 |
| match-service | ❌ 不用 | ✅ swipe 锁 / DH plan 锁 |

**im-service 选 ShedLock**：

- 任务少（1 个 sweep）+ 已有 PG schema；
- ShedLock 注解语义清晰（一行 `@SchedulerLock(name="presenceSweep", lockAtMostFor="PT5M")`）；
- 锁数据持久化在 PG，不依赖 Redis 单独存活。

**match-service 选 Redisson**：

- 锁粒度细（每个用户 5s 锁）；
- 高频调用（每秒多次 swipe）；
- Redisson 看门狗续期 + 可重入更适合业务级锁。

**追问**：

- Q: `lockAtMostFor` 是什么？
  A: 锁的最长持有时间。任务超时（5 分钟没完成）→ 自动释放锁 → 其他实例可接管。防死锁兜底。

- Q: 为什么不强制用 Redisson 统一？
  A: 锁源应该就近 — 已有 PG 就用 PG 锁，已有 Redis 就用 Redis 锁。引入新基建依赖（多锁源并存）是反模式。

- Q: ShedLock 失败时任务会怎样？
  A: `LockProvider.acquireLock` 失败 → 任务跳过本次 → 下次 cron 再尝试。多实例部署中，抢不到锁的实例直接 skip，不是阻塞等。

---

### Q7：chat_messages.route_type 维度怎么设计的？为什么用 BH/DH 矩阵？

**考察点**：数据分析维度设计 + String vs Java enum 选择

**标准答案**：

- **route_type 四种值**：`BH_BH / BH_DH / DH_BH / DH_DH`，由 `MessageManager.determineRouteType` 在 after-send 阶段计算。
- **PG 索引**：
  ```sql
  CREATE INDEX idx_chat_messages_route ON chat_messages(route_type, timestamp DESC) 
      WHERE deleted = 0;
  ```
- **运营/分析价值**：
  - BH→DH 消息数 → 真人愿意和 DH 聊 → AI 自动回复效果；
  - DH→BH 消息数 → ai-chat-service 生成量；
  - BH→BH 消息数 → 真人互聊活跃度。

**实现**：

```java
public String determineRouteType(Long fromUserId, Long toUserId, 
                                  boolean fromIsDh, boolean toIsDh) {
    if (fromIsDh && toIsDh) return RouteType.DH_DH;
    else if (fromIsDh) return RouteType.DH_BH;
    else if (toIsDh) return RouteType.BH_DH;
    else return RouteType.BH_BH;
}
```

**追问**：

- Q: 为什么不存 BH/DH 类型在 user 表关联？
  A: BH/DH 类型在 user-service 端，`isDigitalHuman` 一次 RPC + 缓存查询；chat_messages 表存 from/to 两列，分别 JOIN user_service 太重。route_type 作为 enum-like String 字段做聚合即可。

- Q: 为什么 route_type 用 String 不用 Java enum？
  A: 与 match-service 同款考虑 —— 后续可加新维度（如 "BH→DH→BH 转发"）无需改 enum 跑 migration。代价是编译期不校验非法值，所以常量集中在 `RouteType` 集中管理。

- Q: DH→DH 真的会发生吗？
  A: 理论上不发生（DH 不主动发消息给另一个 DH），但保留 route_type 值是为防御性兜底（万一未来 DH 之间有群组场景）。

---

### Q8：OpenIM 懒注册 + 重试一次是怎么设计的？

**考察点**：幂等重试 + 外部服务容错

**标准答案**：

```java
public Optional<TokenResult> getUserToken(String userId, String nickname, String faceURL) {
    ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
    if (response.getStatusCode().value() == 500) {
        // 用户未注册,先注册再重试
        if (registerUser(userId, nickname, faceURL)) {
            return getUserToken(userId, nickname, faceURL);  // ← 递归 retry
        }
    }
}
```

**`registerUser` 重复注册判宽容**：
```java
if (bodyStr != null && (bodyStr.contains("registered") || bodyStr.contains("exist"))) {
    log.debug("User already exists: userId={}", userId);
    return true;  // 视为成功
}
```

**为什么只 retry 一次**：避免 OpenIM 持续 500 时无限递归。

**追问**：

- Q: 为什么不启动时批量预注册所有用户？
  A: 用户量大（几万+），启动时全 register 慢；真正进入聊天的用户是少数；OpenIM 端也有注册缓存，重复 register 是 idempotent。

- Q: retry 一次够吗？
  A: retry 一次解决"用户未注册"的常见情况；OpenIM 真持续 500 是 OpenIM 故障，多 retry 也无用，应该让 App 端感知到失败。

---

### Q9：im-service 怎么处理 OpenIM 回调的乱序/丢失？

**考察点**：分布式系统容错 + 事件顺序处理

**标准答案**：

**乱序处理（offline 比 online 早到）**：

```java
public void offline(Long userId, Integer platform, long offlineAt) {
    Optional<Long> sinceOpt = redisManager.onlineSince(userId);
    if (sinceOpt.isPresent()) {
        // 正常：online 已有，回填
    } else {
        log.warn("User not online in Redis: userId={}", userId);
        // 不抛错，让 sweep 兜底（孤儿会话 26h 强制 close）
    }
}
```

**丢失处理（online 回调丢失）**：

- App 进入聊天时拿不到 token → 走 OpenIM 自动重连机制 → 重新触发 online 回调；
- im-service 这边不主动补数据，靠 OpenIM 那边兜底。

**before-send 回调丢失**：

- OpenIM 不收到 im-service 返回 → 超时（默认 3s） → OpenIM 默认放行；
- 极端情况：反导流规则被绕过 → 用户发了 Instagram 链接 → 靠运营监控 chat_messages.content 发现。

**追问**：

- Q: OpenIM 超时默认放行安全吗？
  A: 不严格安全（反导流规则被绕过），但权衡体验：超时拒发会让所有消息因 im-service 抖动发不出去。当前选择"默认放行 + 监控告警"。

- Q: 怎么监控 OpenIM 回调丢失？
  A: 监控 `im.callback.handle.duration` P99 和 `im.callback.fail.count`；长时间没收到回调告警。

---

### Q10：im-service 的 Redis 数据结构有哪些？为什么这么设计？

**考察点**：Redis 数据结构选型 + Key 规范

**标准答案**：

| Key | 数据结构 | 用途 | 为什么 |
|-----|---------|------|--------|
| `putao:im:presence:online` | ZSet | 在线用户(member=userId, score=onlineAt epoch ms) | rangeByScore 范围查询 + 多端去重（ZADD NX） |

**Key 命名**：`putao:<service>:<domain>:<id>` —— 防止跨服务 key 撞车（CLAUDE.md 强制）。

**ZSet 选型原因**：

- 业务是"时间窗内的在线用户" → score=时间戳天然适配；
- `rangeByScoreWithScores(since, until, 0, limit)` 一次拉窗口；
- `ZADD NX` 让多端登录只算一个 member。

**TTL 策略**：

- 当前 ZSet **不设 TTL** —— 因为：
  - sweep 任务会主动清理孤儿会话；
  - 设了 TTL 会让"活跃但长时间在线"的合法用户被清掉；
  - ZSet 大小 = 当前在线人数，DAU 10 万的 App 在线 3~5 万人，ZSet 内存 ~1MB。

**追问**：

- Q: 为什么不直接用 SET（member=userId）？
  A: SET 没有 score → 没法查"最近 2 分钟新上线用户"。ZSet 的 score 既是时间戳也是排序键。

- Q: ZSet 不设 TTL 会不会无限增长？
  A: 不会。当前在线 = 活跃在线用户数 × 在线率（30%~60%）；DAU 10 万的 App 在线 3~5 万人，ZSet ~ 1MB，可接受。

- Q: 为什么不用 Redis Hash（field=userId, value=onlineAt）？
  A: Hash 没法做范围查询（"最近 2 分钟上线的"）；ZSet rangeByScore 才是最优解。

---

### Q11：im-service 跟 match-service 怎么协作完成配对后的 IM 副作用？

**考察点**：微服务协作 + 跨服务事件流

**标准答案**：

match-service 配对成功后，本地事务写 match + outbox（match-service.knowledge.md），后台 OutboxRetry scheduler 投递：

```
match-service.MatchOutboxService.deliver
   ↓ gRPC
im-service.EnsureConversation(user_a, user_b)  // 幂等建 OpenIM 会话
   ↓ gRPC
im-service.SendSystemMessage(to=user_a, content="match success", type="MATCH_CREATED")
   ↓ gRPC
im-service.SendSystemMessage(to=user_b, content="match success", type="MATCH_CREATED")
   ↓ gRPC
im-service.TriggerDhOpening(dh_id, target_id, conv_id)  // 触发 DH 开场白
```

**当前问题**：im-service 的 `EnsureConversation` / `TriggerDhOpening` / `SendSystemMessage` 在 `ImGrpcService` 里是 TODO（占位实现）。

**生产路径**：match-service 实际可能绕过 im-service 直接调 OpenIM HTTP API（mobile-gateway 桥接），是已知技术债。

**追问**：

- Q: 为什么 im-service 这几个 gRPC 接口没实现？
  A: 历史原因 — match-service 上线时 im-service 的 OpenIM 封装还没做完。match-service 直接调 OpenIM 是临时方案，应该补全 im-service 的 gRPC 实现统一收口。

- Q: 如果 match-service 直接调 OpenIM，CLAUDE.md 红线 #6 怎么办？
  A: 是技术债。补全 im-service 的 gRPC 实现后，match-service 改成调 im-service gRPC，不再直连 OpenIM。

- Q: Outbox 重试时如果 im-service 一直失败怎么办？
  A: Outbox 任务 5 次失败后置 DEAD，需要人工捞数据（见 match-service.knowledge.md Q4）。

---

### Q12：im-service 怎么保证消息可靠落库？

**考察点**：可靠性 + 重复消息处理 + at-least-once vs exactly-once

**标准答案**：

**`chat_messages.message_id UNIQUE` 兜底**：

```sql
CREATE TABLE chat_messages (
    message_id VARCHAR(128) UNIQUE NOT NULL,
    ...
);
```

- OpenIM 重试回调 → messageId 相同 → INSERT UNIQUE 冲突 → catch 静默吞掉；
- 不会产生重复消息记录。

**At-least-once vs Exactly-once**：

| 模式 | 含义 | im-service 选择 |
|------|------|----------------|
| at-most-once | 不重试，可能丢 | ❌ 不接受 |
| at-least-once | 重试，可能重复 | ✅ OpenIM 回调本身就是 at-least-once |
| exactly-once | 不丢不重 | ❌ 难实现，靠 UNIQUE 兜底去重 |

**实际效果**：靠 OpenIM 的重试机制 + PG UNIQUE 实现"逻辑上 exactly-once"。

**追问**：

- Q: 为什么不主动去重 OpenIM 重发的 messageId？
  A: PG UNIQUE 自动去重，catch DuplicateKey 静默吞掉，无需应用层处理。

- Q: 消息漏落库怎么办？
  A: OpenIM 那边 at-least-once 保证重试；im-service 这边 MyBatis-Plus `insert` 失败抛异常 → ERROR 日志 + 监控告警，靠运营发现补数据。

- Q: 为什么不直接用 Kafka 做消息管道？
  A: OpenIM 是 source of truth（App 端聊天记录从 OpenIM 拉），chat_messages 是给业务分析用的镜像。Kafka 引入新基建依赖不值得。

---

### Q13：typing 续命机制为什么每 3 秒一次？怎么设计的？

**考察点**：实时性体验 + 自定义通知 + 节奏设计

**标准答案**：

**typing 是什么**：DH（数字人）回复前/回复中持续发 `typing=true` 通知，让 App 端显示"对方正在打字..."动画。

**节奏设计**：

| 时机 | 间隔 | 目的 |
|------|------|------|
| 收到 BH 消息 | onset-delay 2~5s（随机） | 防"秒回"穿帮，模拟"看到消息 → 想一下 → 开始打字" |
| 开始打字 | refresh 3s | App 端 typing 动画默认 5s 消失，3s 续命保证不间断 |
| AI 生成文本 | N 段，每段前 typing=true | 模拟真人分段发消息 |

**实现**：

```java
public void sendTyping(String senderId, String receiverId) {
    sendBusinessNotification(senderId, receiverId, NotificationKeys.TYPING,
            Map.of("typing", true));
}
```

用 OpenIM 的 custom msg（msgType=100）+ JSON content 下发。

**追问**：

- Q: typing 频率太低会怎样？
  A: App 端动画消失 → 用户以为 DH 停止回复 → 切走。3s 是经验值平衡。

- Q: typing 频率太高呢？
  A: 浪费 RPC 流量 + 加重 OpenIM 压力。1s 一次会刷出几百条 typing 通知。

- Q: 为什么不直接用 OpenIM 的内置 typing？
  A: OpenIM 的内置 typing 是一次性的（5s 过期），不支持续命。im-service 自己用 custom msg 下发更灵活（可控制 timing + 内容）。

- Q: 续命失败会怎样？
  A: App 端动画消失 5s 后 → 用户看到 DH"停顿" → 体验轻微降级。不重试 — 体验增强不是关键路径。

---

### Q14：CLAUDE.md 红线在 im-service 的落地情况？

**考察点**：工程规范 + 架构设计 + 红线意识

**标准答案**：

| 红线 | im-service 落地 |
|------|-----------------|
| ❌ 跨服务直连别人家 PG/Redis | 只通过 gRPC 调 user-service（isDigitalHuman）/ payment-service（getBalance + consumeCoins） |
| ❌ 服务间用 HTTP/Feign/RestTemplate 代替 gRPC | 对外 9 个接口全是 gRPC；OpenIM/LiveKit（外部引擎）允许 HTTP |
| ❌ 业务服务自建 WebSocket | App 长连接只走 OpenIM，im-service 不接 WS |
| ❌ 对外 API 暴露内部自增 id | proto 用业务主键 user_id，无内部 id 暴露 |
| ❌ DB 列用 TIMESTAMP 或 Asia/Shanghai | 所有时间列 TIMESTAMPTZ，连接 `SET TIME ZONE 'UTC'`，代码统一 UTC |
| ❌ Redis 当数据库 | 仅缓存当前在线 ZSet，source of truth 在 PG |
| ✅ Redis key 前缀 | `putao:im:presence:online` |
| ✅ 敏感配置放 Nacos | OpenIM admin secret、LiveKit secret-key 都走 `@Value` 注入 |
| ✅ 服务命名 dating-<service>-service | `spring.application.name: dating-im-service` |

**追问**：

- Q: OpenIM / LiveKit 调 HTTP 不违反红线 #3 吗？
  A: 不违反 —— 红线针对 dating app 内**服务间调用**（user → match → payment 等）。OpenIM / LiveKit 是**外部引擎**，不在 dating app 微服务体系内。

- Q: 时区为什么强 UTC？
  A: 微服务集群可能部署在不同时区；DB `TIMESTAMPTZ` 内部存 UTC epoch；统一 UTC 避免时区转换 bug。App 展示层自己根据 user timezone 转换。

- Q: Redis 当 ZSet 缓存"在线状态"算不算"当数据库"？
  A: 不算 —— source of truth 在 PG `user_online_session`；Redis 只是高频读缓存，丢了可以靠 sweep + OpenIM 回调重建。

---

### Q15：如果让你重做 im-service，你会改什么？

**考察点**：反思能力 + 技术视野

**标准答案**：

1. **补全 ImGrpcService 中 4 个 TODO 方法**（`sendMessage` / `ensureConversation` / `triggerDhOpening` / `sendSystemMessage`）—— 当前是占位实现，match-service 应该绕开走 gRPC 而不是直连 OpenIM。
2. **AI 自动回复的 typing 续命 + 分段发送做成完整状态机** —— 当前 `AiReplyService.triggerAiReply` 是 `Thread.sleep(2000)` + 单条消息，应该改成：分段生成 → typing 续命 → 分段发送。
3. **UserServiceClient.isDigitalHuman 加 Nacos invalidate 事件** —— 当前本地缓存无 TTL，未来如果用户类型可变会有 stale data。
4. **chat_messages 加密敏感字段**（content 加密 + 端到端加密）—— dating app 聊天隐私要求高，未来合规需要。
5. **OpenIM 适配器加单元测试** —— 当前 OpenImAdaptor 解析 JSON 没有任何单测，OpenIM 升级时容易漏字段。

**追问**：

- Q: 端到端加密怎么实现？
  A: 用户公钥上传 user-service，im-service 拿公钥加密 content → 落库加密内容 → 接收方用自己私钥解密。密钥管理是用户级（pubkey 跟 user_id 关联），im-service 只加密不解密。

- Q: AI 自动回复状态机怎么设计？
  A: `PendingReply` → `GeneratingReply` → `Typing(分段)` → `Sent(每段)` → `Done`。每个状态转换失败可重试或 fallback 到"DH 不在线"。

---

## 场景问题

### Q16：让你设计一个 IM 服务，你会怎么设计？

**考察点**：系统设计能力（IM + 实时音视频 + 业务规则）

**回答框架**：

1. **需求澄清**：
   - 用户量级（10k / 100k / 1M DAU）？
   - 单聊还是群聊？
   - 是否需要音视频通话？
   - 业务规则（反垃圾、扣费、AI 回复）需求？

2. **数据模型设计**：
   - `chat_messages`（消息流水，message_id UNIQUE）
   - `user_online_session`（上下线历史）
   - Redis ZSet `presence:online`（在线状态）
   - `coin_consume_log`（扣费幂等）

3. **核心接口设计**：
   - gRPC: `GetImToken / GenerateCallToken / OnRawCallback / ListOnlineUsers`
   - IM 引擎: callback before/after send, online/offline

4. **技术选型**：
   - IM 引擎: OpenIM（开源）或腾讯 IM（商用）；
   - 音视频: LiveKit（开源）或声网（商用）；
   - 缓存: Redis ZSet 撑在线状态；
   - 持久化: PG + Flyway migration；
   - 分布式锁: ShedLock（任务级）+ Redisson（业务级）；
   - 反垃圾: 服务端 before-send 钩子（不可被绕过）。

5. **权衡取舍**：
   - IM 引擎自研 vs 商用 → 商用（OpenIM 解决长连接 + 可靠投递 + 离线消息）；
   - before-send 同步 vs 异步扣费 → 异步（体验优先 + messageId 幂等）；
   - 单层 vs 双层在线状态 → 双层（Redis 高频读 + PG 持久化）。

### Q17：怎么防止反导流被绕过？

**回答框架**：
1. App 端做（可被反编译绕过）— ❌ 不能依赖；
2. im-service 服务端做（不可绕过）— ✅ 必须；
3. 引擎钩子做（OpenIM callback）— ✅ 在 OpenIM 真正投递前拦截；
4. 监控运营侧做（人工审查 chat_messages.content）— ✅ 兜底；
5. 用户举报做（被举报多次降权）— ✅ 长期治理。

### Q18：消息可靠性怎么保证？

**回答框架**：
1. OpenIM at-least-once（重试机制）；
2. im-service PG UNIQUE(message_id) 兜底去重；
3. ERROR 日志 + 监控告警（`im.callback.fail.count`）；
4. 极端情况靠人工补数据（运营工具）。

---

## 常见坑及回答

### 坑 1：OpenIM 回调钩子延迟导致消息发不出去

- **场景**：im-service 抖动 → OpenIM 回调超时 → OpenIM 默认放行 → 反导流规则被绕过。
- **现状**：OpenIM 默认 3s 超时放行；监控 `im.callback.handle.duration` P99 告警。
- **回答**：权衡体验和安全性 —— 严格拒发会让消息因 im-service 抖动发不出去；当前选择"默认放行 + 监控告警 + 运营兜底"。

### 坑 2：异步扣费失败导致用户被漏扣

- **场景**：payment-service 抖动 → 异步扣费失败 → 用户消息发了但金币没扣。
- **现状**：ERROR 日志 + `im.message.charge.async_fail.count` 监控；单条 6 金币损失小。
- **回答**：接受 trade-off —— 同步扣费会让 payment 抖动阻塞消息发送，体验更差；异步扣费 + 监控告警 + 运营月度对账。

### 坑 3：sweep 兜底导致大量孤儿会话

- **场景**：OpenIM 离线回调大量丢失 → sweep 每 30 分钟扫出几百个孤儿。
- **现状**：单条 update + ZREM，慢但稳定；ERROR 日志数量上升告警。
- **回答**：当前实现一条条 update，量大时可改为 batch update；根本问题是 OpenIM 回调丢失，应联系 OpenIM 侧排查。

### 坑 4：OpenIM 字段升级导致 unknown 增多

- **场景**：OpenIM 升级 → callback JSON 字段名变 → OpenImAdaptor 解析失败 → UnknownEvent。
- **现状**：return 0 + WARN 日志；监控 unknown 比例告警。
- **回答**：业务不阻塞（return 0 让 OpenIM 走完流程）；unknown 比例上升时排查 OpenImAdaptor 兼容性，发版同步。

### 坑 5：UserServiceClient 本地缓存不一致

- **场景**：用户从 BH 变 DH（理论上不发生，但万一）→ 进程内缓存仍是 BH → 跳过扣费。
- **现状**：缓存无 TTL，依赖 BH/DH 类型不变的假设。
- **回答**：当前 BH/DH 类型是预设属性，不变；如未来支持可变，需加 Nacos invalidate 事件或 Redis 分布式缓存。

---

## 加分项（可主动提及）

1. **CLAUDE.md 红线严守**：im-service 是唯一 IM 引擎封装层；服务间只用 gRPC；OpenIM/LiveKit（外部引擎）允许 HTTP。
2. **IM Provider 适配器模式**：sealed interface `ImEvent` + `ImProviderAdaptorManager` + `OpenImAdaptor`，未来切换 IM 引擎业务零改动。
3. **完整的可观测性设计**：业务监控（im.before_send.reject.*, im.message.persisted, im.message.route.*, im.presence.online.count 等）+ 关键 ERROR 日志（异步扣费失败 / OpenIM 字段解析失败）。
4. **配置驱动**：所有阈值都走 Nacos（`im.message.charge.*` / `im.message.anti-funnel.enabled` / `im.typing.*` / `im.presence.sweep.*`），运营可热刷。
5. **降级容错**：OpenIM 抖动 → 默认放行；payment-service 抖动 → 异步扣费；user-service 抖动 → isDigitalHuman fallback BH；Redis 抖动 → sweep 兜底孤儿会话。**主流程永不阻塞 UX**。
6. **ShedLock + Flyway 标准化基建**：与 CLAUDE.md 规范一致，PG 表 `shedlock` 用 Flyway migration 管理。