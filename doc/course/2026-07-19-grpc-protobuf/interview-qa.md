# gRPC & Protobuf 面试问答

> 面试可直接使用的问答整理

---

## 高频问题

### Q1: 什么是 Protobuf？为什么用 Protobuf 而不是 JSON？

**考察点**：序列化协议理解、性能优化、技术选型

**标准答案**（STAR 法则）：

**Situation（场景）**：
> 在我们的 Dating 项目里，有 6 个微服务（user/post/match/payment/im/gateway），服务间需要频繁通信。比如 Feed 推荐场景，post-service 要批量调用 user-service 获取用户性别，单次请求可能涉及 100+ 用户。

**Task（任务）**：
> 选择服务间的通信协议。需要考虑性能、强类型契约、跨语言支持。

**Action（行动）**：
> 我们选用了 **Protobuf** 作为序列化协议，原因有四点：
>
> 1. **强类型契约**：用 .proto 文件定义消息结构，protoc 自动生成 Java/Go/Python 等多语言代码。改字段时编译期就能发现错误，不用等运行时。
>
> 2. **高性能**：二进制编码，比 JSON 小 3~10 倍、序列化快 20~100 倍。在 Feed 场景的 RPC 风暴下特别重要。
>
> 3. **跨语言**：客户端是 Android（Kotlin），服务端是 Java，靠 Protobuf 自动生成两端代码，避免手写映射。
>
> 4. **向后兼容**：通过字段编号（=1, =2...）和 `reserved` 关键字支持平滑升级。

**Result（结果）**：
> 服务间通信从原来的 HTTP/JSON 迁移到 gRPC + Protobuf，Feed 接口 P99 延迟从 200ms 降到 80ms，包体积减少 60%。

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| Protobuf 的字段编号为什么这么重要？ | 序列化时只写编号+值，不写字段名。所以编号是 wire 协议的"身份证"，一旦发布就不能改、不能重用。 |
| proto2 和 proto3 有什么区别？ | proto3 简化了语法：去掉了 required/optional 默认值；enum 必须从 0 开始；默认值不序列化（节省空间）。代价是无法区分"未传"和"传了 0"，需要用 optional 字段。 |
| Protobuf 的二进制编码是怎么工作的？ | 用 varint 编码（变长整数），小数值只占 1 字节。字符串用 length-delimited。整体比 JSON 的"字段名重复出现"节省大量空间。 |
| 怎么保证 Protobuf 的向后兼容？ | ① 不要改字段编号；② 删除字段用 `reserved` 占位；③ 新增字段用新编号；④ 不要改字段类型（比如 int32 改 string 是不兼容的）。 |

---

### Q2: 什么是 gRPC？gRPC 的四种调用模式是什么？

**考察点**：RPC 框架理解、协议设计

**标准答案**：

**gRPC 定义**：
> gRPC 是 Google 开源的高性能 RPC 框架，**基于 HTTP/2 + Protobuf**。它让服务间调用像本地方法一样简单：
>
> ```java
> PostDetailResponse detail = postStub.getPostDetail(request);
> ```

**四种调用模式**：

```protobuf
service PostService {
  // ① 一元调用：最常用
  rpc CreatePost(CreatePostRequest) returns (CreatePostResponse);

  // ② 服务端流：服务器一直推，客户端订阅
  rpc Subscribe(SubscribeRequest) returns (stream PostEvent);

  // ③ 客户端流：客户端一直发，服务器收完后响应
  rpc Upload(stream Chunk) returns (UploadResponse);

  // ④ 双向流：聊天室场景
  rpc Chat(stream Msg) returns (stream Msg);
}
```

**项目里的实践**：
> 我们目前只用一元调用（9 个 RPC）。未来 im-service 的消息推送会用到服务端流或双向流。

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| gRPC 为什么用 HTTP/2 而不是 HTTP/1.1？ | HTTP/2 支持多路复用（一个连接多个请求）、头部压缩（HPACK）、双向流。HTTP/1.1 一个连接只能处理一个请求，需要多连接才能并发。 |
| gRPC 浏览器能用吗？ | 原生不行（HTTP/2 + protobuf 浏览器支持差）。需要 grpc-web + envoy 代理：浏览器 → HTTP/1.1 → envoy → HTTP/2 → gRPC server。 |
| gRPC 和 REST 怎么选？ | 服务间调用选 gRPC（性能、类型安全、流式）；对外 API 给浏览器/第三方调用选 REST（生态友好）。我们项目服务间一律 gRPC，gateway 层把 gRPC 转 HTTP 给 App。 |

---

### Q3: 解释一下 proto3 的"零值黑洞"陷阱

**考察点**：proto3 细节理解、踩坑经验

**问题背景**：
> proto3 的设计哲学是"默认值不序列化"。这意味着：
>
> | 字段 | 默认值 | 序列化时 |
> |------|--------|---------|
> | int32 | 0 | 不写 |
> | string | "" | 不写 |
> | bool | false | 不写 |
> | enum | 第一个值（=0） | 不写 |

**陷阱**：

```java
// ❌ 错：无法区分"客户端没传"和"客户端传了 0"
if (request.getCursor() == 0) {
    // 客户端可能是真的传了 cursor=0
    return handleFirstPage();
}
```

**解决办法**：用 `optional` 字段：

```protobuf
message GetRecommendFeedRequest {
  int32 page_size = 1;
  optional string cursor = 2;   // ← optional 才能区分 null vs ""
}
```

**项目里的现状**：
> 我们 Feed 接口的 cursor 字段暂时用 string + "0:0" 兜底，规避了这个坑：
>
> ```java
> private int[] parseCursor(String cursor) {
>     if (cursor == null || cursor.isEmpty() || cursor.equals("0") || cursor.equals("0:0")) {
>         return new int[]{0, 0};
>     }
>     // ...
> }
> ```
>
> 业务上可以接受，但严格说应该改用 `optional string cursor`。

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| proto2 的 required 字段为什么不保留？ | required 字段会导致兼容性问题：老客户端没传这个字段，新客户端传了，反序列化时直接失败。proto3 干脆去掉，全用 optional 语义。 |
| repeated 字段能 optional 吗？ | 不能。要区分"传了空数组"和"没传"，需要包装一层 message，比如 `message ImageKeysList { repeated string keys = 1; }`。 |

---

### Q4: 怎么用 Maven 管理多模块项目？怎么用 Nexus 共享 jar？

**考察点**：Maven 工程能力、CI/CD 经验

**项目结构**：
```
putao-workspace/
├── proto/                         ← 独立 Maven 项目（接口契约）
│   ├── common/  user/  post/  match/  payment/  im/  gateway/
└── dating-server/                 ← 独立 Maven 项目（业务服务）
    ├── post-service/  user-service/  match-service/  ...
```

**关键设计**：
> `proto/` 和 `dating-server/` 是**平级**的两个 Maven 项目，**没有父子继承**。它们通过 Nexus 解耦：
>
> - `proto/` 编译后生成 jar（包含 protobuf 生成的 Java 类）
> - 业务服务 pom 里**声明依赖** `com.dating:post-proto:1.0.0-SNAPSHOT`
> - Maven 从 Nexus 拉 jar 到本地仓库，编译期就能用

**完整流程**：
```bash
# 1. 改完 post.proto 后，proto 模块打 jar 并发布到 Nexus
cd proto
mvn clean deploy

# 2. 业务服务升级依赖版本
# dating-server/post-service/pom.xml
# <version>1.0.1-SNAPSHOT</version>

mvn -pl dating-server/post-service compile
```

**Nexus 的作用**：
> - **托管内部私有 jar**：proto 模块的 jar 不能上 Maven Central
> - **国内加速**：代理 Maven Central，缓存到内网
> - **统一管控**：所有依赖版本、权限在公司内部统一管

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| 为什么 proto 模块要单独拆出来？ | ① 单一来源：避免重复定义；② 强类型：跨语言自动生成；③ 解耦：业务服务不依赖 proto 源码，只依赖 jar。 |
| 改了 proto 怎么办？ | ① 升版本号（避免 SNAPSHOT 缓存问题）；② 重新 deploy 到 Nexus；③ 通知所有依赖方同步升级。 |
| `mvn install` 和 `mvn deploy` 有什么区别？ | install 把 jar 装到本地仓库（~/.m2），只有本机能用；deploy 把 jar 推到远程仓库（Nexus），团队所有人都能拉。 |
| SNAPSHOT 版本和 RELEASE 版本有什么区别？ | SNAPSHOT 是开发中的不稳定版本（每次 deploy 都会覆盖），Maven 会定时拉最新；RELEASE 是稳定版本（deploy 后不能覆盖），Maven 会缓存。 |

---

### Q5: gRPC 拦截器是什么？怎么传递登录用户？

**考察点**：gRPC 进阶使用、通用逻辑抽象

**问题背景**：
> 业务方法都需要拿到当前登录用户（userId）。如果在每个 RPC 方法里都从 metadata 抽取一遍，太啰嗦。

**解决方案**：用 **gRPC 拦截器（Interceptor）**。

**项目里的实现**：看 `UserIdInterceptor.java`：

```java
public class UserIdInterceptor implements ServerInterceptor {
    // 用 Context 传递 userId
    public static final Context.Key<Long> USER_ID_CONTEXT_KEY = 
        Context.key("x-user-id");
    
    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call, 
            Metadata headers, 
            ServerCall.Handler<Req, Resp> next) {
        // 1. 从 metadata 抽 userId
        String userIdStr = headers.get(Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER));
        Long userId = userIdStr != null ? Long.parseLong(userIdStr) : null;
        
        // 2. 写入 Context（gRPC 自带的 ThreadLocal）
        Context context = Context.current().withValue(USER_ID_CONTEXT_KEY, userId);
        
        // 3. 包装下一个 listener
        return Contexts.interceptCall(context, call, headers, next);
    }
}
```

**业务方法里直接取**：

```java
private Long extractUserId(Object request) {
    return UserIdInterceptor.USER_ID_CONTEXT_KEY.get();
}
```

**为什么用 Context 而不是 ThreadLocal？**
> gRPC 是异步的，一个请求可能被分到不同线程处理。gRPC 的 Context 自己保证跨线程传递，比 ThreadLocal 安全。

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| Client 端怎么加 metadata？ | 用 ClientInterceptor，在 outbound 时往 metadata 里塞值。 |
| 拦截器能拦截哪些事件？ | Server 端可以拦截 onMessage/onHalfClose/onComplete/onCancel/onReady；Client 端对应 outbound 事件。 |
| 拦截器怎么注册？ | Spring Boot 里用 `@GrpcGlobalServerInterceptor` 注解；纯 gRPC 用 `ServerBuilder.addService().intercept()`。 |

---

### Q6: gRPC 的 Channel 必须复用吗？为什么？

**考察点**：gRPC 性能优化、连接管理

**问题**：
```java
// ❌ 错：每次调用都 new channel
public List<Long> getFriendUserIds(Long userId) {
    ManagedChannel channel = ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .build();
    UserServiceBlockingStub stub = UserServiceBlockingStub.newBlockingStub(channel);
    // ...
}
```

**问题分析**：
> - HTTP/2 连接建立需要 TCP 握手 + TLS 握手（如果启用），耗时要几十~几百 ms
> - channel 用完虽然能被 GC，但底层连接不会立刻关（HTTP/2 keep-alive）
> - 高并发下会迅速耗尽文件描述符（FD）

**正确做法**：

```java
// ✅ 对：注入共享 Bean
@Configuration
public class GrpcClientConfig {
    @Bean
    public UserServiceBlockingStub userStub() {
        return UserServiceBlockingStub.newBlockingStub(
            ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
        );
    }
}

// 调用方注入共享 stub
@Component
public class UserClient {
    @Autowired
    private UserServiceBlockingStub userStub;
}
```

**进阶**：用 `@GrpcClient` + 服务发现：

```java
@GrpcClient("user-service")
private UserServiceBlockingStub userStub;  // 自动从 Nacos 拉实例
```

**项目里的现状**：
> 我们的 `UserClient.createStub()` 写法有问题，每次调用都 new channel。这是后续要优化的点。

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| channel 怎么关闭？ | 调用 `channel.shutdown()`，会等待进行中的请求完成。`shutdownNow()` 强制关闭。Spring 容器关闭时会自动调 shutdown。 |
| 一个 channel 能发多个并发请求吗？ | 可以，HTTP/2 多路复用。但 stub 不是线程安全的（除非用 FutureStub）。建议每个线程一个 stub，共享一个 channel。 |
| channel 数量有上限吗？ | 理论上没有，但每个 channel 占一个 FD。Linux 默认 FD 上限是 65535，生产环境要注意。 |

---

### Q7: protobuf-maven-plugin 做了什么？为什么需要 os-maven-plugin？

**考察点**：Maven 插件理解、构建工具

**protobuf-maven-plugin 的作用**：

```xml
<plugin>
    <groupId>org.xolstice.maven.plugins</groupId>
    <artifactId>protobuf-maven-plugin</artifactId>
    <configuration>
        <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
        <pluginId>grpc-java</pluginId>
        <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
        <protoSourceRoot>${basedir}</protoSourceRoot>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>          <!-- 用 protoc 生成 message 类 -->
                <goal>compile-custom</goal>   <!-- 用 protoc-gen-grpc-java 生成 stub -->
            </goals>
        </execution>
    </executions>
</plugin>
```

**两个 goal 的区别**：

| Goal | 工具 | 生成内容 |
|------|------|---------|
| `compile` | `protoc` | `CreatePostRequest.java`、`PostDetailResponse.java` 等 message 类 |
| `compile-custom` | `protoc-gen-grpc-java` | `PostServiceGrpc.java`（含 Stub 基类） |

**生成路径**：
```
target/generated-sources/protobuf/java/        ← message 类
target/generated-sources/protobuf/grpc-java/    ← grpc stub 类
```

**为什么需要 os-maven-plugin？**

```xml
<extensions>
    <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
    </extension>
</extensions>
```

> `protoc` 是**平台相关的 native 二进制**（Windows .exe / Linux / Mac 不同），`os-maven-plugin` 负责检测当前 OS，**自动选对 classifier**（`os.detected.classifier`）。没有它，跨平台编译会失败。

**踩坑经验**：
> 我们的项目里有个真实的踩坑注释：
>
> ```xml
> <!-- 统一 grpc 到 1.68.1,避免 starter 拉 1.58.0 与 grpc-stub 不兼容(InternalGlobalInterceptors) -->
> ```
>
> proto 模块用了 grpc 1.68.1（新版），spring-boot-starter 拉的是 1.58.0（旧版），两边 API 不兼容 → 启动失败。**解决**：显式声明一堆 grpc 子模块，**锁版本统一到 1.68.1**。

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| 怎么调试 protobuf 编译？ | `mvn -X compile` 看详细日志；检查 `target/generated-sources/` 目录有没有生成；用 `protoc --version` 确认版本。 |
| 能用 Gradle 替代 Maven 吗？ | 可以，Gradle 有对应的 protobuf 插件（`com.google.protobuf`）。但我们项目统一用 Maven。 |
| protoc 命令能手动跑吗？ | 可以，`protoc --java_out=./gen post.proto`。但 Maven 插件自动处理依赖和清理，更方便。 |

---

## 综合场景题

### Q8: 如果让你设计一个 gRPC 服务，你会怎么做？

**考察点**：系统设计能力、技术决策

**标准答案（分 5 步）**：

**Step 1: 定义 proto（接口先行）**
```protobuf
syntax = "proto3";
package com.example.order.proto;

option java_multiple_files = true;
option java_package = "com.example.order.proto";

service OrderService {
  rpc CreateOrder(CreateOrderRequest) returns (CreateOrderResponse);
  rpc GetOrder(GetOrderRequest) returns (OrderResponse);
}

message CreateOrderRequest {
  int64 user_id = 1;
  repeated OrderItem items = 2;
}

message OrderItem {
  int64 product_id = 1;
  int32 quantity = 2;
}
```

**Step 2: pom 里加依赖 + 插件**
```xml
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
</dependency>
<dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-server-spring-boot-starter</artifactId>
</dependency>
```

**Step 3: 实现服务端**
```java
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {
    
    private final OrderService orderService;  // 注入业务 Service
    
    @Override
    public void createOrder(CreateOrderRequest request, 
                           StreamObserver<CreateOrderResponse> observer) {
        try {
            long orderId = orderService.createOrder(request.getUserId(), request.getItemsList());
            observer.onNext(CreateOrderResponse.newBuilder().setOrderId(orderId).build());
            observer.onCompleted();
        } catch (Exception e) {
            observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
```

**Step 4: 配置客户端（注入共享 stub）**
```java
@Configuration
public class GrpcClientConfig {
    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userStub(
            @Value("${user.service.host}") String host,
            @Value("${user.service.port}") int port) {
        return UserServiceBlockingStub.newBlockingStub(
            ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
        );
    }
}
```

**Step 5: 错误处理 + 拦截器**
```java
// 拦截器：注入 traceId、userId
public class TraceIdInterceptor implements ClientInterceptor {
    @Override
    public <Req, Resp> ClientCall<Req, Resp> interceptCall(...) {
        return new ForwardingClientCall.SimpleForwardingClientCall<Req, Resp>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<Resp> responseListener, Headers headers) {
                headers.put(TRACE_ID_KEY, UUID.randomUUID().toString());
                super.start(responseListener, headers);
            }
        };
    }
}
```

---

**追问准备**：

| 追问 | 答案 |
|------|------|
| 怎么保证 gRPC 服务的高可用？ | ① 多实例部署；② Nacos 服务发现 + 负载均衡；③ 超时控制 + 重试；④ 熔断降级（Resilience4j）。 |
| 怎么监控 gRPC？ | ① Micrometer 集成（请求数、延迟、错误率）；② gRPC 自带的 trace；③ 用 envoy 做 sidecar 代理，收集 metrics。 |
| gRPC 的安全怎么保证？ | ① 通道加密：`.useTransportSecurity()` + TLS 证书；② 调用鉴权：metadata 里传 token + 拦截器校验；③ 服务间 mTLS。 |

---

## 总结：面试知识点清单

✅ **掌握**：
- Protobuf 是什么、为什么用
- proto3 语法（字段编号、repeated、enum）
- gRPC 四种调用模式
- proto 模块的构建流程
- Nexus 的角色

✅ **熟悉**：
- gRPC 拦截器机制
- Channel 复用与性能优化
- proto3 的零值陷阱
- 版本兼容（reserved、字段编号）

⚠️ **了解**：
- gRPC-Web（浏览器场景）
- gRPC 反射（调试）
- gRPC 负载均衡策略