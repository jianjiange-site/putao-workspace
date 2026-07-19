# match-service 实现计划

> 日期: 2026-07-18
> 服务: `match-service`
> 阶段: P1 骨架 + 核心业务实现

## 总体目标

根据 `doc/specs/match-service-prd-tech.md` 完成 match-service 的骨架与核心业务实现,包括:

1. proto 定义 (`proto/match/match.proto`)
2. 数据库 schema (Flyway V1-V6)
3. 实体类 (Entity)
4. Mapper 接口
5. Manager 编排层
6. Service 业务层
7. gRPC Server / Service
8. Controllers (REST for gateway)
9. 调度任务 (D1 cron + 3 个 DH 计划 scheduler)
10. Clients (user/payment/im)
11. 配置 (Redis/Redisson/Scheduler/Application)

## 关键设计点回顾

- **D0 实时冷启动召回** — 无历史右划时按用户自身画像为 prior
- **D1 日更 cron** — 美东 03:00 (UTC 07:00) 离线生成
- **Redis LIST 队列** + LPOP 即消费,二次过滤 match:swiped SET
- **DH 延迟匹配** — 进程内 TaskScheduler 15s-2min
- **配额 Redis HASH** — `match:quota:<uid>:<yyyymmdd>`
- **match 表** — 主键 (user_id_low, user_id_high) UNIQUE
- **like_record / visit_record** — UPSERT UNIQUE(from, to)
- **dh_interaction_task** — 短生命周期,executor 硬删

## 阶段拆解

### Phase 1 — proto 定义
- [ ] 扩展 `proto/match/match.proto` 实现 7.4 节的全部 RPC (GetTodayFeed / Swipe / SuperHi / ListMatches / GetQuota / ListLikesOfMe / ListVisitsOfMe / RecordVisit)
- [ ] 编译 proto 模块

### Phase 2 — 数据库迁移
- [ ] V1__init_swipe_match.sql: user_swipe_history / match / match_outbox
- [ ] V2__init_like_visit.sql: like_record / visit_record
- [ ] V3__init_dh_interaction.sql: dh_interaction_task
- [ ] V4__seed_match_constants.sql: (no-op placeholder for future)

### Phase 3 — 实体 / Mapper / 常量 / 异常
- [ ] constant: MatchErrorCode / MatchRedisKey / MatchSource / SwipeDirection / SubscriptionTierCache / LikeVisitSource / DhPlanScene / DhTaskAction
- [ ] entity: UserSwipeHistoryEntity / MatchEntity / MatchOutboxEntity / LikeRecordEntity / VisitRecordEntity / DhInteractionTaskEntity
- [ ] mapper: 6 个 MyBatis-Plus Mapper
- [ ] exception: MatchBizException / GlobalExceptionHandler

### Phase 4 — Manager 层
- [ ] UserSwipeHistoryManager / MatchManager / MatchOutboxManager
- [ ] LikeRecordManager / VisitRecordManager / DhInteractionTaskManager
- [ ] QuotaRedisManager (HINCRBY 配额)

### Phase 5 — gRPC Clients
- [ ] UserServiceClient: batchGetProfile / listDhCandidates / nearbyUsers / getUserType
- [ ] PaymentServiceClient: getSubscription (5min cache) / consumeCoins
- [ ] ImServiceClient: ensureConversation / sendSystemMessage / triggerDhOpening / listOnlineUserIds / listRecentOfflineUsers

### Phase 6 — Recommend 域
- [ ] PreferenceBuilder (用户右划画像聚合)
- [ ] CandidateRecaller (BH / DH 双池召回)
- [ ] Ranker (D1 打分公式)
- [ ] ColdStartService (D0 实时召回 + merge)

### Phase 7 — Service 层
- [ ] QuotaService (读 / 扣 / 回滚)
- [ ] FeedService (GetTodayFeed LPOP + 二次过滤 + 重建)
- [ ] SwipeService (LEFT/RIGHT/SUPER_HI)
- [ ] MatchService (createMatch + 副作用)
- [ ] DhDelayedMatchService (TaskScheduler)
- [ ] LikeVisitService (List / Record)
- [ ] MatchOutboxService (副作用投递)
- [ ] PreferenceService (Redis cache)

### Phase 8 — gRPC 实现
- [ ] MatchGrpcServer (8 个 RPC)
- [ ] MatchGrpcService (业务调用层)

### Phase 9 — Scheduler
- [ ] D1QueueScheduler (cron UTC 07:00)
- [ ] MatchOutboxRetry (fixedDelay 30s)
- [ ] OnlinePlanGenerator (fixedDelay 60s + Redisson lock)
- [ ] OfflinePlanGenerator (fixedDelay 20min + Redisson lock)
- [ ] LikeVisitorTaskExecutor (fixedDelay 60s + Redisson lock)

### Phase 10 — Config / Application
- [ ] MatchServiceApplication
- [ ] CommonConfig / MybatisPlusConfig / RedisConfig / RedissonConfig / GrpcClientConfig / TaskSchedulerConfig / AsyncConfig

### Phase 11 — Controllers
- [ ] FeedController / SwipeController / MatchController / LikeVisitController / HealthController

### Phase 12 — 验证
- [ ] mvn compile 验证
- [ ] 写 progress 日志

## 风险与决策

1. **im-service 新增 RPC** — TriggerDhOpening / EnsureConversation / SendSystemMessage 在 match-service 之前不在 im.proto 中。本次先在 im.proto 加上,match-service 调用依赖 im-service 后续升级。
2. **user-service 新增 RPC** — listDhCandidates / nearbyUsers 在 match-service 之前不在 user.proto 中。同理,user.proto 加上,user-service 后续补实现。
3. **D0 召回依赖 user-service** — D0 强依赖 listDhCandidates + nearbyUsers,本期实现预留接口调用,user-service 未实现前 D0 召回会失败,先完成 happy path 代码 + 注释说明。
4. **数据库 schema** — 严格按 PRD 7.2 建表(雪花 ID、TIMESTAMPTZ、UNIQUE 约束、partial index)。
5. **match.source** — 用 String 而非 Java enum,便于运营动态扩展。

