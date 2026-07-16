# 新服务基建引导 - New Service Bootstrap

## 触发条件

当用户提供新服务的技术文档，要求在 `dating-server/` 下创建新服务时使用此技能。

## 服务骨架来源

复制 `dating-server/example-service` 作为起点：

```bash
cp -r dating-server/example-service dating-server/<service-name>
```

## 基建清单（按顺序执行）

### 1. pom.xml 修改

在 `dating-server/<service-name>/pom.xml` 中替换以下内容：

| 替换项 | 替换值 |
|--------|--------|
| artifactId | `<service-name>` |
| name | `<Service Name>` |
| proto 依赖 | `com.dating:<name>-proto:1.0.0-SNAPSHOT` |

> proto 模块必须在 `<dependencies>` 之前用 `mvn install -pl proto/<name> -am` 安装。

### 2. Flyway 迁移

```bash
mkdir -p dating-server/<service-name>/src/main/resources/db/migration
```

创建 `V1__init_<name>_tables.sql`：

- 表前缀：`<name>_`
- 时间字段：TIMESTAMPTZ，禁止 TIMESTAMP
- 必须带 `deleted` 软删字段

**跑 Flyway 迁移：**

```bash
docker run --rm ^
  -e FLYWAY_URL="jdbc:postgresql://38.76.188.242:5433/putao_dating_dev" ^
  -e FLYWAY_USER="jianjian_test" ^
  -e FLYWAY_PASSWORD="MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V" ^
  -e FLYWAY_TABLE=flyway_history_<name> ^
  -e FLYWAY_BASELINE_ON_MIGRATE=true ^
  -e FLYWAY_BASELINE_VERSION=0 ^
  -e FLYWAY_PLACEHOLDER_REPLACEMENT=false ^
  -v "d:/project/putao-workspace/dating-server/<service-name>/src/main/resources/db/migration:/flyway/sql" ^
  flyway/flyway:10 migrate
```

### 3. 创建 proto 模块（若无）

若 proto/<name> 不存在：

1. 参照 `proto/post/pom.xml` 创建 pom.xml
2. 创建 `proto/<name>/src/main/proto/<name>.proto`
3. `mvn -B -ntp clean install -pl proto -am`

### 4. Nacos 配置

在 `nacos/` 目录创建 `<service-name>.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://38.76.188.242:5433/putao_dating_dev?currentSchema=<name>&sslmode=disable
    username: jianjian_test
    password: MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V
  flyway:
    baseline-on-migrate: true
    baseline-version: 0
    placeholder-replacement: false

app:
  cache:
    key-prefix: putao:<name>

snowflake:
  worker-id: ${SNOWFLAKE_WORKER_ID:<worker-id>}
  datacenter-id: ${SNOWFLAKE_DATACENTER_ID:1}
```

**发布到 Nacos**（任选一种）：

```bash
# 方法1：curl 推送（需要 Nacos Open API）
curl -X POST "https://38.76.188.242:8848/nacos/v1/cs/configs" ^
  -d "dataId=<service-name>.yaml" ^
  -d "group=DEFAULT_GROUP" ^
  -d "namespace=putao-dating-dev" ^
  -d "content=$(cat nacos/<service-name>.yaml)"

# 方法2：手动去 Nacos 控制台上传
# https://console.nacos.io -> putao-dating-dev namespace -> 配置管理 -> 新增
```

### 5. 本地 .env.local 更新

在 `.env.local` 添加服务端口（如果有自定义端口）：

```env
<service-name_uppercase>_SERVICE_PORT=<http-port>
<service-name_uppercase>_SERVICE_GRPC_PORT=<grpc-port>
```

### 6. 启动命令

```bash
# 先安装 proto
mvn -B -ntp clean install -pl proto/<name> -am

# 启动服务
cd dating-server/<service-name>
mvn -B -ntp spring-boot:run
```

### 7. 健康检查

- `http://localhost:<port>/actuator/health`
- `http://localhost:<port>/swagger-ui.html`

## 命名规范

| 项目 | 规范 | 示例 |
|------|------|------|
| 目录名 | kebab-case | `order-service` |
| 包名 | dot.case | `com.dating.order` |
| 数据库 Schema | 下划线 | `order` |
| proto 模块名 | kebab-case | `order-proto` |
| artifactId | kebab-case | `order-service` |

## 端口分配（待确认）

在 `doc/progress/infra-bootstrap-log.md` 确认已有服务的端口分配，避免冲突。
