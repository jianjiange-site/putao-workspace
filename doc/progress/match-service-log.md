# match-service 学习日志

> 按 `.cursorrules` 中的"Vibe-Coding 文档组织规范",本文件用追加式记录每次学习 session 的完成 / 遗留 / 卡壳点。

---

## 2026-07-23 (Session #1)

**目标**:从零开始吃透 match-service,把 prd.md / knowledge.md / interview-qa.md 已有内容与真实代码对照,补 code evidence

**完成**:
- [x] 读 `README.md` —— 抓全局地图(14 个模块 / 11 个重点数字 / 6 个核心模块)
- [x] 扫 `prd.md` 14 个功能清单(F001-F014)
- [x] 读 PRD `doc/specs/match-service-prd-tech.md` 全文(1100+ 行)
- [x] 对照代码验证所有 12 个知识点,新增 **`code-evidence.md`** —— 12 个知识点逐个附真实代码片段 + 文件:行号
- [x] 12 道面试高频问题准备完毕(`interview-qa.md`)

**已验证(代码与文档 100% 一致)**:
- `SwipeService.swipe` 70-89 行 — Redisson 锁 + 幂等
- `QuotaService.consumeRightSwipe` 50-76 行 — HINCRBY + 回滚
- `QuotaService.consumeSuperHi` 85-118 行 — 三字段扣减 + 金币路径
- `FeedService.getTodayFeed` 80-126 行 — while LPOP + 二次过滤
- `FeedService.markSwiped` 158-161 行 — SADD swiped SET
- `DhDelayedMatchService.scheduleDelayedMatch` 49-69 行 — TaskScheduler + ThreadLocalRandom
- `MatchService.createMatch` 53-70 行 — @Transactional + outbox 入队
- `MatchManager.insertIgnoreConflictWithLog` 58-78 行 — UNIQUE + ERROR 日志
- `MatchOutboxService.scheduleRetry` 89-94 行 — 指数退避公式
- `MatchOutboxRetry.run` 19-20 行 — fixedDelay=30_000
- `DhInteractionPlanService.generateOne` 112-236 行 — 三道闸 + execute_time 均匀分布
- `OnlinePlanGenerator.run` 27-53 行 — tryLock(0, ...) 抢不到锁 skip
- `CandidateRecaller.applyDhLevel` 99-127 行 — L0~L3 渐进
- `FeedMerger.merge` 83-116 行 — 按比例 merge + DH 补齐
- `FeedMerger.computeBhRatioD1` 119-128 行 — D1 个性化比例偏移
- `Ranker.scoreBh` 35-54 行 — D1 打分公式 + 两个加性 bonus
- `UserServiceClient.batchGetProfile` 42-57 行 — 失败降级返回空
- `ImServiceClient.listOnlineUsers` 81-94 行 — 失败降级返回空集合

**遗留(下一个 session 处理)**:
- [ ] D1Generator 主代码(本次没读,优先级 P1 —— PRD 4.2.1 ~ 4.2.4 都在这)
- [ ] LikeVisitService / ListLikesOfMe / ListVisitsOfMe / RecordVisit 主链路
- [ ] SuperHiService 完整金币 RPC 失败回滚路径(本次只读了 QuotaService)
- [ ] match_outbox manager 的 markDone / markRetry / listPending 实现
- [ ] DhInteractionTaskManager batchInsert / scanDueTasks / hardDelete
- [ ] LikeRecordManager softDeleteByPair / upsert 实现
- [ ] PreferenceBuilder 真实代码(本次只用了接口)
- [ ] 面试话术录音训练(30s / 2min / 5min 三个版本)
- [ ] Q3 / Q6 / Q11 三道题的口述练习(自己录音听)

**AI 行为备注 / 卡壳点**:
- 一开始想整理"代码与文档差异",对照了十几个文件后**全部一致**,没有差异 —— 这本身就是信号:match-service 是早期产物,文档写得非常贴近实现
- code-evidence.md 不应该重复 knowledge.md 已有的"场景/问题/方案"叙事,**只做代码行号索引** —— 这是定位差异
- match.service 还没有 progress log(本次是第一次学习),按 vibe-coding 规范后续追加
