# Post-Service 学习笔记

> 学习日期：2026-07-16
> 学习范围：帖子服务 - 发帖、点赞、评论、Feed流

## 本次学习内容

- [业务功能设计](./prd.md) - 功能流程、数据流转、接口设计
- [核心知识点](./knowledge.md) - 技术原理、权衡取舍
- [面试问答](./interview-qa.md) - 高频问题、追问准备

## 学习目标

- [x] 理解 Redis 写合并实现高并发计数
- [x] 掌握 Feed 流三路混合推荐设计
- [x] 理解 RocketMQ 写扩散实现
- [x] 能讲清楚幂等性设计

## 关联知识

- 设计文档：`doc/specs/post-service-design.md`
- 核心代码：`dating-server/post-service/src/main/java/com/dating/post/`

## 目录结构

```
post-service/
├── service/           # 业务逻辑
│   ├── PostWriteService    # 发帖
│   ├── PostReadService     # 读取
│   ├── LikeService         # 点赞
│   ├── CommentService      # 评论
│   └── FeedService         # Feed流
├── manager/           # 数据访问编排
├── job/               # 定时任务
│   ├── LikeFlushJob        # 点赞刷盘
│   ├── CommentFlushJob     # 评论刷盘
│   └── FeedScoreJob        # 热门池重建
└── mq/                # 消息队列
    ├── producer/           # 生产者
    └── consumer/           # 消费者
```
