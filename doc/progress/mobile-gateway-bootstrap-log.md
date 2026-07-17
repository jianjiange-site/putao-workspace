# Mobile Gateway 启动问题排查记录

> 本文档记录 mobile-gateway 服务启动过程中遇到的所有问题及解决方案。

---

## 2026-07-17 — Mobile Gateway 启动问题

### 问题 1：gRPC 版本不一致导致 NoClassDefFoundError

**现象**：
```
org.springframework.context.ApplicationContextException: Failed to start bean 'shadedNettyGrpcServerLifecycle'
Caused by: java.lang.NoClassDefFoundError: io/grpc/InternalGlobalInterceptors
Caused by: java.lang.ClassNotFoundException: io.grpc.InternalGlobalInterceptors
```

同时还有：
```
Failed to instantiate [net.devh.boot.grpc.client.channelfactory.GrpcChannelFactory]
Caused by: java.lang.NoClassDefFoundError: io/grpc/internal/AbstractManagedChannelImplBuilder
```

**根因**：
依赖版本冲突导致内部类找不到。

| 组件 | 原版本 | 冲突原因 |
|------|--------|----------|
| `grpc-spring-boot-starter` | 2.15.0.RELEASE | 依赖 grpc 1.58.x |
| `grpc.version` | 1.68.1 | 升级后与 grpc-spring-boot-starter 不兼容 |
| `grpc-services` | 1.58.0 | 与 grpc-core 版本不一致 |
| `grpc-netty-shaded` | 1.58.0 | 同上 |

**解决**：统一降级 grpc.version 到 1.58.0

修改文件：

```xml
<!-- proto/pom.xml -->
<grpc.version>1.68.1</grpc.version>  →  <grpc.version>1.58.0</grpc.version>

<!-- dating-server/mobile-gateway/pom.xml -->
<grpc.version>1.68.1</grpc.version>  →  <grpc.version>1.58.0</grpc.version>
```

**验证命令**：
```bash
cd proto; mvn clean install -DskipTests  # 先编译 proto
cd ../dating-server/mobile-gateway; mvn clean compile -DskipTests  # 再编译 gateway
```

---

### 问题 2：Maven 命令在 PowerShell 中执行失败

**现象**：
```
cd D:\project\putao-workspace\proto && mvn clean install -DskipTests
+                                     ~~
不是有效分隔符
```

**根因**：老版本 PowerShell 不支持 `&&` 作为命令分隔符。

**解决**：改用 `;` 代替 `&&`

```bash
cd D:\project\putao-workspace\proto; mvn clean install -DskipTests
```

---

### 问题 3：Nacos 配置未找到导致启动失败

**现象**（历史记录）：
```
[Nacos Config] Load config[dataId=dating-mobile-gateway-dev.yaml, group=DEFAULT_GROUP] success
```

**备注**：配置加载成功，但后续因 gRPC 版本问题启动失败。

---

## 依赖版本对照表

| 组件 | 正确版本 | 备注 |
|------|----------|------|
| grpc.version | 1.58.0 | 必须与 grpc-spring-boot-starter 2.15.x 匹配 |
| grpc-spring-boot-starter | 2.15.0.RELEASE | gRPC 服务端/客户端自动配置 |
| protobuf.version | 4.28.3 | 可独立升级 |

---

## 启动验证检查清单

- [ ] `.env.local` 存在且包含 NACOS_* 凭据
- [ ] IDE EnvFile 插件已启用并指向 `.env.local`
- [ ] Nacos 上存在 `<service>-dev.yaml` 配置
- [ ] gRPC 版本统一为 1.58.0
- [ ] proto 模块已 install 到本地 Maven 仓库
- [ ] 数据库连接正常（Flyway 迁移成功）

---

## 常见启动错误速查

| 错误信息 | 根因 | 解决 |
|----------|------|------|
| `NoClassDefFoundError: io/grpc/InternalGlobalInterceptors` | grpc 版本冲突 | 降级到 1.58.0 |
| `NoClassDefFoundError: io/grpc/internal/AbstractManagedChannelImplBuilder` | grpc 版本冲突 | 降级到 1.58.0 |
| `Failed to start bean 'shadedNettyGrpcServerLifecycle'` | 同上 | 同上 |
| `&& 不是有效分隔符` | PowerShell 版本低 | 改用 `;` |
| `Property 'spring.cloud.nacos.password' is missing` | 未启用 EnvFile | 装插件 + 配置 |
| `Could not find a GrpcChannelFactory` | gRPC 配置问题 | 检查 grpc-spring-boot-starter 依赖 |
