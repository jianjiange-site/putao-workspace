# user-service 学习进度日志

## 2026-07-28 (Session #1)

**目标**: 全面探索 user-service 代码库，创建面试学习文档

**完成**:
- [x] 探索 user-service 完整代码结构
- [x] 分析 proto 接口定义
- [x] 分析 5 张数据库表的实体类
- [x] 分析 gRPC 服务实现（UserGrpcService, UserIdentityGrpcImpl, UserProfileGrpcImpl）
- [x] 分析核心服务实现（UserIdentityService, UserProfileService, UserBanService, UserAvatarService, UserInterestService）
- [x] 分析 Manager 层（缓存、封禁、各类绑定管理）
- [x] 分析 Mapper 层（5 个 MyBatis-Plus Mapper）
- [x] 分析 Redis Key 规范和缓存策略
- [x] 分析异常处理机制
- [x] 分析配置管理（Nacos, Snowflake, PhoneNumber）
- [x] 分析错误码设计
- [x] 阅读 user-service-design.md 设计文档
- [x] 创建 PRD 技术文档（doc/course/2026-07-28-user-service/prd.md，12 章节）
- [x] 创建核心知识点文档（doc/course/2026-07-28-user-service/knowledge.md，8 个知识点）
- [x] 创建面试问答文档（doc/course/2026-07-28-user-service/interview-qa.md，30+ 题目）
- [x] 创建 README.md

**代码覆盖**:
- 所有 5 个 Entity 类
- 所有 5 个 Mapper 接口
- 所有 Service 接口和实现
- 所有 Manager 类
- 所有 gRPC 实现类
- 所有 VO/DTO/Converter 类
- 所有配置类和常量类
- 所有异常类
- 数据库 Flyway 迁移脚本
- application.yml 配置
- pom.xml 依赖

**AI 行为备注**:
- 发现 user-service 不依赖其他服务的 gRPC client，只被其他服务调用
- 发现头像上传在 MVP 阶段使用 placeholder URL，V2 才接入真实对象存储
- 发现 Proto 接口定义在 proto/user/user.proto（proto 目录独立管理）
- 补充创建了 knowledge.md，包含 8 个核心知识点：分布式锁、EXCLUDE 约束、Cache Aside、三级封禁、Snowflake、gRPC Context、Presigned URL、MapStruct
