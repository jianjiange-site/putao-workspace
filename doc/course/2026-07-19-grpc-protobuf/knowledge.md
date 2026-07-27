# gRPC & Protobuf 知识点整理

> 纯技术知识学习，不涉及面试场景

---

## 知识点一：Protobuf 是什么，为什么需要它

### 1. 背景：服务之间怎么传数据？

两个服务之间要通信，必须先把内存里的 Java 对象变成能传的东西。

**老办法——JSON over HTTP**：

```
服务 A  [Java对象] → JSON字符串 → 网络 → JSON字符串 → [Java对象] 服务B
```

这样做有几个问题：
- JSON 是明文，字段名每次都重复传，浪费带宽（一个 "user_id" 字符串就占 7 字节）
- 字段改名字，生产和测试版本不一致，只能靠人肉保证
- 反序列化需要跑一遍 JSON parser，速度慢

### 2. Protobuf 是什么

**Protobuf（Protocol Buffers）= 一套用 `.proto` 文件定义数据格式的工具**。

开发者写一个 `.proto` 文件描述"我要传什么字段、什么类型"，编译器（protoc）自动生成各种语言的代码（Java / Go / Python / ...）。发送方用生成的类序列化，接收方用生成的类反序列化。

```
.proto 文件（人类可读） → protoc 编译 → 各语言代码（Java/Go/Python...）
                                           ↓
                                    序列化/反序列化
                                           ↓
                                    网络传输（字节流）
```

**和 JSON 的本质区别**：序列化后的字节流里只有**字段编号 + 值**，没有字段名字符串。`user_id = 123` 在 protobuf 里是 2 字节，在 JSON 里是 16 字节。

### 3. 项目里的实践

看 `proto/post/post.proto` 顶部：

```protobuf
syntax = "proto3";
package com.dating.post.proto;

option java_multiple_files = true;
option java_package = "com.dating.post.proto";

import "common/result.proto";
```

**几个关键点**：

| 元素 | 含义 |
|------|------|
| `syntax = "proto3"` | 协议版本，当前主流用 proto3 |
| `package com.dating.post.proto` | 命名空间，避免不同服务之间的 message 撞名 |
| `java_multiple_files = true` | 每个 message 生成一个独立的 .java 文件（推荐写法） |
| `java_package` | 生成代码的 Java 包名 |
| `import "common/result.proto"` | 引用公共类型，路径是文件相对路径，不是 classpath |

**字段编号是 Protobuf 的核心**：每个字段有个编号（1、2、3...），序列化时只写编号不写名字，解码时按编号查表。所以编号一旦发布就**不能改、不能重用**，改字段名不影响二进制格式。

---

## 知识点二：gRPC 是什么

**gRPC = Google Remote Procedure Call**。本质是一个 **RPC 框架**，让你像调本地方法一样调远程服务：

```java
// 看起来就像普通方法调用
PostDetailResponse detail = postStub.getPostDetail(request);
// 实际上跨越了网络，用的是 protobuf 二进制传输
```

gRPC 跑在 **HTTP/2** 上，天生支持多路复用（一个连接上同时跑多个请求）和头部压缩；用 **protobuf** 做序列化，比 JSON 小 3~10 倍、快 20~100 倍。

项目中每个服务自己实现 gRPC Server（监听独立端口），服务之间直接 gRPC 互通，不走 gateway。gateway 的角色是：**App 的唯一入口，把 HTTP/JSON 转成 gRPC 调给后端**。

**生成的代码里，每个 service 会带三种 Stub**：

```java
UserServiceGrpc.newBlockingStub(channel);   // 阻塞，最常用
UserServiceGrpc.newFutureStub(channel);    // 返回 ListenableFuture
UserServiceGrpc.newStub(channel);         // 异步 StreamObserver
```

项目里用的是 **BlockingStub**，适合请求-响应场景。

---

## 知识点三：gRPC 一次调用到底发生了什么

想象你在 Java 里写了这一行：

```java
postStub.getPostDetail(request);
```

表面上是普通方法调用，实际上**跨越了网络**。

**第一步：你在 Client 进程里写了一个 Java 对象**。这个对象叫 Request，是 protobuf 编译器根据 .proto 文件自动生成的 Builder 模式类。你塞了一些字段进去，比如 `post_id = 123`。

**第二步：客户端框架把这个 Java 对象变成一串字节**。这一步叫序列化。protobuf 用 varint 编码，把字段编号（比如 1）和字段值（比如 123）压缩成尽可能短的二进制。`post_id = 123` 只需要 2 个字节，而同样的信息用 JSON 写"post_id: 123"得花 14 字节。这就是 protobuf 比 JSON 小的根本原因——**字段名字符串不用重复出现在字节流里**，只编一个数字编号就够了。

**第三步：客户端框架把这串字节塞进 HTTP/2 frame 发出去**。gRPC 跑在 HTTP/2 上，所以传输层是有结构的 HTTP/2 frame。请求包含两类：HEADERS frame 里写路径、方法名、metadata（比如 traceId）、鉴权 token；DATA frame 里装刚刚序列化好的 protobuf 字节。**关键的省钱点：HTTP/2 是多路复用的**，同一个 TCP 连接上可以同时跑多个请求，不用每个请求都重新三次握手。

**第四步：服务器收到 frame，开始反着走一遍**。HTTP/2 框架把 frame 拼回完整请求，HPACK 算法解压 headers 拿到方法路径，protobuf 反序列化把字节流还原成 Java Request 对象。**这个过程对你是透明的**——你只在最后写了一个 `@Override public void getPostDetail(...)` 方法，框架已经帮你做完了前面所有脏活。

**第五步：gRPC 框架根据路径路由到你的方法**。比如 `:path = /com.dating.post.proto.PostService/GetPostDetail`，框架查路由表找到对应的方法，调进去。你在方法里查数据库、返回数据。

**第六步：你写的业务代码往 observer 里塞 Response**。`observer.onNext(response).onCompleted()`，框架又帮你把它序列化回字节流。这一步和第二步是镜像的。

**第七步：服务器把字节流通过 HTTP/2 frame 发回去**。客户端框架再走一遍第四步的反向，把字节变成 Java Response 对象，赋值给 `resp` 变量。然后你代码里下一行 `resp.getContent()` 才被执行——**这一步你看见了**。

整条链路里，你作为业务开发只看到了两端：**一端是构造 Request 调 stub，另一端是 @Override 方法被框架调进来**。中间 5 步（序列化、HTTP/2 frame、headers、metadata、反序列化）全是框架干的。这就是为什么 gRPC 写起来像本地调用，但性能是网络级的。

**拦截器在哪儿？** 它在第三步之前和第四步之后。在你的业务方法完全不知道的情况下，框架会先过一串拦截器：ServerInterceptor 可以在调业务方法之前验 token、抽 userId。所以项目里的 `UserIdInterceptor` 不用改任何业务代码，业务方法里 `USER_ID_CONTEXT_KEY.get()` 就能拿到登录用户——那串数值是拦截器偷偷放进 gRPC 的 Context（类似 ThreadLocal，但能跨线程）里的。

---

## 知识点四：项目里的 gRPC 实现

### 1. 服务端

只需要一个 `@GrpcService` 注解就能启动，继承生成的 `ImplBase` 类，重写对应的 RPC 方法：

```java
@GrpcService
@RequiredArgsConstructor
public class PostGrpcService extends PostServiceGrpc.PostServiceImplBase {
    private final PostWriteService postWriteService;

    @Override
    public void createPost(CreatePostRequest request, StreamObserver<CreatePostResponse> observer) {
        long postId = postWriteService.createPost(extractUserId(request), request.getContent().trim(), request.getImageKeysList());
        observer.onNext(CreatePostResponse.newBuilder().setPostId(postId).build());
        observer.onCompleted();
    }
}
```

底层用的是 `net.devh:grpc-server-spring-boot-starter`，默认监听 9090 端口。

### 2. 客户端

Channel 是 HTTP/2 连接，必须在整个应用生命周期复用，不能每次调用都 new。正确做法是注入共享的 stub Bean：

```java
@Bean
public UserServiceBlockingStub userServiceBlockingStub() {
    return UserServiceGrpc.newBlockingStub(
        ManagedChannelBuilder.forAddress(userServiceHost, userServicePort)
            .usePlaintext()   // dev 环境明文，生产要用 TLS
            .build()
    );
}
```

### 3. 拦截器

把"每个调用都要做的事"抽成拦截器，业务方法本身不知道它们的存在。比如 `UserIdInterceptor` 从请求的 metadata 里抽 `x-user-id`，写入 gRPC Context，业务方法里直接 `USER_ID_CONTEXT_KEY.get()` 就能拿到登录用户。

### 4. 错误处理

gRPC 用 `Status` 表达错误，常见的有：`NOT_FOUND`（资源不存在）、`INVALID_ARGUMENT`（参数错误）、`PERMISSION_DENIED`（权限拒绝）、`INTERNAL`（服务器内部错误）、`UNAVAILABLE`（下游挂了）。

---

## 知识点五：proto 模块的构建机制

### 1. 为什么单独拆出来

`proto/` 是一个独立 Maven 项目，所有服务都依赖它。好处是**单一来源**：一个 .proto 文件定义，全公司统一使用，不会出现各服务定义不一致的问题。

```
putao-workspace/
├── proto/                    ← 独立 Maven 项目，存放所有 .proto 文件
│   ├── common/               ← 公共 message（Result、Empty 等）
│   ├── user/
│   └── post/
│
└── dating-server/            ← 另一个独立 Maven 项目
    ├── post-service/         ← 依赖 post-proto.jar
    └── user-service/         ← 依赖 user-proto.jar
```

### 2. 编译过程

proto 模块的 `pom.xml` 里配了 `protobuf-maven-plugin`，执行 `mvn compile` 时：

- `protoc` 把 `.proto` → `xxxRequest.java`、`xxxResponse.java`（message 类）
- `protoc-gen-grpc-java` → `XxxServiceGrpc.java`（含 Stub 基类）

生成位置在 `target/generated-sources/protobuf/`。

### 3. 服务怎么用

proto 模块 `mvn install` 后，业务服务声明依赖：

```xml
<dependency>
    <groupId>com.dating</groupId>
    <artifactId>post-proto</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

proto 改了必须：① 升版本号 ② `mvn deploy` 到 Nexus ③ 依赖方同步升级版本。**这是硬性规定**，否则调用方拿到旧版 stub，编译期就会失败（其实是好事——proto 改字段没法偷偷上线）。

---

## 知识点六：实际踩坑总结

### 1. proto3 默认值陷阱 ⚠️

proto3 里默认值字段（int=0、string=""、bool=false）序列化时根本不写进字节流。所以 `if (request.getCursor() == 0)` 无法区分"客户端没传"和"客户端真的传了 0"。项目里用 string 类型 + "0:0" 兜底规避了这个坑。

### 2. channel 必须复用 ⚠️

每次调用都 `new ManagedChannel` 会耗尽文件描述符。必须注入共享 stub Bean，所有调用共用一个 channel。

### 3. 版本锁定 ⚠️

grpc-spring-boot-starter 会拉旧版 grpc stub，和 proto 模块用的新版 grpc 不兼容，导致启动失败。解决方法是显式声明所有 grpc 子模块，统一锁到一个版本。

### 4. usePlaintext 仅限 dev

`.usePlaintext()` 是明文传输，dev 可以用，生产必须改 `.useTransportSecurity()` 配 TLS 证书。

---

## 知识点七：调试工具

**grpcurl** 是最常用的命令行工具：

```bash
# 列出所有服务
grpcurl -plaintext localhost:9090 list

# 调用方法
grpcurl -plaintext -d '{"post_id": 123}' \
    localhost:9090 \
    com.dating.post.proto.PostService/GetPostDetail
```

开启 gRPC Reflection 后，可以用 `grpcurl` 直接列出服务方法，不需要导入 .proto 文件：

```yaml
grpc:
  server:
    reflection-service-enabled: true
```

---

## 总结：项目里 gRPC + Protobuf 的最佳实践

### ✅ 做得好的地方

1. **proto 单独成模块**：通过 Nexus 共享，避免重复定义
2. **强类型契约**：服务端改字段时客户端编译期就能发现
3. **字段编号管理**：有版本号机制保证兼容性
4. **拦截器机制**：UserIdInterceptor 把"获取登录用户"做成通用逻辑
5. **Status 错误码**：用标准 gRPC Status

### ⚠️ 需要改进的地方

1. **UserClient.createStub() 写法问题**：每次调用都 new channel，应该注入共享 stub
2. **cursor 字段应该用 optional**：现在用 "0:0" 兜底，不够严谨
3. **没有用 Nacos 做服务发现**：目前写死 host:port，多实例部署时无法负载均衡
4. **没有 grpc reflection**：调试时必须导入 .proto 文件才能用 grpcurl
5. **没有 TLS**：生产环境必须上 `.useTransportSecurity()`

---

## 参考资料

- [Protocol Buffers 官方文档](https://protobuf.dev/)
- [gRPC 官方文档](https://grpc.io/docs/)
- [protobuf-maven-plugin](https://www.xolstice.org/protobuf-maven-plugin/)
- [gRPC Spring Boot Starter](https://github.com/yidongnan/grpc-spring-boot-starter)
- 项目源码：`proto/post/post.proto`、`dating-server/post-service/pom.xml`
