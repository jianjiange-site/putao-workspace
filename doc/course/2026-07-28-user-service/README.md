# user-service 课程

> user-service 用户身份解析 + 用户资料域服务学习资料

## 目录

- [PRD 技术文档](prd.md) - 服务设计文档，包含架构、数据库、接口、缓存设计
- [核心知识点](knowledge.md) - 8 个核心技术的原理、权衡取舍、实现细节
- [面试问答](interview-qa.md) - 30+ 面试题目及参考答案

## 快速概览

### 服务职责

user-service 是约会交友 App 的用户核心服务，负责：
- **身份解析**: 手机号 / 第三方 / 设备 ID 三通道用户识别
- **用户资料**: 昵称、年龄、性别、城市、简介等 CRUD
- **兴趣标签**: 用户兴趣标签全量管理
- **头像上传**: Presigned URL 直传对象存储
- **封禁查询**: 三级封禁状态检查

### 核心技术点

| 分类 | 技术点 |
|------|--------|
| 通信协议 | gRPC + HTTP/2 |
| 数据库 | PostgreSQL + MyBatis-Plus + Flyway |
| 缓存 | Redis + Cache Aside 模式 |
| 分布式锁 | Redisson |
| ID 生成 | Snowflake 算法 |
| 手机号规范 | libphonenumber E.164 |
| 对象存储 | Presigned URL 直传 |
| 服务发现 | Nacos |

### 数据库表

| 表名 | 用途 |
|------|------|
| user_info | 用户主资料 |
| user_login_phone | 手机号绑定 |
| user_third_party_registration | 第三方账号绑定 |
| user_device_registration | 设备绑定 |
| user_interest | 兴趣标签 |

### 关键设计

1. **分布式锁防并发**: ResolveOrCreate 三通道加锁
2. **Placeholder 用户**: 先建占位用户再完善资料
3. **Cache Aside**: 先写库再删缓存
4. **EXCLUDE 约束**: 支持软删后重绑
5. **gRPC Context**: 跨层传递 userId/traceId

## 学习路径

1. 先阅读 [PRD](prd.md) 了解整体设计
2. 再看代码理解实现细节
3. 最后用 [面试问答](interview-qa.md) 检验掌握程度

## 相关文档

- [设计文档原版](../../specs/user-service-design.md)
- [proto 接口定义](../../../proto/user/user.proto)
- [项目开发规范](../../student-dev-guide.md)
