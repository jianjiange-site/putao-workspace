# Post-Service 实现进度日志

> 追加式记录，每次 vibe-coding session 后更新

---

## 总进度概览

| 模块 | 状态 | 完成日期 | 备注 |
|------|------|----------|------|
| 基础骨架 | ✅ 完成 | 2026-07-15 | Day 1~2 |
| proto 定义 | ✅ 完成 | 2026-07-15 | 9 个 RPC 接口 |
| proto 模块本地 install | ✅ 完成 | 2026-07-15 | 6 个 jar 安装到本地仓库 |
| 数据层 (5张表) | ✅ 完成 | 2026-07-15 | Flyway migration |
| gRPC 接口 | ✅ 完成 | 2026-07-15 | PostGrpcService |
| 核心业务 | ✅ 完成 | 2026-07-15 | Day 4~5 |
| Feed 推荐 | ✅ 完成 | 2026-07-16 | 三路混合 + 热门池重建 + 降级逻辑 |
| 定时任务 | ✅ 完成 | 2026-07-15 | LikeFlush, CommentFlush, FeedScore |
| MQ 写扩散 | ✅ 完成 | 2026-07-15 | RocketMQ producer/consumer |
| 接入部署 | ✅ 完成 | 2026-07-16 | Nacos 配置 + 启动 18084/19084 |
| 集成测试 | ⏳ 待开始 | - | Day 8+ |

---

## Session 日志

<!-- 在下方追加每次 session 的记录 -->

## 2026-07-16 (Session #4)

**目标**: 修复 Feed 推荐实现与 post-service-design.md 设计文档的冲突

**背景**: 结合 `architecture-Vibe-ChatVibe服务端技术架构文档.md` 与 `post-service-design.md` 进行对比审查（后者优先），发现 5 个需要修复的问题。

**完成**:
- [x] 修复 `getPostDetailForFeed` 的 isLiked 逻辑永远为 false 的问题
  - 原因：`PostLikeManager.class.cast(null) != null` 永远为 false
  - 修复：注入 `PostLikeManager` 并正确调用 `isLiked(userId, postId)`
- [x] 在 `rebuildRecommendPool` 中加入 Redis 实时增量补偿
  - 原因：只查 DB 基准值，没有加上 Redis 增量
  - 修复：调用 `getRedisIncr()` 获取未刷盘的增量，合并后打分
- [x] 实现 Feed 三路混合的降级逻辑
  - 原因：好友/冷启动为空时没有降级到 recommend
  - 修复：实现 `fillSingleSlotWithPost` 支持降级源
- [x] 完成统计方法，输出可观测日志
  - 原因：`countRecommend/Friends/ColdStart` 返回固定值
  - 修复：添加 `FeedSource` 枚举追踪来源，用 `EnumMap` 统计
- [x] 实现 `post.fanout.produce.fail` 指标计数
  - 原因：TODO 占位未实现
  - 修复：注入 `MeterRegistry` 并在失败时 `Counter.increment()`
  - 同时添加 `micrometer-registry-prometheus` 依赖到 pom.xml

**修复文件**:
- `FeedService.java`: 注入 `PostLikeManager`、新增 `FeedSource` 枚举、实现降级逻辑
- `PostFanoutProducer.java`: 注入 `MeterRegistry`、添加指标计数
- `pom.xml`: 添加 `micrometer-registry-prometheus` 依赖

**AI 行为备注**:
- 重构 `FeedService.getRecommendFeed` 时误删了 `getPostDetailForFeed` 方法，发现后立即补回

---

## 2026-07-15 (Session #2)

**目标**: 终端构建验证 + 本地 install proto 模块

**完成**:
- [x] 配置环境变量 JAVA_HOME=D:\java\jdk21 + Maven Path
- [x] 终端可直接执行 `mvn -version` (Maven 3.9.11 + Java 21.0.11)
- [x] proto 模块（含 common/user/post/gateway/im/match 6 个）成功 install 到本地仓库
- [x] 创建 `~/.m2/settings.xml` mirror 指向 nexus-public

**遇到的问题（已修复）**:
1. `protobuf-maven-plugin:0.9.4` 在 maven central 无法下载 → 全部降级到 0.6.1
2. proto 0.6.1 的 `<extraArgs>` 解析失败 → 删除，只用 `<protoSourceRoot>${basedir}</protoSourceRoot>`
3. common 模块的 `result.proto` 路径与子模块 `import "common/result.proto"` 不匹配 → 把 `result.proto` 移到 `common/common/result.proto`
4. 子模块 target/classes 残留老 .proto → 加 `<excludes>target/**</excludes>`
5. 子模块生成 grpc-java 代码报 `io.grpc.protobuf` 包找不到 → 加 `grpc-protobuf` 依赖（非 lite）
6. 业务模块 post-service 依赖校验循环超时（30+ 分钟卡住）→ 怀疑 IDEA 用别的 mirror，
   命令行的 maven central 访问受限

**遗留**:
- [ ] post-service 还没跑过构建（命令行受网络限制）
- [ ] 准备切换回 IDEA Maven 面板做后续构建

**AI 行为备注**:
- 用方式一（本地 install）能让 6 个 proto 模块全部进入本地仓库，未来直接被业务模块引用

---

## 2026-07-15 (Session #1)

**目标**: 完成 post-service 基础开发和核心业务实现

**完成**:
- [x] 更新 `proto/post/post.proto` - 扩展为完整的 9 个 RPC 接口
- [x] 创建 Flyway 迁移脚本 - 5 张表 + shedlock 表
- [x] 创建 5 个 Entity 类 (PostEntity, PostImageEntity, PostStatEntity, PostLikeEntity, PostCommentEntity)
- [x] 创建 5 个 Mapper 接口 + 点赞 upsert SQL
- [x] 创建常量类 (ErrorCode, RedisKey, PostStatus, LikeStatus)
- [x] 创建异常类 (BizException, PostNotFoundException, CommentNotFoundException, ForbiddenException, GrpcExceptionHandler)
- [x] 创建配置类 (RedisConfig, RedissonConfig, SnowflakeIdConfig, GrpcClientConfig, CaffeineConfig, ShedLockConfig, AppInitConfig)
- [x] 创建 UserClient - 调用 user-service 获取好友列表和性别(桩实现)
- [x] 创建 Manager 层 (PostManager, PostStatManager, PostLikeManager, PostCommentManager)
- [x] 创建 Service 层 (PostWriteService, PostReadService, LikeService, CommentService, FeedService)
- [x] 创建 VO 类 (PostDetailVO, CommentVO, UserPostsVO, CommentsVO, RecommendFeedVO)
- [x] 创建 gRPC 服务实现 PostGrpcService
- [x] 创建 MQ 生产者 PostFanoutProducer
- [x] 创建 MQ 消费者 PostFanoutConsumer
- [x] 创建定时任务 (LikeFlushJob, CommentFlushJob, FeedScoreJob)
- [x] 更新 application.yml 配置
- [x] 创建 Spring Boot 主应用 PostApplication

**技术要点实现**:
- 写合并 (Write Coalescing): Redis 累加 + 批量刷盘
- 写扩散: RocketMQ fanout topic
- 三路混合 Feed: 热门池 + 好友时间线 + 冷启动池
- Hacker News 热度打分
- ShedLock 多实例定时任务互斥
- 性别分桶池

**遗留**:
- [ ] user-service 接口实现后需要替换 UserClient 桩
- [ ] 需要手动创建 RocketMQ topic

**AI 行为备注**:
- 严格按照 post-service-design.md 设计文档实现
- 所有 Redis key 使用 putao 前缀
- gRPC 使用 net.devh 框架
- 时间统一使用 TIMESTAMPTZ 和 UTC 时区

---

## 2026-07-16 (Session #3)

**目标**: post-service 在 IDEA 启动 + Nacos 配置 + 依赖版本对齐

**背景**: Session #2 把代码和 proto 都准备好了，但 post-service 还没有真正起过。本 session 目标就是把它在本地 18084 端口跑起来。

**完成**:
- [x] post-service 成功启动，启动耗时 25s（dev 环境）
- [x] Tomcat 监听 18084，gRPC Server 监听 19084
- [x] Nacos 注册成功：`dating-post-service 192.168.1.85:18084`（gRPC port 元数据 = 19084）
- [x] Nacos Config 热更新生效：Listening `dating-post-service-dev.yaml / DEFAULT_GROUP`
- [x] Flyway 校验通过（schema 已 up to date）
- [x] PostgreSQL / Redis / Redisson / RocketMQ Producer+Consumer / Netty Shaded 全部联通
- [x] 3 个定时任务 CommentFlush / FeedScore / LikeFlush 启动后正常执行

---

### Nacos 配置相关（重点）

#### 1. namespace 设计

本环境 namespace = `putao-dating-dev`，对应 Nacos Config 的 tenant。

**正确的数据 ID 格式**：`{spring.application.name}-{profile}.yaml`

本服务最终生效的 dataId：
```
dating-post-service-dev.yaml
```

#### 2. DataId 命名规则踩坑记录

最初我们尝试过几种命名，全都没生效，错误表现为：
```
[Nacos Config] c.a.c.n.c.NacosPropertySourceRepository - ignore NacosPropertySource
```
或者干脆连日志都没有。

| 写法 | 结果 | 原因 |
|------|------|------|
| `dating-post-service.yaml` | ❌ 不加载 | 缺 `-dev` profile 后缀，dev profile 不匹配 |
| `dating-post-service-dev.yml` | ⚠️ 不加载 | **后缀必须是 `.yaml` 不是 `.yml`** |
| `dating-post-service-dev.yaml`（**最终**）| ✅ 加载 | 正确 |
| `post-service-dev.yaml` | ❌ 不加载 | 必须用 `spring.application.name` 的完整名 |

> 💡 关键规则：**dataId 拼接格式 = `${spring.application.name}-${spring.profiles.active}.{file-extension}`**
> 而且 `file-extension` 只识别 `.yaml` / `.properties`，**`.yml` 会被忽略**。

#### 3. 最终写入 Nacos 的 `dating-post-service-dev.yaml` 完整内容

```yaml
spring:
  application:
    name: dating-post-service
  datasource:
    url: jdbc:postgresql://38.76.188.242:5433/dating_dev_putao?stringtype=unspecified
    username: jianjian_test
    password: MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V
    hikari:
      connection-init-sql: SET TIME ZONE 'UTC'
  data:
    redis:
      host: 38.76.188.242
      port: 6380
      password: sNuP9gZScsj88QbEyTujffOvRCCH9Kv1
      database: 1

server:
  port: 18084

grpc:
  server:
    port: 19084

app:
  cache:
    key-prefix: putao

dating:
  object-storage:
    provider: s3
    endpoint: https://minio-api.jianjiange.site
    region: us-east-1
    access-key: admin
    secret-key: GorLDkuOhGyK5c1RXh2gaPooXgtso/MR
    path-style-access: true
    bucket: dating-putao

redisson:
  single-server-config:
    address: redis://38.76.188.242:6380
    password: sNuP9gZScsj88QbEyTujffOvRCCH9Kv1
    database: 1

flyway:
  enabled: true
  locations: classpath:db/migration
  baseline-on-migrate: true

logging:
  level:
    root: INFO
    com.dating.post: DEBUG

rocketmq:
  name-server: 38.76.188.242:9876
  producer:
    group: putao-dating-dev-post-service-producer
    access-key: rocketmq-student
    secret-key: 5cafa390b8a42c25
    send-message-timeout: 2000
    retry-times-when-send-failed: 0
  consumer:
    access-key: rocketmq-student
    secret-key: 5cafa390b8a42c25
```

#### 4. 本地 `application.yml` 的最小内容（必须留的）

```yaml
spring:
  application:
    name: dating-post-service
  profiles:
    active: dev
  config:
    import: nacos:putao-dating-dev.yaml?group=DEFAULT_GROUP&refreshEnabled=true
```

> 业务配置全部放 Nacos，本地 yml 只保留"服务名 + 激活 profile + nacos import 三件套"。
> 这是为了让所有环境（dev/test/prod）共享同一份 jar，启动时按 profile 自动拉不同 Nacos 配置。

---

### 启动过程踩过的 4 个大坑（按时间顺序）

#### 坑 1：application.yml 没 rocketmq 配置 → bean 创建失败

**报错**：
```
RocketMQTemplate 这个 bean 被 @ConditionalOnMissingBean 触发失败
```

**根因**：`rocketmq-spring-boot-starter` 的自动装配需要 `rocketmq.name-server` 等配置，没有就跳过；但 consumer 那边要求 starter 必须装配，导致 bean 缺失。

**修复**：在 Nacos yaml 里加完整的 `rocketmq.{name-server,producer,consumer}` 配置块。

---

#### 坑 2：rocketmq-spring-boot-starter 2.3.1 调用 `setNamespaceV2` 缺失

**报错**：
```
APPLICATION FAILED TO START
void org.apache.rocketmq.client.consumer.DefaultMQPushConsumer.setNamespaceV2(java.lang.String)
The called method's class was loaded from: rocketmq-client-5.1.4.jar
```

**根因**：
- starter 2.3.1 假设 runtime 是 rocketmq-client 5.2.0+，会调用 `setNamespaceV2`
- 但 starter 自身依赖的 rocketmq-client 是 5.1.4，这个方法 5.2.0 才有
- 一个老 API 兼容性问题（[apache/rocketmq-spring#694](https://github.com/apache/rocketmq-spring/issues/694)）

**修复**（写在 `pom.xml`）：
```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.3.3</version>            <!-- 2.3.1 → 2.3.3 -->
</dependency>
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client</artifactId>
    <version>5.2.0</version>            <!-- 显式锁住,避免被 starter 拉成 5.1.4 -->
</dependency>
```

---

#### 坑 3：grpc-server-spring-boot-starter 拉老 grpc-core 1.58.0，与 grpc-stub 1.68.1 不兼容

**报错**：
```
java.lang.NoClassDefFoundError: io/grpc/InternalGlobalInterceptors
  at io.grpc.internal.ManagedChannelImplBuilder.getEffectiveInterceptors
```

**根因**：
- grpc-server-spring-boot-starter:2.15.0.RELEASE 自己依赖 grpc-core:1.58.0
- 项目显式声明 grpc-stub:1.68.1 → 实际加载 grpc-api:1.68.1
- grpc-core:1.58.0 调用的 `InternalGlobalInterceptors` 在 1.69 之后从 `grpc-core` 移走，1.68.1 找不到

**修复**（在 `pom.xml` 里把 starter 拉过来的所有 grpc 组件全部显式锁到 1.68.1）：
```xml
<dependency><groupId>io.grpc</groupId><artifactId>grpc-api</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-core</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-context</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-netty-shaded</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-util</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-services</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-inprocess</artifactId><version>${grpc.version}</version></dependency>
<dependency><groupId>io.grpc</groupId><artifactId>grpc-protobuf</artifactId><version>${grpc.version}</version></dependency>
```

> 用 `mvn dependency:tree -Dincludes=io.grpc` 验证时所有 grpc 包都是 1.68.1 才算 ok。

---

#### 坑 4（次要）：IDEA Run 启动日志出现 `Standard Commons Logging discovery in action with spring-jcl`

**说明**：spring-jcl 检测到 classpath 里有 commons-logging，给个 warning 提示冲突。

**影响**：warning 级别，不影响启动，可以忽略。如果想消除：

```xml
<dependency>
    <groupId>commons-logging</groupId>
    <artifactId>commons-logging</artifactId>
    <scope>provided</scope>
</dependency>
```

本项目暂不处理。

---

### 关键 pom.xml 改动总结

| 改动 | 文件 | 内容 |
|------|------|------|
| RocketMQ starter 升级 | `post-service/pom.xml` | `2.3.1 → 2.3.3` + 显式锁 `rocketmq-client:5.2.0` |
| grpc 组件对齐 | `post-service/pom.xml` | 把 starter 拉过来的 grpc-core/context/netty-shaded/util/services/inprocess/protobuf 全部显式锁到 1.68.1 |

---

### 启动成功的关键日志（可用于排错参考）

```
INFO  NacosConfigDataLoader - Load config[dataId=dating-post-service-dev.yaml, group=DEFAULT_GROUP] success
INFO  AppInitConfig - App initialized, redis key prefix: putao
INFO  HikariDataSource - HikariPool-1 - Start completed.
INFO  FlywayExecutor - Database: jdbc:postgresql://38.76.188.242:5433/dating_dev_putao (PostgreSQL 16.13)
INFO  FlywayExecutor - Schema "public" is up to date. No migration necessary.
INFO  Redisson 3.27.2 - 2 connections initialized for 38.76.188.242:6380
INFO  RocketMQAutoConfiguration - a producer (putao-dating-dev-post-service-producer) init on namesrv 38.76.188.242:9876
INFO  RocketMQMessageListenerContainerRegistrar - Register the listener to container
INFO  GrpcServerFactoryAutoConfiguration - Detected grpc-netty-shaded: Creating ShadedNettyGrpcServerFactory
INFO  GrpcClientAutoConfiguration - Detected grpc-netty-shaded: Creating ShadedNettyChannelFactory
INFO  TomcatWebServer - Tomcat started on port 18084
INFO  NacosServiceRegistry - nacos registry, DEFAULT_GROUP dating-post-service 192.168.1.85:18084 register finished
INFO  AbstractGrpcServerFactory - Registered gRPC service: com.dating.post.proto.PostService
INFO  GrpcServerLifecycle - gRPC Server started, listening on address: *, port: 19084
INFO  PostApplication - Started PostApplication in 25.085 seconds
INFO  NacosContextRefresher - [Nacos Config] Listening config: dating-post-service-dev.yaml
INFO  CommentFlushJob - Starting comment flush job...
INFO  FeedScoreJob - Starting feed score rebuild job...
INFO  LikeFlushJob - Starting like flush job...
```

---

### 遗留 & 下一步

- [ ] 把 rocketmq / grpc 的版本锁提到 `dating-server/pom.xml` 顶层 dependencyManagement，让所有服务继承（user/match/im 等）
- [ ] `application.yml` 增加 `commons-logging provided` 依赖消除 warning
- [ ] 写一个 `doc/runbook/post-service-local-debug.md`，把"启动报 N 个常见错时怎么改"做成速查表
- [ ] 创建 RocketMQ topic `youjianxin-dating-dev-post-fanout-v1`（首次发消息时如不存在需 broker 自动创建；目前 consumer 起来了但未验证）
- [ ] 验证 HTTP/gRPC 接口可调用（先 gRPC，因为 controller 还没建）
- [ ] user-service 起来后，PostGrpcService 里的桩调用切换到真实 gRPC client

**AI 行为备注**:
- AI 在 debug 过程中**主动误把"profile 不匹配"和"yml 不是 yaml"两个 Nacos 配置问题串在一起回答**，浪费了一轮；后续应严格按"日志关键字 + DataId 规则表"逐项排查
- AI 给出"升 grpc 统一版本"前没有先用 `mvn dependency:tree -Dincludes=io.grpc` 把 starter 拉的真实版本号打印出来确认冲突，导致走了一轮试错；下次**先打印依赖树再下结论**
- 启动成功的标志是"NacosContextRefresher - Listening config"出现 + "Started PostApplication in X seconds"同时出现，缺一不可

---
