# gRPC & Protobuf 学习笔记

> 学习日期：2026-07-19
> 学习范围：Protocol Buffers 语法、gRPC 通信机制、protobuf-maven-plugin 代码生成、Maven 多模块与 Nexus 私服

## 本次学习内容

- [核心知识点](./knowledge.md) - 技术原理、协议机制、踩坑总结
- [面试问答](./interview-qa.md) - 高频问题、追问准备

## 学习目标

- [x] 理解 Protobuf 是什么、为什么需要它
- [x] 掌握 proto3 语法（字段编号、repeated、enum、optional）
- [x] 理解 gRPC 的四种调用模式
- [x] 掌握项目里 proto 模块的结构与构建流程
- [x] 理解 Nexus 在多模块 monorepo 中的角色
- [x] 能讲清楚为什么项目选 gRPC 而不是 HTTP/Feign

## 关联知识

- 设计文档：`doc/architecture-Vibe-ChatVibe服务端技术架构文档.md`
- 核心代码：
  - Proto 定义：`proto/post/post.proto`、`proto/user/user.proto`、`proto/common/common/result.proto`
  - proto 模块构建：`proto/pom.xml`、`proto/post/pom.xml`
  - gRPC 集成：`dating-server/post-service/pom.xml`、`config/GrpcClientConfig.java`
  - gRPC 实现：`dating-server/post-service/src/main/java/com/dating/post/grpc/PostGrpcService.java`
  - gRPC 调用：`dating-server/post-service/src/main/java/com/dating/post/client/UserClient.java`

## 目录结构

```
gRPC & Protobuf 学习目录
├── proto/                          # 接口契约模块
│   ├── common/                     # 公共类型（Result、PageRequest...）
│   ├── post/                       # post 服务契约（9 个 RPC）
│   ├── user/                       # user 服务契约
│   └── ...                         # match/payment/im/gateway
└── dating-server/
    └── post-service/
        ├── pom.xml                 # 依赖 post-proto / user-proto / common-proto
        ├── grpc/
        │   ├── PostGrpcService     # @GrpcService 实现 RPC
        │   └── UserIdInterceptor   # gRPC 拦截器
        └── client/
            └── UserClient          # gRPC BlockingStub 调用 user-service
```

## 三者关系速览

| 角色 | 比喻 | 作用 |
|------|------|------|
| **proto** | 合同 / 蓝图 | 定义消息格式 + RPC 接口（IDL） |
| **gRPC** | 快递公司 | 按合同把数据从 A 送到 B（传输框架） |
| **Nexus** | 仓库 / 档案馆 | 把生成的"合同副本（jar）"统一管理起来（依赖仓库） |

工作流：**写 proto → protoc 生成代码 → mvn deploy 到 Nexus → 各服务 mvn dependency:resolve 拉下来 → 业务实现 + 调用**