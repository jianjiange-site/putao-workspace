# Post-Service 功能设计文档（类PRD）

> 帖子服务完整功能梳理，包括所有接口、业务流程、数据流转

---

## 模块概述

### 业务定位

帖子服务是 Dating App 的核心内容模块，负责：
- 用户发布动态（文本 + 图片）
- 用户互动（点赞、评论）
- Feed 推荐流展示

### 核心价值

1. **内容沉淀**：让用户创造和消费 UGC 内容
2. **社交互动**：通过点赞、评论建立用户间连接
3. **推荐分发**：智能分发内容，让优质内容曝光

### 用户角色

| 角色 | 行为 |
|------|------|
| 内容消费者 | 浏览 Feed、查看帖子详情、点赞、评论 |
| 内容创作者 | 发帖、删帖、管理自己的内容 |

---

## 功能清单

| 功能编号 | 功能名称 | 功能描述 | 优先级 |
|---------|---------|---------|--------|
| F001 | 发帖 | 用户发布文本+图片动态 | P0 |
| F002 | 查看帖子详情 | 查看单条帖子的完整信息 | P0 |
| F003 | 查看用户帖子列表 | 查看某个用户发布的所有帖子 | P0 |
| F004 | 删除帖子 | 作者删除自己的帖子 | P0 |
| F005 | 点赞 | 对帖子进行点赞/取消点赞 | P0 |
| F006 | 评论 | 对帖子发表一级评论 | P0 |
| F007 | 查看评论列表 | 分页查看帖子的评论 | P0 |
| F008 | 删除评论 | 作者删除自己的评论 | P1 |
| F009 | 推荐 Feed | 获取个性化推荐 Feed 流 | P0 |

---

## 功能详情

### F001：发帖

#### 1. 功能描述

用户发布一条新动态，支持文本（≤1024字符）和图片（≤9张）。

#### 2. 业务规则

- 文本必填，长度 1-1024 字符
- 图片可选，最多 9 张
- 图片必须是通过预签名 URL 已上传到 MinIO 的
- 图片 key 格式：`post-image/{user_id}/{yyyymm}/{uuid}.{ext}`
- 发帖后自动进入冷启动池，5 分钟后有机会进入热门池
- 发帖后异步写扩散到粉丝的 timeline

#### 3. 用户交互流程

```
1. 用户编辑文本
2. 用户选择图片（最多9张）
3. App 调用预签名 URL 接口获取上传地址
4. App 直接上传图片到 MinIO
5. App 调用发帖接口，传入文本和图片 key 列表
6. 服务端：
   a. 生成雪花 ID 作为 post_id
   b. 事务写入 posts、post_images、post_stats 三张表
   c. 写入 Redis 缓存
   d. 加入冷启动池
   e. 发送 MQ 消息做写扩散
7. 返回发帖成功，显示新帖子
```

#### 4. 数据流转

```
App
  │
  │ 1. 预签名 PUT URL 请求
  ▼
mobile-gateway → post-service.GetPresignedUrl
                      │
                      ▼
                 MinIO (返回上传地址)
                      │
  │ 2. 直接上传图片
  ▼
MinIO (存储图片，key = post-image/{user_id}/{yyyymm}/{uuid}.jpg)
                      │
  │ 3. 发帖请求 CreatePost(content, image_keys)
  ▼
mobile-gateway → post-service.CreatePost (gRPC)
                      │
                      ▼
┌─────────────────────────────────────────────────────────┐
│  PostWriteService.createPost()                          │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 3.1 @Transactional                                │   │
│  │     - 雪花ID生成 post_id                         │   │
│  │     - INSERT posts                               │   │
│  │     - INSERT post_images (批量)                 │   │
│  │     - INSERT post_stats (初始值 0)               │   │
│  └─────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 3.2 Redis                                       │   │
│  │     - HSET post:detail:{post_id} 缓存详情       │   │
│  │     - ZADD cold_start:pool:{gender} 入冷启动池  │   │
│  └─────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 3.3 MQ                                         │   │
│  │     - PostFanoutProducer.send() 发送写扩散消息  │   │
│  │     - Topic: putao-dating-dev-post-fanout-v1  │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                      │
                      ▼
PostFanoutConsumer 消费消息
                      │
                      ▼
ZADD user:timeline:{follower_id} 写入粉丝 timeline
```

#### 5. 接口设计

**RPC 接口**：`CreatePost`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | string | 是 | 帖子内容，1-1024字符 |
| image_keys | string[] | 否 | 图片 key 列表，最多9个 |
| user_id (from metadata) | int64 | 是 | 发帖人ID，从 gateway 注入 |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "post_id": 1234567890123456789,
    "created_at": "2026-07-16T10:30:00Z"
  }
}
```

**错误码**：
| code | 说明 |
|------|------|
| 4001 | content 为空 |
| 4002 | content 超长 |
| 4003 | 图片数量超限 |
| 4004 | 图片 key 为空 |
| 4030 | 权限不足 |

#### 6. 边界情况

| 场景 | 处理方式 |
|------|----------|
| 图片上传失败 | 提示用户重新上传 |
| MQ 发送失败 | 记录指标，不阻塞返回。5分钟后热门池重建可兜底 |
| Redis 不可用 | 直接落库，缓存 miss 时回源 DB |
| 事务失败 | 抛异常，App 提示重试 |

#### 7. 监控埋点

- `post.create.success`：发帖成功计数
- `post.create.fail`：发帖失败计数
- `post.fanout.produce.fail`：写扩散失败计数

---

### F002：查看帖子详情

#### 1. 功能描述

查看单条帖子的完整信息，包括内容、图片、点赞数、评论数。

#### 2. 业务规则

- 只能查看未删除的帖子
- 帖子状态为"审核中"时只作者可见
- 返回的点赞数 = DB 基准值 + Redis 增量
- 图片只返回 key，App 自己拼接 URL

#### 3. 用户交互流程

```
1. 用户点击帖子入口
2. 请求帖子详情接口
3. 服务端查询：
   a. 先查 Redis 缓存
   b. 缓存 miss 则查 DB
   c. 查询点赞数（DB + Redis 增量）
4. 返回帖子详情
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.GetPostDetail (gRPC)
                                    │
                                    ▼
                          ┌─────────────────┐
                          │ Redis Cache      │
                          │ HGET post:detail:│
                          │ {post_id}        │
                          └────────┬────────┘
                                   │ hit/miss
                                   ▼
                          ┌─────────────────┐
                          │ PostgreSQL      │
                          │ posts +         │
                          │ post_images +    │
                          │ post_stats      │
                          └────────┬────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
               posts 表       images 表       stats 表
            (内容+作者)      (图片列表)     (计数基准)
                    │              │              │
                    └──────────────┼──────────────┘
                                   ▼
                          ┌─────────────────┐
                          │ Redis INCR      │
                          │ post:stat:incr: │
                          │ {post_id}:likes │
                          └────────┬────────┘
                                   │
                                   ▼
                           返回合并后的计数
```

#### 5. 接口设计

**RPC 接口**：`GetPostDetail`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| post_id | int64 | 是 | 帖子ID |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "post_id": 1234567890123456789,
    "user_id": 1001,
    "content": "今天的天气真好",
    "image_keys": ["post-image/1001/202607/c9fd...d2.jpg"],
    "like_count": 1100,
    "comment_count": 23,
    "created_at": "2026-07-16T10:30:00Z"
  }
}
```

#### 6. 边界情况

| 场景 | 处理方式 |
|------|----------|
| 帖子不存在 | 返回 4005 错误码 |
| 帖子已删除 | 返回 4005 错误码 |
| 帖子审核中 | 仅作者可见，其他用户返回 4005 |
| Redis 全挂 | 直接查 DB，计数不准但能返回内容 |

---

### F003：查看用户帖子列表

#### 1. 功能描述

查看某个用户发布的所有帖子，按时间倒序排列。

#### 2. 业务规则

- 只返回未删除的帖子
- 支持游标分页，使用 post_id 作为游标
- post_id 雪花 ID 单调递增，可代替时间游标

#### 3. 用户交互流程

```
1. 用户进入个人主页
2. 请求用户帖子列表（首次不带游标）
3. 服务端按 post_id 倒序查询
4. 返回帖子列表 + next_cursor
5. 用户滚动到底部，加载更多（带 next_cursor）
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.ListUserPosts (gRPC)
                                    │
                                    ▼
                          ┌─────────────────┐
                          │ PostgreSQL      │
                          │ SELECT * FROM posts
                          │ WHERE user_id = ?
                          │   AND deleted = 0
                          │   AND post_id < {cursor}
                          │ ORDER BY post_id DESC
                          │ LIMIT {page_size}
                          └─────────────────┘
                                    │
                                    ▼
                          返回帖子列表 + 下次游标
```

#### 5. 接口设计

**RPC 接口**：`ListUserPosts`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| user_id | int64 | 是 | 要查看的用户ID |
| page_size | int32 | 否 | 每页数量，默认10，最大50 |
| cursor | int64 | 否 | 游标，首次为空 |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [
      {
        "post_id": 1234567890123456789,
        "content": "...",
        "image_keys": [...],
        "like_count": 1100,
        "comment_count": 23,
        "created_at": "2026-07-16T10:30:00Z"
      }
    ],
    "next_cursor": 1234567890123456780,
    "has_more": true
  }
}
```

---

### F004：删除帖子

#### 1. 功能描述

作者删除自己发布的帖子。

#### 2. 业务规则

- 只有作者可以删除自己的帖子
- 逻辑删除（deleted = 1），保留数据
- 同时清理 Redis 缓存和冷启动池
- 不删除点赞、评论记录（保留审计数据）
- 不清理 timeline 里的引用（读时处理）

#### 3. 用户交互流程

```
1. 作者点击帖子详情页的"删除"
2. 二次确认弹窗
3. 确认后调用删除接口
4. 服务端：
   a. 校验权限（user_id == post.user_id）
   b. UPDATE posts SET deleted = 1
   c. DEL Redis 缓存
   d. ZREM 冷启动池
   e. SREM updated_set
5. 返回成功
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.DeletePost (gRPC)
                                    │
                                    ▼
                          ┌─────────────────┐
                          │ @Transactional   │
                          │ UPDATE posts    │
                          │ SET deleted = 1 │
                          └────────┬────────┘
                                   │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
               Redis DEL      ZREM 冷启动池    SREM updated_set
            post:detail:{id}
```

#### 5. 接口设计

**RPC 接口**：`DeletePost`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| post_id | int64 | 是 | 帖子ID |
| user_id (from metadata) | int64 | 是 | 操作人ID |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "success": true
  }
}
```

**错误码**：
| code | 说明 |
|------|------|
| 4005 | 帖子不存在或已删除 |
| 4030 | 权限不足（非作者） |

---

### F005：点赞

#### 1. 功能描述

用户对帖子进行点赞或取消点赞。

#### 2. 业务规则

- 同一用户对同一帖子只能点赞一次
- 重复点赞幂等处理，返回成功
- 点赞后计数 +1，取消后计数 -1
- 计数走 Redis 累加，每分钟批量刷盘

#### 3. 用户交互流程

```
1. 用户点击帖子详情页的点赞按钮
2. 立即显示点赞状态（乐观更新）
3. 调用点赞接口
4. 服务端：
   a. upsert post_likes 表（幂等）
   b. Redis INCR/DECR 计数
   c. SADD updated_set 标记待刷盘
5. 定时任务每分钟批量刷盘
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.ActionLike (gRPC)
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────┐
│  LikeService.actionLike()                              │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 4.1 post_likes 表 UPSERT                       │   │
│  │     INSERT ... ON CONFLICT DO UPDATE           │   │
│  │     WHERE status <> NEW_STATUS                 │   │
│  └─────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 4.2 状态变更时更新 Redis                       │   │
│  │     INCR post:stat:incr:{post_id}:likes ±1    │   │
│  │     SADD post:updated_set {post_id}            │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                    ┌─────────────────────────────┐
                    │ LikeFlushJob (每分钟)       │
                    │ 1. SRANDMEMBER updated_set 100 │
                    │ 2. Lua GET + SET 0         │
                    │ 3. UPDATE post_stats       │
                    └─────────────────────────────┘
```

#### 5. 接口设计

**RPC 接口**：`ActionLike`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| post_id | int64 | 是 | 帖子ID |
| action | int32 | 是 | 1=点赞，0=取消点赞 |
| user_id (from metadata) | int64 | 是 | 操作人ID |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "success": true
  }
}
```

#### 6. 关键 SQL

```sql
INSERT INTO post_likes (user_id, post_id, status, created_at, updated_at)
VALUES (#{userId}, #{postId}, #{status}, NOW(), NOW())
ON CONFLICT (user_id, post_id)
DO UPDATE SET status = EXCLUDED.status, updated_at = NOW()
WHERE post_likes.status <> EXCLUDED.status;
```

---

### F006：评论

#### 1. 功能描述

用户对帖子发表一级评论。

#### 2. 业务规则

- 评论内容 1-512 字符
- 只支持一级评论（预留楼中楼字段）
- 评论后自动进入 Redis ZSet 缓存
- 评论数 +1

#### 3. 用户交互流程

```
1. 用户在帖子详情页输入评论内容
2. 点击发送
3. 服务端：
   a. 校验内容长度
   b. INSERT post_comments 表
   c. ZADD post:comments:{post_id} 评论ID
   d. ZREMRANGEBYRANK 裁剪到 200 条
   e. INCR 评论计数
   f. SADD updated_set
4. 返回评论详情
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.CreateComment (gRPC)
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────┐
│  CommentService.createComment()                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │ INSERT post_comments                            │   │
│  │ (root_id=0, parent_id=0, reply_to_user_id=0)   │   │
│  └─────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Redis:                                           │   │
│  │ ZADD post:comments:{post_id} score={comment_id} │   │
│  │ ZREMRANGEBYRANK 0 -201 (只留200条)              │   │
│  │ INCR post:stat:incr:{post_id}:comments         │   │
│  │ SADD post:updated_set {post_id}                │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

#### 5. 接口设计

**RPC 接口**：`CreateComment`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| post_id | int64 | 是 | 帖子ID |
| content | string | 是 | 评论内容，1-512字符 |
| user_id (from metadata) | int64 | 是 | 评论人ID |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "comment_id": 1234567890123456789,
    "created_at": "2026-07-16T10:35:00Z"
  }
}
```

**错误码**：
| code | 说明 |
|------|------|
| 4007 | 评论内容为空 |
| 4008 | 评论内容超长 |
| 4005 | 帖子不存在 |

---

### F007：查看评论列表

#### 1. 功能描述

分页查看帖子的评论列表。

#### 2. 业务规则

- 按评论时间倒序
- 优先从 Redis ZSet 获取，缓存 miss 回源 DB
- Redis 只缓存最新 200 条
- 支持游标分页

#### 3. 用户交互流程

```
1. 用户进入帖子详情页，滚动到评论区域
2. 首次加载调用评论列表接口
3. 服务端：
   a. 先查 Redis ZSet
   b. 缓存够用则直接返回
   c. 缓存不够（>200 条或冷帖）回源 DB
4. 返回评论列表 + 下次游标
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.ListComments (gRPC)
                                    │
                                    ▼
                          ┌─────────────────┐
                          │ Redis ZSet      │
                          │ ZREVRANGEBYSCORE│
                          │ post:comments:  │
                          │ {post_id}       │
                          └────────┬────────┘
                                   │ 够用？
                    ┌──────────────┴──────────────┐
                    ▼                              ▼
                  够用                           不够
                    │                              │
                    ▼                              ▼
            返回 comment_ids              ┌─────────────────┐
                                           │ PostgreSQL      │
                                           │ SELECT * FROM   │
                                           │ post_comments   │
                                           │ WHERE post_id=? │
                                           │   AND root_id=0 │
                                           │ ORDER BY created_at DESC │
                                           │ LIMIT ?         │
                                           └─────────────────┘
```

#### 5. 接口设计

**RPC 接口**：`ListComments`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| post_id | int64 | 是 | 帖子ID |
| page_size | int32 | 否 | 每页数量，默认10 |
| cursor | int64 | 否 | 游标（上一页最末 comment_id） |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "comments": [
      {
        "comment_id": 1234567890123456789,
        "user_id": 1001,
        "content": "写得真好",
        "created_at": "2026-07-16T10:35:00Z"
      }
    ],
    "next_cursor": 1234567890123456780,
    "has_more": true
  }
}
```

---

### F008：删除评论

#### 1. 功能描述

作者删除自己的评论。

#### 2. 业务规则

- 只有评论作者可以删除
- 逻辑删除
- 同时清理 Redis ZSet
- 评论数 -1

#### 3. 接口设计

**RPC 接口**：`DeleteComment`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| comment_id | int64 | 是 | 评论ID |
| user_id (from metadata) | int64 | 是 | 操作人ID |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "success": true
  }
}
```

---

### F009：推荐 Feed

#### 1. 功能描述

获取个性化推荐的 Feed 流，三路混合：热门推荐 + 好友动态 + 冷启动。

#### 2. 业务规则

- 异性优先：男性用户看女性发的帖，反之亦然
- 热门池占 8 个位置，好友 1 个，冷启动 1 个
- 使用布隆过滤器去重
- 好友频控：同一好友单页最多出现一次

#### 3. 用户交互流程

```
1. 用户打开首页 Feed
2. 服务端三路并行拉取：
   a. 热门池：ZREVRANGE recommend:{gender}
   b. 好友时间线：ZREVRANGE timeline:{user_id}
   c. 冷启动池：ZREVRANGE cold_start:{gender}
3. 布隆过滤器去重
4. 按位置策略混排
5. 批量查询帖子详情
6. 返回 Feed 列表
```

#### 4. 数据流转

```
App → mobile-gateway → post-service.GetRecommendFeed (gRPC)
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────┐
│  FeedService.getRecommendFeed()                        │
│  ┌─────────────────────────────────────────────────┐   │
│  │ 3路并行拉取                                       │   │
│  │ ┌─────────┐ ┌─────────┐ ┌─────────┐             │   │
│  │ │热门池   │ │好友时间线│ │冷启动池 │             │   │
│  │ │ZREVRANGE│ │ZREVRANGE│ │ZREVRANGE│             │   │
│  │ └────┬────┘ └────┬────┘ └────┬────┘             │   │
│  │      └──────────┼──────────┘                     │   │
│  │                 ▼                                │   │
│  │          布隆过滤器去重                          │   │
│  │                 ▼                                │   │
│  │          三路混排                                │   │
│  │          位置1,2,4,5,7,8,9,10 → 热门池         │   │
│  │          位置3 → 好友时间线                      │   │
│  │          位置6 → 冷启动池                        │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

#### 5. 三路池详解

| 池 | 来源 | 排序 | 容量 | 写入方式 |
|----|------|------|------|----------|
| 热门池 | 全网近3天帖子 | Hacker News 热度分 | 每性别 Top 3000 | 每5分钟 Job 重建 |
| 好友时间线 | 关注的人发帖 | 发帖时间倒序 | 每人 100 条 | 发帖时 MQ 写扩散 |
| 冷启动池 | 新发布的帖子 | 发帖时间倒序 | 每性别 10000 条 | 发帖时同步写入 |

#### 6. 接口设计

**RPC 接口**：`GetRecommendFeed`

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page_size | int32 | 否 | 每页数量，默认10 |
| cursor | string | 否 | 游标，格式 "rec:offset:cs:offset" |
| user_id (from metadata) | int64 | 是 | 当前用户ID |

**返回**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [
      {
        "post_id": 1234567890123456789,
        "user_id": 1001,
        "content": "...",
        "image_keys": [...],
        "like_count": 1100,
        "comment_count": 23,
        "source": "recommend",  // recommend | friend | cold_start
        "created_at": "2026-07-16T10:30:00Z"
      }
    ],
    "next_cursor": "rec:30:cs:5",
    "has_more": true
  }
}
```

---

## 数据模型

### 核心实体

```
┌─────────────┐     ┌─────────────────┐
│   posts     │────<│  post_images    │
│  帖子主表    │1:N  │   帖子图片表     │
└──────┬──────┘     └─────────────────┘
       │
       │ 1:1
       ▼
┌─────────────┐     ┌─────────────────┐
│ post_stats  │     │  post_likes     │
│  计数底座    │<:N──│    点赞表       │
└─────────────┘     └─────────────────┘
                           │
                           │
                    ┌──────┴──────┐
                    ▼             ▼
             ┌─────────────┐ ┌─────────────┐
             │post_comments│ │ post_images │
             │   评论表     │ │   图片表     │
             └─────────────┘ └─────────────┘
```

### 表结构

#### posts（帖子主表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigserial | 内部主键 |
| post_id | bigint | 雪花ID，业务主键 |
| user_id | bigint | 发帖人 |
| content | varchar(1024) | 内容 |
| status | smallint | 0=已删/1=正常/2=审核中 |
| deleted | smallint | 逻辑删除 |
| created_at | timestamptz | 创建时间 |
| updated_at | timestamptz | 更新时间 |

#### post_images（帖子图片）
| 字段 | 类型 | 说明 |
|------|------|------|
| post_id | bigint | FK，PK 的一部分 |
| sort_order | smallint | 0-8，PK 的一部分 |
| image_key | varchar(128) | MinIO key |
| created_at | timestamptz | |

#### post_stats（计数底座）
| 字段 | 类型 | 说明 |
|------|------|------|
| post_id | bigint | PK |
| like_count | int | 累计点赞（已刷盘） |
| comment_count | int | 累计评论 |
| updated_at | timestamptz | |

#### post_likes（点赞表）
| 字段 | 类型 | 说明 |
|------|------|------|
| user_id | bigint | PK |
| post_id | bigint | PK |
| status | smallint | 1=已赞/0=已取消 |
| created_at | timestamptz | |
| updated_at | timestamptz | |

#### post_comments（评论表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigserial | 内部主键 |
| comment_id | bigint | 雪花ID，业务主键 |
| post_id | bigint | |
| user_id | bigint | |
| root_id | bigint | 根评论ID（自身为根则0） |
| parent_id | bigint | 直接父评论ID |
| reply_to_user_id | bigint | 被回复人 |
| content | varchar(512) | |
| status | smallint | |
| deleted | smallint | |
| created_at | timestamptz | |

---

## Redis 数据结构

| Key Pattern | 类型 | TTL | 用途 |
|-------------|------|-----|------|
| `putao:post:detail:{post_id}` | Hash | 7d | 帖子详情缓存 |
| `putao:post:stat:incr:{post_id}:likes` | String | 7d | 点赞未刷盘增量 |
| `putao:post:stat:incr:{post_id}:comments` | String | 7d | 评论未刷盘增量 |
| `putao:post:comments:{post_id}` | ZSet | 7d | 最新200条评论ID |
| `putao:post:updated_set` | Set | 7d | 待刷盘的post_id集合 |
| `putao:user:timeline:{user_id}` | ZSet | 7d | 好友动态时间线 |
| `putao:feed:pool:recommend:{gender}` | ZSet | 7d | 热门推荐池 |
| `putao:feed:cold_start:pool:{gender}` | ZSet | 7d | 冷启动池 |
| `putao:user:read:bloom:{user_id}` | BloomFilter | 7d | 已读去重 |

---

## 监控指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `post.create.success` | Counter | 发帖成功 |
| `post.create.fail` | Counter | 发帖失败 |
| `post.like.action` | Counter | 点赞行为（tag: action=LIKE/UNLIKE） |
| `post.like.flush.batch_size` | Histogram | 每次刷盘批量大小 |
| `feed.recommend.duration` | Timer | Feed 推荐耗时 |
| `feed.recommend.bloom.hit` | Counter | 布隆过滤器命中 |
| `post.updated_set.size` | Gauge | 待刷盘队列长度 |
| `post.fanout.produce.fail` | Counter | 写扩散发送失败 |
| `post.fanout.dlq.size` | Gauge | 死信队列积压 |
