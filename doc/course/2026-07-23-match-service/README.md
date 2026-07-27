# Match-Service 学习笔记

> 学习日期：2026-07-23
> 学习范围：match-service — 推荐 Feed（卡片队列）+ 划卡 + 匹配触发 + Like/Visit 互动体系

## 本次学习内容

- [业务功能设计](./prd.md) - D0/D1 卡片队列、划卡/SuperHi/Match 全流程、Like/Visit 互动、DH 模拟计划
- [核心知识点](./knowledge.md) - 双池召回+打分公式、Redis LIST+LPOP、DH 延迟匹配、Outbox 副作用、真实感调度
- [面试问答（通用版）](./interview-qa.md) - 高频问题、追问准备、项目讲解话术
- [面试问答（可靠性专版）](./interview-qa-reliability.md) - 50+ 高频问题：以"首页卡片 → 划卡 → match → IM 副作用"全链路可靠性为主轴，串联推荐召回、Redis 队列、配额原子性、Outbox、DH 模拟计划、跨服务降级

## 学习目标

- [ ] 理解 dating app 首页"卡片右滑"的端到端流程
- [ ] 掌握 D0 冷启动 + D1 日更推荐的双轨设计与两池独立召回模型
- [ ] 掌握 BH/DH 双用户体系下的延迟匹配设计（防穿帮）
- [ ] 理解 match 副作用的 Outbox 兜底机制（跨服务 RPC 失败不阻塞 UX）
- [ ] 掌握 Like/Visit 列表背后 DH 模拟计划的真实性约束
- [ ] 能讲清 match-service 与 user/payment/im 三方服务的 gRPC 边界

## 关联知识

- 设计文档：`doc/specs/match-service-prd-tech.md`（PRDTech 全集）
- 实现计划：`doc/plans/2026-07-18-match-service-p1.md`
- 核心代码：`dating-server/match-service/src/main/java/com/dating/match/`

## 核心代码地图

```
match-service/src/main/java/com/dating/match/
├── MatchServiceApplication.java
├── controller/                       # REST 入口
│   ├── FeedController                # GetTodayFeed
│   ├── SwipeController               # Swipe / SuperHi
│   ├── MatchController               # ListMatches / GetQuota
│   ├── LikeVisitController           # ListLikes / ListVisits / RecordVisit
│   └── HealthController
├── grpc/MatchGrpcService.java        # gRPC 服务端实现
├── service/
│   ├── FeedService                   # LPOP + 二次过滤 + 重建
│   ├── SwipeService                  # LEFT/RIGHT 串行化 + 触发 match
│   ├── SuperHiService                # 订阅赠送 + 金币双路径 + 立即 match
│   ├── MatchService                  # match 创建 + 副作用 outbox
│   ├── DhDelayedMatchService         # 进程内 TaskScheduler 15s~2min
│   ├── QuotaService                  # Redis HASH HINCRBY + 回滚
│   ├── ColdStartService              # D0 实时召回 + merge
│   ├── D1Generator                   # D1 离线召回 + 打分 + merge
│   ├── LikeVisitService              # 列表拼装 + 异步 RecordVisit
│   ├── MatchOutboxService            # outbox 投递
│   └── DhInteractionPlanService      # ONLINE/OFFLINE 生成 + Executor
├── recommend/
│   ├── PreferenceBuilder             # 30 天右划画像聚合
│   ├── CandidateRecaller             # BH/DH 双池召回(L0~L3 渐进)
│   ├── Ranker                        # 打分公式(0.45/0.30/0.15/0.10 + 2 bonus)
│   ├── FeedMerger                    # 池内排序 + 按比例 merge
│   └── PreferenceProfile             # 偏好画像 VO
├── scheduler/
│   ├── D1QueueScheduler              # @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
│   ├── MatchOutboxRetry              # @Scheduled(fixedDelay = 30_000)
│   ├── OnlinePlanGenerator           # @Scheduled(fixedDelay = 60_000) + Redisson lock
│   ├── OfflinePlanGenerator          # @Scheduled(fixedDelay = 1_200_000) + Redisson lock
│   └── LikeVisitorTaskExecutor       # @Scheduled(fixedDelay = 60_000) + Redisson lock
├── manager/                          # 数据访问编排
│   ├── MatchManager                  # INSERT IGNORE + UNIQUE 触发的 ERROR 日志
│   ├── LikeRecordManager             # UPSERT + softDeleteByPair
│   ├── UserSwipeHistoryManager
│   ├── MatchOutboxManager
│   ├── VisitRecordManager
│   └── DhInteractionTaskManager      # batchInsert/scanDue/hardDelete
├── client/                           # 三个跨服务 gRPC stub
│   ├── UserServiceClient             # batchGetProfile / listDhCandidates / nearbyUsers / getUserType
│   ├── PaymentServiceClient          # getSubscription / consumeCoins
│   └── ImServiceClient               # ensureConversation / sendSystemMessage / listOnline/Offline
├── mapper/                           # MyBatis-Plus(一张表一个)
├── entity/                           # 6 张 PG 实体
├── vo/                               # 13 个 VO
├── constant/                         # MatchRedisKey / MatchErrorCode / MatchSource / SubscriptionTier / ...
├── config/
│   ├── MatchProperties               # Nacos 配置注入(权重/Nacos key 汇总)
│   ├── RedissonConfig                # 分布式锁
│   ├── MybatisPlusConfig
│   └── ...
└── exception/
    ├── MatchBizException
    └── GlobalExceptionHandler
```

## 一句话定位

> match-service 是 dating app 首页"卡片右滑即喜欢"全链路的**唯一权威服务**：
> 它生成两池召回的 240 张卡片队列、串行化用户的划卡动作、按 BH/DH 矩阵触发 match、把跨服务副作用丢进 outbox 兜底、并在后台跑 DH 模拟 like/visit 让真人用户感到"被关注"。

## 重点数字（背下来能应付 80% 追问）

| 数字 | 含义 | 出现位置 |
|------|------|---------|
| **240** | D0/D1 队列容量（每人） | `props.getD1QueueSize()` |
| **5 / 80 / 120 / 120** | FREE/WEEKLY/MONTHLY/YEARLY 每日可划卡上限 | `SubscriptionTierConst` |
| **5 / 10 / 15 / 15** | 各档每日右划上限 | 同上 |
| **0 / 0 / 1 / 1** | 各档每日 Super Hi 赠送数（周度不送） | 同上 |
| **100** | Super Hi 金币价 | `SUPER_HI_COIN_PRICE` |
| **0.20 / 0.40** | D0 / D1 bh_ratio 运营基线 | `MatchProperties` |
| **0.45 / 0.30 / 0.15 / 0.10** | 打分公式 base 权重（BH/DH 共用） | `bh_weights` / `dh_weights` |
| **+0.20 / +0.20** | mutual_like_bonus / new_bh_bonus 加性 bonus | `MatchProperties` |
| **3** | new_bh_window_days（新人曝光窗口） | 同上 |
| **15s ~ 2min** | DH 延迟匹配窗口 | `DhDelayedMatchService.MIN/MAX_DELAY_MS` |
| **60s / 20min / 60s / 30s** | ONLINE / OFFLINE / Executor / OutboxRetry 间隔 | scheduler 注解 |
| **7200s / 1200s / 10800s** | DH 计划 cooldown / 离线阈值 / 离线回看 | Nacos |
| **5 ~ 10 / 3 ~ 6** | ONLINE / OFFLINE 单次生成 DH 数 | 同上 |
| **15 / 25** | 单 BH 24h 内 DH like / visit 上限 | 同上 |
| **7 天 / 36h** | feed LIST TTL / quota HASH TTL | `MatchProperties` |

## 学习路径建议

1. 先看 `prd.md` 把功能清单和规则吃透（什么算 match、什么延迟、什么触发 outbox）
2. 再看 `knowledge.md` 把每个技术决策的 why 理解（为什么是 LPOP、为什么延迟、为什么 outbox）
3. 最后看 `interview-qa.md` 整理话术，模拟被问场景回答
