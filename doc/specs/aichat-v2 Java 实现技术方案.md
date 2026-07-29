---
title: aichat-v2 Java 实现技术方案
date: 2026-07-19T06:30:45-07:00
tags: aichat-v2, Dora, Java, Spring Boot, 虚拟线程, Agent, LLM, 技术方案
---

> 本方案给出用 **Java** 重新实现 aichat-v2的落地设计。范围 = **核心 Agent 闭环 + 主动触达（simpdog/chase/recovery）+ RAG/记忆压缩**；暂不做全量 AB 实验对等、千人千面 character card 多版本、life event、video tips、CE 社交代理、埋点上报（都作为扩展点预留接口）。参考基线为 Kotlin 版 commit `01d1c5a4`，架构赌注 `Agent = LLM + Loop + Tools` 原样保留。

---

## 1. 目标与范围

**要做到的核心能力**：
1. 接 Tencent TIM 回调，把一条用户消息变成一次「安全、有节奏、按会话串行」的 DH 回复。
2. `Agent = LLM + Loop + Tools`：纯 function-calling 循环 + Pre/Post Hooks 注入业务，循环内零业务逻辑。
3. LLM 多 provider 路由 + staggered hedge fallback。
4. 消息安全流水线（发送前 smell test + AIPolice 分类重生成 + 出口兜底）。
5. Redis 历史/记忆/trace + PostgreSQL profile fallback。
6. 主动触达三件套：simpdog（舔狗）、chase（追发）、recovery（挽回）。
7. RAG（pgvector 索引+预取）+ 历史压缩。
8. **千人千面**：character card（6 维确定性人格）+ emoji 策略 + per-user archetype（同一 DH 对不同用户不同 reaction）。
9. **Life event**：每-DH SUSTAINED 故事线 + 概率性 INSTANT TOPIC/LEAVE 瞬时事件。
10. **Video tips + 视频通话转写**：视频引导触达 + `VIDEO_CALL_COMPLETED` 转写回灌历史。

**明确不在本期**（预留扩展点）：CE/Instagram 社交代理、BytePlus 埋点上报、Tianshu prompt 控制面、event-stream/state-snapshot 架构。全量 BytePlus AB 分桶不做，但本期用到的四个实验键（`template_group` / `media_strategy_v1` / `cross_dh_v1` / `life_event_v1`）走**精简版 AB 解析**（见 §12.1）。这些扩展都通过「新增 Hook / Tool / Client」接入，不改核心循环。

---

## 2. 技术选型

### 2.1 为什么是 Java 21 + 虚拟线程（Loom）

Kotlin 版重度依赖协程做「大量并发 I/O + 结构化取消 + 每资源独立 dispatcher」。Java 侧的等价答案是 **Java 21 虚拟线程**：

| Kotlin 协程概念 | Java 21 等价物 |
|---|---|
| `suspend` 函数 / 协程 | 阻塞式代码跑在**虚拟线程**上（写同步代码、拿异步吞吐） |
| `Dispatchers` / `AppDispatchers.pg/rag/agent` | 每资源一个**有界信号量或平台线程池**（限制并发，隔离故障域） |
| `withContext(named)` | `Semaphore.acquire()` + 在受限执行器上跑 |
| 结构化取消 `Job.cancel()` | `Future.cancel(true)` / `Thread.interrupt()` + `StructuredTaskScope`（预览）|
| `Mutex`（per-lane） | `ReentrantLock` per-conversation（见 §5） |
| `withTimeout` | `Future.get(timeout)` / `CompletableFuture.orTimeout` |

虚拟线程让我们保留原项目「写同步阻塞代码、用超时+隔离控制爆炸半径」的纪律，又不引入 WebFlux 响应式的心智负担。

### 2.2 框架与库

| 层 | 选择 | 说明 |
|---|---|---|
| 语言/运行时 | **Java 21 LTS**，开启虚拟线程 | `spring.threads.virtual.enabled=true` |
| Web | **Spring Boot 3.2+（Spring MVC on 虚拟线程）** | 生态成熟、配置/健康检查/metrics 开箱；非 WebFlux。轻量备选 Javalin/Helidon SE |
| JSON | **Jackson**（+ `jackson-datatype-jsr310`） | 注意 getter 可见性坑（见 §14） |
| Redis | **Lettuce**（异步/响应式 API，或同步 API 跑虚拟线程） | 与 Kotlin 版同源；RESP2 pinned |
| Postgres | **HikariCP + JDBC**，RAG 用裸 SQL（pgvector `<=>`） | Exposed 换成 JDBC/`jOOQ`/MyBatis 均可，推荐 JDBC + 轻封装 |
| Kafka | **Apache Kafka client**（`aiokafka` 对应 `KafkaProducer`） | typing / chat-round / TIM 事件广播 |
| HTTP 客户端 | **JDK 21 `HttpClient`**（LLM/Serper/embedding） | 注意 HTTP/1.1 强制（见 §14） |
| 配置中心 | **Nacos Java SDK**（`nacos-client`） | 热更新监听，env fallback |
| 定时/调度 | **`ScheduledExecutorService`（虚拟线程工厂）** 自研 `PollingExecutor` 基类 | 对应 Kotlin `ScheduledExecutor` |
| 可观测 | **Micrometer + OpenTelemetry Java agent** | `/actuator/prometheus`；OTel W3C trace 传播 |
| 测试 | **JUnit 5 + AssertJ + Testcontainers**（Redis/pgvector） | 单测 + 本地 boot 冒烟 |

> 原 Kotlin 项目 CLAUDE.md 明确「不引入 Spring」是为了那套已选定 Ktor 的架构不反复横跳；对一个**全新 Java 实现**，Spring Boot 3 + 虚拟线程是团队效率与生态的更优解。若团队坚持极简容器，Javalin + 手工 DI 也能 1:1 落地本方案的所有接口。

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  transport 层（Spring MVC Controllers）                        │
│  POST /            TIM 回调入口（快速 ack + 异步派发）          │
│  POST /api/agent/run   debug 手动 run                          │
│  GET  /api/health /metrics /api/trace/...                     │
└───────────────┬─────────────────────────────────────────────┘
                │ MessageTrigger
┌───────────────▼─────────────────────────────────────────────┐
│  LaneManager  —— 每会话串行 + 消息吸收/合并/取消重试           │
└───────────────┬─────────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────────┐
│  AgentRuntime = Pre-Hooks → AgentLoop → Post-Hooks            │
│    AgentLoop  —— 纯 LLM + function-calling，无业务逻辑          │
└───────────────┬─────────────────────────────────────────────┘
                │ AgentResult(sentMessages)
┌───────────────▼─────────────────────────────────────────────┐
│  CancellableReplyDelivery → MessageSender → TimClient(REST)   │
│    多气泡投递 · 自适应节奏 · 可被新消息打断 · 投递后存历史       │
└──────────────────────────────────────────────────────────────┘

横切：LLMRouter(hedge) · RedisStores · PgRepos · Nacos配置 · OTel/Trace
后台：ProactiveScheduler / ChaseExecutor / RecoveryScanner（虚拟线程轮询）
```

**Maven 多模块**（对应 Kotlin package 边界，强制依赖方向）：

```
aichat-java/
├── aichat-models/       Trigger、AgentResult、ToolCall、枚举（无外部依赖）
├── aichat-core/         runtime(AgentLoop/AgentRuntime/LaneManager) + hooks + tools 接口
├── aichat-llm/          LLMClient、LLMRouter、各 provider client、hedge
├── aichat-infra/        Redis/PG/Kafka/Nacos provider + 各 Store 实现
├── aichat-features/     hooks 实现 + proactive + recovery + rag + safety
├── aichat-app/          Spring Boot 启动、Controller、composition root（唯一可引用所有模块）
└── aichat-obs/          Micrometer/OTel 适配
```

依赖法则（编译期用 ArchUnit 守护）：`aichat-core` 只依赖接口（tools/memory/persona），**不依赖** transport/infra 具体实现；`tools` 不得 import `runtime`；`aichat-app` 是唯一 composition root。

---

## 4. 核心抽象（接口先行）

### 4.1 Trigger（sealed，Java 17+ sealed interface）

```java
public sealed interface Trigger permits MessageTrigger, EventTrigger, TimerTrigger {
    String conversationId();
    long msgTime();
}

public record MessageTrigger(
    String conversationId, String userId, String dhUserId,
    String content, List<ContentPart> contentParts,   // 多模态/合并后
    String appName, String platform,                  // DORA / INSTAGRAM
    long msgTime, ActionType actionType                // GIFT / VIDEO_CALL_* / null
) implements Trigger {}

public record EventTrigger(...) implements Trigger {}   // proactive/recovery INITIATIVE
public record TimerTrigger(...) implements Trigger {}    // follow-up 定时
```

### 4.2 Tool（每个 ≤ 50 行，单一能力）

```java
public enum ToolPhase { DEEP_DIVE, COMMIT, CLOSURE }

public interface Tool {
    String name();
    ToolPhase phase();
    JsonNode declaration();                 // function-calling schema
    ToolResult execute(ToolCall call, AgentContext ctx);
}

public final class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    public void register(Tool t) { tools.put(t.name(), t); }
    public Collection<Tool> forTrigger(AgentContext ctx) { /* INITIATIVE 排除 set_follow_up 等 */ }
}
```

### 4.3 Hook（Pre / Post，单一职责）

```java
public interface PreLoopHook  { void invoke(AgentContext ctx); }        // 可 ctx.skipLoop = true
public interface PostLoopHook { void invoke(AgentContext ctx, AgentResult result); }
```

`AgentContext` 是贯穿全程的可变载体（system prompt、historyMessages、profile、memory、ragContent、意图信号、trace collector 等）。**约定**：pre-hook 改写当轮用户输入只能整体替换 `ctx.setTrigger(trigger.withContent(...))`，AgentLoop 读 `ctx.trigger()` 构造首条 user message。

### 4.4 LLM 抽象

```java
public interface LLMClient {                 // 单 provider
    LLMResponse call(LLMRequest req);        // 阻塞，跑在虚拟线程
    String name();
}
public interface LLMRouter {                 // 多 provider + hedge
    LLMResponse call(LLMRequest req, CallWeight weight);  // HEAVY / LIGHT
}
public record LLMResponse(
    List<Part> rawModelParts,                // 关键：保留 Gemini thoughtSignature 原样回传
    List<String> plainTextParts,             // 非 thought 文本，用于 FC 回退
    List<ToolCall> toolCalls,
    int inputTokens, int outputTokens, String model
) {}
```

**函数调用不变量**：`rawModelParts` 原样保存并逐字回传给模型，剥离/重排会打断跨 turn function-calling 连续性——这条在 Java 版同样是硬约束。

---

## 5. 请求生命周期实现

### 5.1 TIM 回调入口（快速 ack + 虚拟线程异步派发）

```java
@RestController
public class TimCallbackController {
    private final ExecutorService agentExec =           // 虚拟线程，每任务一线程
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("agent-", 0).factory());

    @PostMapping("/")
    public Map<String,String> onCallback(@RequestBody String rawBody,
                                         @RequestParam("appName") String appName) {
        // TIM 要求快速 ack
        agentExec.submit(() -> withServerSpan(() ->
            timCallbackService.handleCallback(rawBody, appName)));
        return Map.of("ActionStatus", "OK");            // 立即返回
    }
}
```

`handleCallback` 顺序：并发副作用（chat-round / 事件广播 / videotips）→ `parseCallback` 过滤 echo/bot/非内容 → 埋点入站 → 短路信令事件（`av_call`、`VIDEO_CALL_COMPLETED` 只写历史）→ 构造 `MessageTrigger` → `laneManager.dispatch(...)`。

### 5.2 LaneManager —— 每会话串行 + 吸收

用 **per-conversation `ReentrantLock` + 收件箱队列** 替代 Kotlin 的 lane mutex：

```java
public final class LaneManager {
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    static final class Lane {
        final ReentrantLock lock = new ReentrantLock();
        final BlockingQueue<Inbound> inbox = new LinkedBlockingQueue<>();
        volatile Future<?> deliveryJob;     // 下条消息进来先取消上一轮未发气泡
    }

    public AgentResult dispatch(MessageTrigger t, AgentConfig cfg) {
        Lane lane = lanes.computeIfAbsent(t.conversationId(), k -> new Lane());
        lane.inbox.add(new Inbound(t));
        lane.lock.lock();                    // 同会话串行；不同会话并行（各自虚拟线程）
        try {
            cancelInFlightDelivery(lane);    // 丢弃上一轮未投气泡
            awaitReadingWindow(lane, cfg);   // control 固定 3s / length-aware 窗
            MessageTrigger merged = drainAndMerge(lane);   // 连发合并成一次
            return runWithInterrupt(lane, merged, cfg);    // LLM 期间监视 inbox，新消息→取消重试(≤3)
        } finally { lane.lock.unlock(); }
    }
}
```

`runWithInterrupt`：把 `runtime.run(...)` 提交到虚拟线程拿 `Future`，另起一个每 200ms 轮询 inbox 的监视线程，发现新消息 `future.cancel(true)` → 吸收 → 重试（最多 3 次）。

### 5.3 AgentRuntime = Hooks + Loop

```java
public AgentResult run(Trigger trigger, AgentConfig cfg) {
    try (var span = tracer.startAgentRun(trigger)) {
        var ctx = buildContext(trigger, cfg);           // persona system prompt + memory
        for (PreLoopHook h : preHooks) {
            runHook(h, ctx);
            if (ctx.skipLoop()) return blocked(ctx);     // 任一 hook 可短路
        }
        AgentResult result = agentLoop.run(ctx);
        runPostHooks(ctx, result);                       // AIPolice 清空消息 → DROPPED_BY_POST_HOOK
        persistMemory(ctx.memoryUpdates());
        return result;
    }
}
```

### 5.4 AgentLoop —— 纯循环

```java
public AgentResult run(AgentContext ctx) {
    LoopState state = new LoopState();
    for (int turn = 0; turn < ctx.maxTurns(); turn++) {
        LLMResponse resp = router.call(buildRequest(ctx, state), ctx.callWeight());
        state.recordRawParts(resp.rawModelParts());      // 保留 thoughtSignature

        if (resp.toolCalls().isEmpty()) {                // FC 回退
            if (ctx.fcRetryOn() && turn < ctx.fcRetryMax()) { state.addRetryNudge(); continue; }
            autoCommit(resp.plainTextParts(), state);     // control：泄漏文本自动 send_message
            break;
        }
        for (ToolCall tc : resp.toolCalls()) {
            Tool tool = registry.get(tc.name());
            ToolResult r = tool.execute(tc, ctx);
            state.record(tc, r);
            if (tool.phase() == ToolPhase.COMMIT) return state.toResult();  // send_message 后立即返回
        }
    }
    return state.toResult();
}
```

**关键洞察保留**：LLM 文本输出 ≠ 用户消息，只有 `send_message` 工具产出用户可见消息；沉默 = 不调 send_message。send_message（COMMIT）后立即返回，同 turn 只允许 CLOSURE 工具（`set_follow_up`）。

### 5.5 投递 + 投递后存历史

```java
public void deliverAndPersist(AgentResult result, Lane lane, AgentContext ctx) {
    List<Bubble> bubbles = splitter.split(result.sentMessages());
    var sink = new DeliveredSink();
    Future<?> job = deliveryExec.submit(() -> messageSender.deliver(bubbles, sink));  // Lane 锁外
    lane.deliveryJob = job;
    try { job.get(); }                                   // 正常完成 = 全部投出
    catch (CancellationException e) { /* 被新消息打断：吞掉 */ }
    finally {
        history.saveModelReply(ctx, sink.deliveredRaw()); // 只存「实际投出去的」前缀
    }
}
```

- interruptible（TIM reactive/proactive）：`SaveHistoryHook` **只存 user 侧**，model 侧由此处投递后存。
- user 侧无条件存（即使沉默/被 BLOCK，否则消息从历史消失）。
- 非 interruptible（`/api/agent/run`）：`interruptibleDelivery=false`，SaveHistory 照旧存 user+model。

### 5.6 投递节奏（先实现 control，length-aware 作参数化扩展）

`MessageSender` 两段等待：**Lane 阅读窗**（gate 回复+合并的真正闸门，control 固定 3s）+ **per-bubble compose 延迟**（control 3–8s 随机）。length-aware 4 档（t1/t2/t3）作为 `PacingProfile` 参数化留口，本期可只跑 control。

---

## 6. Hooks 流水线（本期清单）

**Pre-Loop（顺序有依赖）**：
1. `ConversationContextHook` —— profile + memory + 时区 → system prompt（确定性无 LLM）
2. `CharacterCardHook` —— 算每-DH character card（V1/V3/V4）+ emoji 策略 + archetype → `ctx.characterCard*`（§12.2）
3. `HistoryContextHook` —— 聊天历史作为 contents[] 消息数组注入
4. `RAGPrefetchHook`（gated）—— 异步 embedding + 向量检索
5. `ImageDescriptionHook` —— 图片 → Gemini 描述 → 改写 trigger
6. `ActionPromptHook` —— 按 actionType（GIFT/VIDEO_CALL_*）注入场景 prompt
7. `IntentDetectionHook` —— Flash-Lite 分类；CONTACT_INFO_EXCHANGE → skipLoop，其余注入 prompt/能力边界
8. `LifeEventHook`（gated）—— 注入 SUSTAINED 故事线 + 概率性 INSTANT TOPIC/LEAVE，仅设 `LifeEventMeta`（§12.3）
9. `FirstReplyHook` —— 首回复风格提示；GIF/sticker 改写成 "hi"
10. `RAGAppendHook`（gated）—— 读异步 prefetch 结果进 context
11. `PromptRouterHook` —— **最后**：按 `template_group` 选版本，用上游信号（含 `{{CHARACTER_CARD}}`）重建 systemPrompt

**Post-Loop（顺序显著）**：
1. `AutoMemoryUpdateHook` —— rule-based `conversation_stage`/`last_active`
2. `AIPoliceHook` —— 安全 raw mutator（见 §7）
3. `GiftResponseCacheHook`（gated）—— gift 回复 Redis 去重缓存
4. `SaveHistoryHook` —— 存 user 侧（model 侧延后）
5. `CrossDHSignalWriteHook`（gated）—— 写每-DH 信号到 `user:xdh:{userId}` HASH（archetype rank 依赖，§12.2）
6. `LifeEventPostHook`（gated）—— 拥有全部 life-event Redis 副作用：校验 LLM 是否响应 TOPIC、提交冷却（§12.3）
7. `HistoryCompressionHook`（gated）—— 异步 LLM 摘要最老消息
8. `RAGIndexHook`（gated）—— 索引新 exchange 进 pgvector
9. `RecoveryAwaitReplyHook`（gated）—— 写「等待回复」标记
10. `InputMethodFormatterHook` —— **钉在最后**：raw→display 投影（本期可先直通）

> `StateSnapshotHook`、`EventHistoryWriteHook`（event-stream 架构）、IG 相关 hook 仍为后续扩展，接口已就位，按需 `register`。**Video tips 不是 hook**——它事件驱动，由 `TimCallbackService` 触发（§12.4）。

---

## 7. 安全流水线 / AIPolice

四道独立检查，**每层不假设上游已清理，fail-closed（宁丢不放）**：

1. **SendMessageTool** —— `OutputSmellTest.check()` + `sanitizeMessage()`（strip artifacts + 长度 + 去重），BLOCKED 让 LLM 循环内重试。
2. **AIPoliceHook**（post）—— 每条消息两层：正则硬匹配（<1ms）+ LLM 分类器（Flash-Lite，所有消息都过）。`Verdict`：NORMAL / CONTACT_INFO / AI_IDENTITY / OFFLINE_MEETING / REPETITION / PROFILE_CONFUSION。违规 → `classifyWithRegen`（HEAVY 模型重生成再审，超次数则丢消息）；全部 LLM 失败 → 当 AI_IDENTITY（fail-closed）；全局超时 → 安全兜底语料 + `scheduleRetry`。用 `RepetitionGuard`（专用 Lettuce channel）查重复。
3. **MessageSplitter** —— `stripXmlTags()` 预清理 + 按长度切割。
4. **TimClient 出口** —— 再做一次 `OutputSmellTest.check()`，BLOCKED 直接抛弃。

```java
public final class AIPoliceHook implements PostLoopHook {
    public void invoke(AgentContext ctx, AgentResult result) {
        List<String> kept = new ArrayList<>();
        for (String msg : result.sentMessages()) {
            Verdict v = classify(msg, ctx);                 // 正则 + LLM
            if (v == Verdict.NORMAL) { kept.add(msg); continue; }
            Optional<String> regen = classifyWithRegen(msg, v, ctx, MAX_REGEN);
            regen.ifPresent(kept::add);                     // 修不好 → 丢
        }
        result.setSentMessages(kept);                       // 清空 → runtime 提升 DROPPED_BY_POST_HOOK
    }
}
```

配套：`UserBlockChecker`（PG `user_block`，chase/recovery 尊重 block）、`MessageRefundService`（男用户消息被 CONTACT_INFO 拦截退 5 金币，`SELECT … FOR UPDATE` 幂等）。

---

## 8. LLM 路由与 hedge fallback

`LLMRouterImpl` 从 Nacos `aichat_v2_router_profiles` 热加载 providers + profiles；按 profile weight 选 primary slot，fallback slots 按各自 `timeoutMs` **staggered hedge**，首个成功者胜出、其余取消。Java 实现用 `CompletableFuture` + `anyOf` + 取消：

```java
public LLMResponse call(LLMRequest req, CallWeight weight) {
    Profile p = profileFor(weight);
    List<CompletableFuture<LLMResponse>> inflight = new ArrayList<>();
    for (int i = 0; i < p.slots().size(); i++) {
        Slot slot = p.slots().get(i);
        long delay = slot.hedgeDelayMs();                    // 交错启动
        var f = CompletableFuture.supplyAsync(
            () -> { sleep(delay); return clients.get(slot.provider()).call(req); },
            llmExec)                                         // 虚拟线程池
            .orTimeout(slot.timeoutMs(), MILLISECONDS);
        inflight.add(f);
    }
    LLMResponse winner = anyOfSuccess(inflight);            // 首个成功
    inflight.forEach(f -> f.cancel(true));                  // 其余取消
    statsStore.record(...); healthTracker.update(...);
    return winner;
}
```

providers 支持 `gemini`（Google 原生/AIRouter）、`openai`/`claude`（`callapi.top`）、`grok`（openrouter）、`bedrock`、`vertex`，per-provider `extraHeaders` 透传。`ArenaRouter`（HEAVY first-turn 多候选 + judge）作为可选外层，本期可不做。provider stats 写 Redis `pstats:*`，health 写 `ProviderHealthTracker`。

---

## 9. 数据层

### 9.1 Redis（3 逻辑实例，Lettuce，RESP2 pinned）

| 实例 | Nacos key | env fallback | 用途 |
|---|---|---|---|
| shared/dora | `redisCfg` | `REDIS_URI` | `user:info:*`、`user:online:rank`、chat 历史双写目标 |
| aichat 专用（主/写） | `aiChatV2RedisCfg` | `AICHAT_REDIS_URI` | 几乎全部 aichat 状态 |
| aichat 读副本 | `aiChatV2RedisReadCfg` | `AICHAT_REDIS_READ_URI` | 只读，回落主 |

关键 key（数据结构/TTL）：
- 历史：`chat:{u1}:{u2}` LIST（`[MMM dd, yyyy HH:mm] user:id: text`，LTRIM 200）
- trace：`trace:s:{id}`60s / `trace:{id}`30m / `trace:c:{id}`12h / `trace:a:{id}`3d / `trace_idx:{chatId}` ZSET 3d
- provider stats：`pstats:{provider}:{model}` ZSET 2h + `pstats:known` SET
- simpdog：`user:online:rank` ZSET、`follow_up:timers` ZSET + `follow_up:meta:{convId}` HASH、`holdback:*`
- chase：`chase:queue` ZSET、`chase:v1:*`、`chase:v2:*`
- recovery：`recovery:pending:{slot}`(sharded ZSET)、`recovery:queue`、`recovery:cooldown:*`
- 千人千面：`user:xdh:{userId}` HASH（`rank_counter` HINCRBY + `dh:{dhId}:rank` HSETNX frozen，archetype rank）
- life event：`life_event:conv:*`（当前故事线）、`life_event:cd:*`（冷却）、`life_event:short_cd:*`
- video tips：`video_tips_v2:{minId}:{maxId}` HASH（pair 维度）、`video_tips:dedup:{minId}:{maxId}`、`video_tips_v2_lock:{pairKey}`；shared Redis gating：`pwa:not:allowed:dispatch:set`、`u:pt:{userId}:remain`、`user:online:status:{id}`
- 其它：`gift_responses:{chatId}` LIST、`rep:open:{userId}` HASH、`{convId}:compressing` 锁

### 9.2 PostgreSQL（2 逻辑库，HikariCP）

| 库 | Nacos key | 池 | 用途 |
|---|---|---|---|
| main | `pgCfg`（默认库 archat） | max 10 | profile Redis-miss fallback + view_user_posts |
| RAG | `pgRagCfg` | max 20 | pgvector（与 dora 共享） |

- profile fallback：`userinfo`（`deleted_at IS NULL`）、`userinfo_pwa_club_mapping`；视频意愿回写 `user_double_check`（`VideoWillingnessWriter` co-write，§12.4）
- RAG（裸 SQL，per-statement 超时 2s）：`tim_msg_record`（`INSERT ON CONFLICT DO NOTHING`）、`tim_msg_vector`（`msg_vector_768` **768 维**，`(session_key,msg_key)` upsert），相似度 `1 - (msg_vector_768 <=> $q::vector)` 阈值 0.7

### 9.3 Kafka（只产不消，SASL_SSL + OAUTHBEARER，`acks=1`，`max.block.ms` 必配）

`im.message.send`（typing）/ `chat.round.update` / `tim.callback.events`（原始回调广播）。

### 9.4 TIM（REST，`console.tim.qq.com/v4/`）

`openim/sendmsg`（发 TIMTextElem/TIMCustomElem，出口过 smell test，max 2000 字符，retry×2）、`openim/admin_getroammsg`（冷启动拉漫游历史回灌）。UserSig HmacSHA256 缓存 180d。

### 9.5 Nacos（热更新，namespace 由 `ENV` 映射）

`global_db_config` / `common_config` / `aichat_v2_feature_flags` / `aichat_v2_router_profiles`（缺失则无 LLM provider，CRITICAL）/ `recovery_config`。用 `nacos-client` 的 `addListener` 做热 reload，配 `ConfigHolder`（`volatile` 引用原子替换）。

---

## 10. 主动触达（simpdog / chase / recovery）

三个**互相独立**的产品，共享 `ProactiveDispatcher`（把 EventTrigger 过一遍 AgentLoop 再投递）。统一后台调度基类：

```java
public abstract class PollingExecutor implements ScheduledExecutor {
    private final ScheduledExecutorService sched =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    protected abstract void tick();               // 子类实现
    public void start() { sched.scheduleWithFixedDelay(this::safeTick, initialDelay, period, ...); }
    private void safeTick() { try { tick(); } catch (Exception e) { onTickError(e); } }
    public void stop() { sched.shutdownNow(); }   // 优雅停机（@PreDestroy 统一 stop）
}
```

- **simpdog**：`ProactiveScheduler`（hourly 扫离线 60–90min）/ `FollowUpTimerExecutor`（30s 轮询 `follow_up:timers` ZSET，由 LLM 的 `set_follow_up` 驱动）/ `HoldBackExecutor`（天级，用户第 3/6 单触发）。多 pod 用 Redis `SET NX EX` 扫描锁。
- **chase**（AB gated，默认 OFF）：分布式 enqueue（单 pod 扫描入队 `chase:queue`）+ drain（每 pod）。V1 corpus-based 无 LLM；V2 三档 DEEP/MID/SHALLOW 情绪 prompt 一次性调 flash 生成。
- **recovery**（始终实例化，AB gated）：`RecoveryAwaitReplyHook` 标记 → 每 band（immediate/short/long）一个 `RecoveryScanner`（SETNX 锁）→ `RecoveryDrainExecutor` 经 `RecoveryRecall` 做资格校验（block → 日/周限额 → 冷却 → bot 检查）→ dispatcher 投递。

所有调度器在 `aichat-app` 收进 `List<ScheduledExecutor>`，`@PostConstruct` 统一 start、`@PreDestroy` 统一 stop。

---

## 11. RAG + 记忆压缩

**RAG**（三 hook 零等待，利用 IntentDetection 延迟掩盖）：`RAGPrefetchHook`（embed 用户消息 → 异步向量搜 `tim_msg_vector` → 写 Redis）→ `RAGAppendHook`（结果 ready 才读进 context，不阻塞）→ `RAGIndexHook`（post，索引新 exchange）。embedding 用 Vertex `gemini-embedding-001`（768 维）。

**并发隔离（对应原事故教训）**：RAG 的 JDBC/HikariCP 调用**必须**跑在**独立的有界线程池 + 独立 Hikari 池**（`ragExec` + `pgRagCfg`），绝不与主 `agentExec` 共用——这是本方案的硬约束（见 §13）。

**历史压缩**（`HistoryCompressionHook`，post，异步 fire-and-forget）：list 长度 ≥ 阈值（默认 300）→ LLM 摘要最老 `n-m` 条 → trim 到 m（默认 200）。用 Redis 分布式锁 `{convId}:compressing`（`SET NX EX 120` 原子）；`withTimeout(110s)` 略短于锁 TTL 确保 finally 主动释放。摘要保护 prefix cache（避免 prefix shifting 击穿）。

---

## 12. 千人千面 / Life Event / Video Tips

这三块都需要**少量 AB 分桶**。本期不做全量 AB 平台对接，只实现一个覆盖 4 个实验键的精简解析器。

### 12.1 精简版 AB 解析（三块的前置）

```java
public interface ABResolver {
    /** 命中失败/异常一律返回 defaultBucket（fail-safe），按 humanUserId + appName 分桶 */
    String resolve(String userId, String key, String defaultBucket, String appName);
}
```

- 实现用 **Caffeine** 本地缓存（热路径 0ms），底层可接 BytePlus SDK 或（本期）读 Nacos `aichat_v2_feature_flags` 里的静态分桶规则 + `hash(userId+key) % 100` 权重切分。
- 本期用到的键：`template_group`（character card 版本 default/v2/v3/v4）、`media_strategy_v1`（control/emoji/emoji_gif）、`cross_dh_v1`（off/on_a/on_b/on_c）、`life_event_v1`（control/treatment）。
- **fail-safe 铁律**：resolver 为 null 或异常 → 返回 control，输出与未开实验时逐字节一致。

### 12.2 千人千面（character card + emoji + archetype）

**三个正交维度**（互不影响，各自分桶）：

| 维度 | AB 键 | 作用 |
|---|---|---|
| character card 版本 | `template_group` | 决定人格卡文案版本（default/v2/v3/v4） |
| emoji/媒体表达 | `media_strategy_v1` | 决定 emoji 策略（control/emoji/emoji_gif） |
| per-user archetype | `cross_dh_v1` | 决定同一 DH 对不同用户是否呈现不同 reaction 模式 |

**CharacterCardHook（pre #2）**：不知道当前用户走哪版，**统一把所有版本的卡都算出来**写到 `ctx.characterCard / characterCardV3 / characterCardV4`，由 `PromptRouterHook` 按 `template_group` 在模板 `{{CHARACTER_CARD}}` 占位符处挑选。

```java
public final class CharacterCardHook implements PreLoopHook {
    private final ABResolver ab;
    private final ArchetypeResolver archetype;   // 可 null → baseline

    public void invoke(AgentContext ctx) {
        String dh = ctx.trigger().dhUserId();
        // 各版本确定性人格卡（同 dhUserId 恒定）
        ctx.setCharacterCard(  resolverV1.build(dh));
        ctx.setCharacterCardV3(resolverV3.build(dh));
        ctx.setCharacterCardV4(resolverV4.build(dh));

        // emoji 策略：按人格 temperament 查表（media_strategy_v1）
        String media = ab.resolve(ctx.humanUserId(), "media_strategy_v1", "control", ctx.appName());
        if (!media.equals("control")) {
            EmojiPolicy p = EmojiPolicyTable.forTemperament(resolverV4.temperament(dh));
            ctx.setEmojiPolicy(p);               // 替换 guardrails 里那行 emoji 规则
        }

        // per-user archetype：同 DH 对不同用户不同（cross_dh_v1 on_b/on_c）
        String xdh = ab.resolve(ctx.humanUserId(), "cross_dh_v1", "off", ctx.appName());
        if (archetype != null && (xdh.equals("on_b") || xdh.equals("on_c"))) {
            int rank = crossDHSignalStore.getOrAssignRank(ctx.humanUserId(), dh);  // 见下
            String twoLayer = archetype.render(dh, ctx.humanUserId(), rank, xdh);  // Surface + Archetype
            ctx.setCharacterCard(twoLayer);      // 两层卡覆盖 baseline
            ctx.setCharacterCardV3(twoLayer);
            ctx.setCharacterCardV4(twoLayer);
        }
    }
}
```

**确定性人格卡内核**（对应 `CharacterCardResolverV3/V4`）：
- 6 个表达维度（interest / lifeDetail / speechStyle / temperament / interactionPattern / conflictStyle）。
- **V3**：`SplitMix64` per-dim salt 做哈希切片，从各维度 pool 里选条目（修 V1/V2 的 hash entropy 不足）。
- **V4**：从 `dhUserId` 派生一个 **5 维 Big Five（OCEAN）核心向量**，6 个表达维度共读该核心 + 加权 argmax 选 pool 条目——杀掉「shy + 杠」这种维度独立采样的矛盾组合。pool = V3 文案 + 60 条人工 OCEAN 标注。
- 全确定性、无 LLM、无 I/O，可纯函数单测。

```java
// V4 核心：dhUserId → OCEAN 向量 → 各维加权 argmax
double[] ocean = deriveOcean(dhUserId);                 // SplitMix64 seeded, 5 维 [0,1]
for (Dim d : EXPRESSION_DIMS)
    card.put(d, pool.get(d).argmaxByWeight(ocean, d.weights()));
```

**Per-user archetype 两层卡**（仅 `on_b`/`on_c`，对应 `ArchetypeCardRenderer`）：
- **Layer 1 Surface（按 dhUserId，所有用户一致）**：取 V4 argmax 的 interest / lifeDetail / speechStyle（speechStyle 用剪枝后 6 条池，砍掉与 archetype voice 冲突的条目）。
- **Layer 2 Archetype（按 (userId, rank)，同 DH 对不同用户不同）**：archetype **接管** temperament/interactionPattern/conflictStyle，用一段 `## How you react`（COCKY/DISMISSIVE/CROSSING/FLIRTY/WARM 五情境）reaction block 取代后两维。
- 6 个 archetype 定义在 `resources/archetype-pool.json`，每个 `identity` 必须以 `EmojiPolicyTable` 的 temperament prefix 开头（emoji 实验耦合，用测试锁死这个不变量）。

**rank 派发 + CrossDHSignalWriteHook（post #5）**：
- `rank` = 用户进入实验后开的第 N 个新 DH，首次接触**原子派发后永久 freeze**。
- `CrossDHSignalStore`（Redis HASH `user:xdh:{userId}`）：`rank_counter` 用 `HINCRBY` 单调计数，`dh:{dhId}:rank` 用 `HSETNX` 冻结。存量旧 DH `getRank` 返回 null → 不注入 archetype（baseline，byte-identical）。

```java
public int getOrAssignRank(String userId, String dhId) {
    String key = "user:xdh:" + userId, field = "dh:" + dhId + ":rank";
    Long existing = redis.hget(key, field).map(Long::parseLong).orElse(null);
    if (existing != null) return existing.intValue();
    long next = redis.hincrby(key, "rank_counter", 1);   // 原子自增
    boolean won = redis.hsetnx(key, field, String.valueOf(next));  // 冻结
    return won ? (int) next : Integer.parseInt(redis.hget(key, field).get());
}
```

### 12.3 Life Event（每-DH 故事线）

**业务问题**：让 DH 像真人一样「有自己的生活」——既有跨天延续的**长线故事**（SUSTAINED），也有偶发的**瞬时事件**（刚发生的话题 TOPIC / 要离开一会 LEAVE）。gated by `life_event_v1`。

**LifeEventHook（pre #8，只读只注入，副作用全部延后）**：
```java
public void invoke(AgentContext ctx) {
    if (!ab.resolve(ctx.humanUserId(), "life_event_v1", "control", ctx.appName()).equals("treatment")) return;

    // 1) SUSTAINED：每-DH 长线故事，LIGHT 模型生成，Redis 缓存 7d
    String sustained = sustainedResolver.get(ctx.trigger().dhUserId());  // life_event:conv:{dh}，miss 才调 LLM
    ctx.appendSystemNote(sustained);

    // 2) INSTANT：概率性瞬时事件（受冷却门控）
    if (cooldownOk(ctx) && rng(ctx) < INSTANT_PROB) {
        LifeEvent e = pool.pickInstant();                 // TOPIC / LEAVE，来自 resources/life-events.json
        ctx.appendSystemNote(e.prompt());
        ctx.setLifeEventMeta(new LifeEventMeta(e, /*committed=*/false));  // ← 只标记，不写 Redis
    }
}
```

- `SustainedContextResolver`：Redis `life_event:conv:{dh}` 缓存 7d；miss 才用 LIGHT 模型生成一段人物近况，避免每轮调 LLM。
- **副作用延后**是关键：pre-hook 只设 `LifeEventMeta`，是否真正「用掉」这次事件由 post-hook 依据 LLM 有没有响应决定。

**LifeEventPostHook（post #6，拥有全部 Redis 副作用）**：
```java
public void invoke(AgentContext ctx, AgentResult result) {
    LifeEventMeta meta = ctx.lifeEventMeta();
    if (meta == null || meta.committed()) return;
    if (meta.event().type() == TOPIC && !llmActedOnTopic(result, meta))  // 校验 LLM 真的把话题带出来了
        return;                                                          // 没带出来 → 不消耗冷却，下轮可再试
    redis.setex(cooldownKey(ctx), COOLDOWN_SEC, "1");   // life_event:cd:{...} / short_cd
}
```
Redis：`life_event:conv:*`（故事线缓存）、`life_event:cd:*`（长冷却）、`life_event:short_cd:*`（短冷却）；pool 在 `resources/life-events.json`。指标经 `LifeEventMetrics`（注入率/消耗率）。

### 12.4 Video Tips + 视频通话转写

**事件驱动，非 hook、非 ScheduledExecutor**——由 `TimCallbackService` 在回调路径触发。

**A) Video tips（视频引导触达）—— `VideoTipsService`**：
- gated `video_tips_enabled`；在 `handleCallback` 里按 pair 维度触发。
- pair 维度 Redis：`video_tips_v2:{minId}:{maxId}` HASH（状态）、`video_tips:dedup:{minId}:{maxId}`（去重）、`video_tips_v2_lock:{pairKey}`（分布式锁，`SET NX EX`）。
- 投放前 gating（shared Redis，`VideoCallHelper`）：`pwa:not:allowed:dispatch:set`（不可投放集合）、`u:pt:{userId}:remain`（付费时长余额）、`user:online:status:{id}`（在线态）。
- 用户主动要视频 → 作为意图 `VIDEO_CALL_REQUEST`（IntentDetectionHook）+ `ActionPromptHook` 注入场景 prompt，不走本 service。

**B) 视频意愿回写 —— `VideoWillingnessWriter`**：
- 复活/引导路径命中「开视频意愿」时，co-write archat PG `user_double_check` 表（跑在独立 `pgExec` + 主 Hikari 池，带超时）。

**C) 视频通话转写回灌 —— `VideoCallTranscriptReader`**（`VIDEO_CALL_COMPLETED` 分支）：
- 接通的视频，端上转写经 user-service `POST /submitVideoCallTranscript` 写进**共享 Redis** 的 `chat:{a}:{b}` list，行格式 `[MMM dd, yyyy HH:mm] {username}:{userId}: We had a video call. Duration: %s. We talked about: %s`。
- aichat 主历史在专用 Redis，这条行不会自己进来。`VIDEO_CALL_COMPLETED` 回调时从共享 Redis 扫 list 头部（30 条 / 30 分钟内）找转写行，**原样（verbatim）LPUSH** 进 aichat Redis。
- **竞态处理**：转写上报与 TIM 回调是竞态——没找到时先存占位行 `"We had a video call. Duration: {d}s."`，**15s 后后台重试一次**（虚拟线程 + OTel context 传播）。
- verbatim 拷贝保证 `mergeHistories` 的跨源 dedup（`timestamp|userId|content`）能合并掉同一行不产生重复；**不走 syncWriter 镜像**（共享 Redis 已有原始行）。

```java
// VIDEO_CALL_COMPLETED 分支（TimCallbackService）
void onVideoCallCompleted(String a, String b, int durationSec) {
    Optional<String> line = transcriptReader.findRecent(a, b, 30, Duration.ofMinutes(30));  // 共享 Redis
    if (line.isPresent()) aichatRedis.lpush(ChatKey.of(a,b), line.get());        // verbatim
    else {
        aichatRedis.lpush(ChatKey.of(a,b), placeholder(durationSec));            // 先占位
        retryExec.schedule(() -> withOtel(() -> copyTranscript(a, b)), 15, SECONDS);  // 后台重试
    }
    videoTipsService.incrementCallCount(a, b);                                    // 通话计数
}
```

---

## 13. 配置、可观测、部署

- **配置**：Nacos 主源 + env fallback；`FeatureFlags` 热更新 `volatile` 原子替换。所有子系统按 flag + 基础设施可用性条件注册（对应 Kotlin composition root 的 `listOfNotNull`）。
- **Trace**：每 HTTP 请求一条 OTel trace（W3C 传播，入口 extract `traceparent`）；Redis 四层 staging→full→compact→archive + TTL 自动清理，无 background job。MDC `traceId`/`conv` 用 `try(var scope = MDC.putCloseable(...))` **作用域化**设置——禁止裸 `MDC.put`（虚拟线程/池线程跨会话复用会残留串值）。
- **Metrics**：Micrometer `/actuator/prometheus`，统一 `resource_pool_*` schema（Hikari/线程池/OkHttp/队列的 in_use/idle/max/pending/saturation）。
- **部署**：Docker（`-javaagent` OTel）+ K8s + Jenkins（对应现有 pipeline）。健康检查 `/actuator/health`。

---

## 14. 并发与容量纪律（Java 版硬规则）

原项目烧掉一整天事故的教训必须在 Java 版落成硬约束：

1. **每个阻塞资源独立线程域**：`agentExec`（虚拟线程）、`ragExec`（有界，配 pgRag Hikari）、`compressionExec`（单线程）、`chatSyncExec`（单线程 FIFO）、各 LLM `llmExec`。**绝不**让 RAG 的 JDBC 阻塞污染主循环线程域。
2. **一切阻塞调用带超时 + 可中断**：`Future.get(timeout)` / `orTimeout`；JDBC 配 `connectionTimeout=5s` + `socketTimeout=30s`（PG 默认无限）；Kafka `max.block.ms=2000`。
3. **池大小是容量决策**：`maximumPoolSize` / 信号量许可数旁边写清算式（peakRPM × 每次占用时长 × 头寸）。
4. **后台任务传播 trace context**：提交到执行器时包一层 `Context.current().wrap(runnable)`（OTel），否则 span 变孤儿。
5. **禁用阻塞-only 库**：Jedis→Lettuce、Apache HttpClient sync→JDK HttpClient、AWS SDK v1→v2 async。
6. **虚拟线程注意**：不要在虚拟线程里用 `synchronized` 包大段阻塞（会 pin 载体线程）——热路径锁用 `ReentrantLock`。

**性能排障纪律**：先 grep 生产错误日志（多数事故日志里有直接相关异常）→ 判断是否多个无关模块同时变慢（是则共享资源争用，找谁持有共享资源而非谁看起来慢）→ 只读诊断（`/actuator`、thread dump、SLOWLOG）验证假设后再改代码。

---

## 15. Java 特有坑（预先规避）

- **Jackson 可见性**：非 JavaBean getter（`type()` record 访问器）需 `@JsonProperty`；record 用 `jackson-databind` 3.x 或 `parameter-names` module。别把 `@JsonProperty` 放 private field 上指望它序列化。
- **JDK HttpClient 默认偏好 HTTP/2**：对端是 HTTP/1.1（Node/轻量服务）时会报 `HTTP/1.1 header parser received no bytes`——显式 `.version(HTTP_1_1)`。
- **虚拟线程 pin**：`synchronized` + 阻塞 I/O 会钉住平台线程，用 `ReentrantLock` 替代。
- **MDC 泄漏**：见 §12，作用域化设置。

---

## 16. 分阶段落地计划

| 里程碑 | 交付 | 验收 |
|---|---|---|
| **M0 骨架**（1w） | 多模块 Maven + Spring Boot 启动 + Nacos/Redis/PG provider + 健康检查 | `/actuator/health` 绿，Nacos 热更新生效 |
| **M1 核心闭环**（2w） | Trigger/Tool/Hook 接口 + AgentLoop + AgentRuntime + LaneManager + send_message + 单 provider LLMClient | `POST /api/agent/run` 能跑出一条回复，trace 落 Redis |
| **M2 TIM 打通**（1.5w） | TimCallbackController + TimClient(REST) + CancellableReplyDelivery + 投递后存历史 + 历史/记忆 store | 真机 TIM 回调 → DH 回复到达，历史正确 |
| **M3 安全**（1.5w） | OutputSmellTest + AIPoliceHook(classify+regen) + RepetitionGuard + block/refund | 违规消息被拦/重生成/丢弃，退款幂等 |
| **M4 LLM 路由**（1w） | LLMRouter hedge fallback + 多 provider + stats/health | 主 provider 挂时 fallback 命中，stats 可查 |
| **M5 上下文 hooks**（1.5w） | Conversation/History/Intent/ActionPrompt/FirstReply/PromptRouter | profile/memory/意图注入生效，意图 skipLoop 正确 |
| **M6 主动触达**（2w） | PollingExecutor 基类 + simpdog 三档 + recovery band scanner（chase 可 gated OFF） | 离线用户被追发，recovery 资格校验正确 |
| **M7 RAG + 压缩**（1.5w） | pgvector 索引/预取（隔离池）+ 历史压缩 | 相似历史召回，长历史压缩不丢近期消息 |
| **M8 千人千面 + life event + video tips**（2w） | 精简 AB 解析 + CharacterCardHook(V1/V3/V4)+emoji+archetype 两层卡 + CrossDHSignalStore rank + LifeEventHook/PostHook + VideoTipsService + 转写回灌 | 同 DH 对不同用户 reaction 不同；故事线注入且冷却正确；`VIDEO_CALL_COMPLETED` 转写落历史无重复 |
| **M9 硬化**（1w） | 并发隔离审计 + metrics + 压测 + 冒烟测试套件 | 300+ RPM 无池饱和，故障域隔离验证 |

总计约 15 周（1 人）/ 可并行压缩到 ~8–9 周（3 人按模块拆分：core / infra / features）。千人千面确定性人格卡是纯函数，可与 M2–M7 并行开发、M8 集成。

---

## 17. 测试策略

- **单元**：JUnit 5 + AssertJ，覆盖 AgentLoop 状态机、AIPolice verdict、MessageSplitter、LaneManager 合并/取消、hedge 路由选主。
- **千人千面确定性**：CharacterCardResolverV3/V4 纯函数，锁定「同 dhUserId 恒定输出」+「V4 无矛盾维度组合」+「archetype identity 前缀 ∈ EmojiPolicyTable temperament」不变量；CrossDHSignalStore rank 派发的原子性/冻结性（并发 `getOrAssignRank` 只有一个赢家）。
- **集成**：Testcontainers 起 Redis + pgvector + （可选）Kafka；mock LLM provider（固定响应）跑端到端。
- **冒烟**：`docker compose up` 起依赖 + Nacos-less 启动 + 手动/脚本打 HTTP（对应 Kotlin 版做法）。
- **并发/容量**：Gatling/JMeter 压 `POST /`，断言 `resource_pool_*` saturation 不超阈、无跨模块连锁变慢。
- **架构守护**：ArchUnit 断言依赖方向（core 不依赖 infra、tools 不 import runtime）。

---

## 18. 风险与取舍

| 风险 | 缓解 |
|---|---|
| 虚拟线程 pin（synchronized + 阻塞） | 代码规范 + `-Djdk.tracePinnedThreads` 监控，热路径用 ReentrantLock |
| function-calling `rawModelParts` 处理不当断连 | 单测锁定「原样回传」，各 provider client 有 wire-format 契约测试 |
| RAG/JDBC 污染主线程域（原事故复现） | 编译期/评审强制独立池；metrics 看板独立监控 rag 池 saturation |
| Nacos 不可用启动 | env fallback + in-memory 兜底，`aichat_v2_router_profiles` 缺失显式 CRITICAL 日志 |
| Spring Boot 相对 Ktor 更重 | 关闭不用的 autoconfig；若团队敏感可换 Javalin，接口不变 |
| 与 dora-service 共享 Redis/PG 数据格式 | 严格对齐 `chat:{a}:{b}` 行格式、`tim_msg_*` schema，加契约测试防漂移 |
| archetype rank 派发竞态（多 pod 首触） | `HINCRBY` + `HSETNX` 原子组合保证 freeze 唯一；并发测试锁死 |
| life event 冷却/副作用被 pre 提前消耗 | 副作用全部延后到 `LifeEventPostHook`，pre 只标记 meta，TOPIC 未响应不消耗冷却 |
| 视频转写与 TIM 回调竞态 | 占位行 + 15s 后台重试 + `mergeHistories` 跨源 dedup 三重兜底 |

---

*本方案范围 = 核心闭环 + 主动触达/RAG + **千人千面 / life event / video tips**，对齐 Kotlin 基线 `01d1c5a4`。CE/Instagram 代理、BytePlus 埋点、Tianshu、event-stream 架构、全量 AB 平台作为「新增 Hook/Tool/Client」扩展点接入，不改核心循环。*
