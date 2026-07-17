---
name: dating-infra
description: 操作 putao-workspace 共享基建。涉及任何 Nacos / Redis / MinIO / RocketMQ / PostgreSQL 的读写，例如:发布/读取 Nacos Data ID、查 PG 表 / 跑 Flyway migrate / 建库、Redis key/zset/hash 查改、MinIO 建 bucket / 上传对象 / 列对象、RocketMQ 列 cluster / 建 topic / 查消息 / 查 DLQ；以及任何 endpoint 出现 `38.76.188.242` 或 `*.jianjiange.site` 域名的操作；调试连接失败、Cloudflare 522/523、SCRAM 认证失败等基建侧错误也用本 skill。本 skill 包含所有共享 dev 凭据 + 命名规范 + 现成 docker 一次性命令模板，**所有命令都已用 workspace 实际值带好，直接 bash 执行**。
---

# Putao workspace 基建操作手册

本 skill 为 `putao-workspace` 仓库的共享基建操作集合。所有连接信息、命名规范、命令模板都在这一份文档里。

凭据合法性:CLAUDE.md 红线 #1 已放宽，共享 dev 凭据允许在 workspace 文件里写明文。

---

## 速查表

| 组件 | endpoint | 用户 | 密码 / Key |
|---|---|---|---|
| **PostgreSQL** | `38.76.188.242:5433` | `jianjian_test` | `MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V` |
| **Redis** | `38.76.188.242:6380`,db `1` | (无) | `sNuP9gZScsj88QbEyTujffOvRCCH9Kv1` |
| **Nacos** | `http://38.76.188.242:8848` | `nacos` | `jianjiange` |
| **MinIO API** | `https://minio-api.jianjiange.site` | `admin` | `GorLDkuOhGyK5c1RXh2gaPooXgtso/MR` |
| **MinIO Web** | `https://minio.jianjiange.site` | `admin` | 同上 |
| **RocketMQ NameServer** | `38.76.188.242:9876`(broker `broker-a` / cluster `StudentCluster`) | `rocketmq-student` | `5cafa390b8a42c25` |
| **RocketMQ Dashboard** | `https://rocketmq.jianjiange.site`(Basic Auth) | `student` | `MwTt14eUL9s1M3` |

---

## 命名规范(`putao-dating-dev`)

**物理资源**全部带前缀，**逻辑标识**(Spring app.name / Nacos Data ID)不带：

| 资源 | 名称 |
|---|---|
| PG 库 | `putao-dating-dev`(dash;SQL 内必须双引号 `"putao-dating-dev"`) |
| Redis key 前缀 | `putao-dating-dev:<service>:<domain>:<id>` |
| Nacos namespace | `putao-dating-dev` |
| Nacos Data ID | `<service>.yaml`，例 `post-service.yaml` —— **不带前缀** |
| MinIO bucket | `putao-dating-dev`(workspace 共用一个桶，服务靠 key 顶层前缀隔离) |
| RocketMQ topic / group | `putao-dating-dev-<...>`，例 `putao-dating-dev-post-fanout-v1` |

---

## 工具策略

本机**没装** `psql` / `mc` / `mqadmin` / `redis-cli`，全部通过 docker 临时容器跑：

| 操作 | docker 镜像 |
|---|---|
| PG SQL | `postgres:16-alpine`(自带 `psql`) |
| MinIO | `minio/mc:latest` |
| Flyway 迁移 | `flyway/flyway:10` |
| Redis | `redis:7-alpine`(自带 `redis-cli`) |

Nacos + RocketMQ Dashboard 用 `curl` + `jq` 直接打 REST API，**不需要 docker**。

---

## Nacos

### 1. 拿 accessToken(每个会话开头)

```bash
NACOS=http://38.76.188.242:8848
TOKEN=$(curl -sS -X POST "$NACOS/nacos/v1/auth/login" \
  -d 'username=nacos&password=jianjiange' | jq -r .accessToken)
echo "${TOKEN:0:20}..."  # 验证非空
```

### 2. 列所有 namespace

```bash
curl -sS "$NACOS/nacos/v1/console/namespaces?accessToken=$TOKEN" \
  | jq -r '.data[] | "\(.namespace)\t\(.namespaceShowName)"'
```

### 3. 列某 namespace 下所有 Data ID

```bash
NS=putao-dating-dev
curl -sS "$NACOS/nacos/v1/cs/configs?accessToken=$TOKEN&search=accurate&tenant=$NS&pageNo=1&pageSize=50&dataId=&group=" \
  | jq -r '.pageItems[] | "\(.dataId)\t\(.group)"'
```

### 4. 取某 Data ID 内容

```bash
NS=putao-dating-dev
DATA_ID=post-service.yaml
curl -sS "$NACOS/nacos/v1/cs/configs?accessToken=$TOKEN&tenant=$NS&dataId=$DATA_ID&group=DEFAULT_GROUP"
```

### 5. 发布(或覆盖)一个 Data ID

```bash
NS=putao-dating-dev
DATA_ID=user-service.yaml
curl -sS -X POST "$NACOS/nacos/v1/cs/configs?accessToken=$TOKEN" \
  --data-urlencode "dataId=$DATA_ID" \
  --data-urlencode 'group=DEFAULT_GROUP' \
  --data-urlencode "tenant=$NS" \
  --data-urlencode 'type=yaml' \
  --data-urlencode "content=$(cat /path/to/putao-workspace/nacos/$DATA_ID)"
# 期望响应:true
```

### 6. 删除 Data ID

```bash
curl -sS -X DELETE "$NACOS/nacos/v1/cs/configs?accessToken=$TOKEN&tenant=$NS&dataId=$DATA_ID&group=DEFAULT_GROUP"
```

### 7. 新建 namespace

```bash
curl -sS -X POST "$NACOS/nacos/v1/console/namespaces?accessToken=$TOKEN" \
  --data-urlencode 'customNamespaceId=putao-dating-dev' \
  --data-urlencode 'namespaceName=putao-dating-dev' \
  --data-urlencode 'namespaceDesc=putao workspace dev'
# 期望响应:true(已存在会返回 false 或 400)
```

---

## PostgreSQL

### 1. 一次性 SQL

```bash
docker run --rm -e PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' postgres:16-alpine \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d 'putao-dating-dev' \
  -c "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;"
```

### 2. 多语句 / 脚本(从 stdin)

```bash
docker run --rm -i -e PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' postgres:16-alpine \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d 'putao-dating-dev' <<'SQL'
SELECT count(*) FROM user_info;
SELECT count(*) FROM posts;
SQL
```

### 3. 建库 / 删库(带 dash 库名必须双引号)

```bash
# 切到 postgres 库做 DDL
docker run --rm -e PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' postgres:16-alpine \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d postgres \
  -c 'CREATE DATABASE "putao-dating-dev";'
```

### 4. 跑 Flyway migrate(某个服务)

```bash
WS=/path/to/putao-workspace
SVC=user-service                                   # 改成 post-service / match-service / mobile-gateway
HIST=flyway_history_$(echo $SVC | sed 's|-service$||;s|mobile-gateway|gateway|')

docker run --rm \
  -e FLYWAY_URL='jdbc:postgresql://38.76.188.242:5433/putao-dating-dev' \
  -e FLYWAY_USER='jianjian_test' \
  -e FLYWAY_PASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' \
  -e FLYWAY_TABLE=$HIST \
  -e FLYWAY_BASELINE_ON_MIGRATE=true \
  -e FLYWAY_BASELINE_VERSION=0 \
  -e FLYWAY_PLACEHOLDER_REPLACEMENT=false \
  -v $WS/dating-server/$SVC/src/main/resources/db/migration:/flyway/sql \
  flyway/flyway:10 migrate
```

**坑**:
- 必须 `FLYWAY_PASSWORD=` 通过 env var 传，**不能**走 `-password=...` CLI 参数(`flyway:10` 镜像对 CLI 参数有 bug，会报 "no password was provided")
- `BASELINE_VERSION=0` 否则 Flyway 默认 baseline 在 v1，V1 SQL 被跳过
- `PLACEHOLDER_REPLACEMENT=false` 否则 SQL 里如果有 `${id}` 字面量会被当占位符报 "No value provided"

### 5. 看某服务跑到哪个版本

```bash
SVC_HIST=flyway_history_user
docker run --rm -e PGPASSWORD='MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V' postgres:16-alpine \
  psql -h 38.76.188.242 -p 5433 -U jianjian_test -d 'putao-dating-dev' \
  -c "SELECT version, description, success FROM $SVC_HIST ORDER BY installed_rank DESC LIMIT 10;"
```

---

## Redis

### 1. 启动一个 redis-cli 容器(挂网络让 38 IP 可达)

```bash
docker run --rm -it redis:7-alpine \
  redis-cli -h 38.76.188.242 -p 6380 -a 'sNuP9gZScsj88QbEyTujffOvRCCH9Kv1' \
  -n 1 --no-auth-warning
# 进去后:select 1(workspace 用 db 1) → KEYS / GET / ZRANGE 随便玩
```

### 2. 一次性命令(脚本场景)

```bash
docker run --rm redis:7-alpine \
  redis-cli -h 38.76.188.242 -p 6380 -a 'sNuP9gZScsj88QbEyTujffOvRCCH9Kv1' \
  -n 1 --no-auth-warning \
  KEYS 'putao-dating-dev:*' | head -20
```

### 3. 看 ZSet / Hash 内容

```bash
KEY='putao-dating-dev:feed:cold_start:pool:male'
docker run --rm redis:7-alpine \
  redis-cli -h 38.76.188.242 -p 6380 -a 'sNuP9gZScsj88QbEyTujffOvRCCH9Kv1' \
  -n 1 --no-auth-warning \
  ZREVRANGE "$KEY" 0 10 WITHSCORES
```

### 4. 清掉某前缀(谨慎)

```bash
# 列出来看着
docker run --rm redis:7-alpine \
  redis-cli -h 38.76.188.242 -p 6380 -a 'sNuP9gZScsj88QbEyTujffOvRCCH9Kv1' \
  -n 1 --no-auth-warning \
  --scan --pattern 'putao-dating-dev:post:*' | head -10

# 真删:
docker run --rm redis:7-alpine sh -c '
  redis-cli -h 38.76.188.242 -p 6380 -a "sNuP9gZScsj88QbEyTujffOvRCCH9Kv1" -n 1 --no-auth-warning \
    --scan --pattern "putao-dating-dev:post:detail:*" | \
    xargs -I {} redis-cli -h 38.76.188.242 -p 6380 -a "sNuP9gZScsj88QbEyTujffOvRCCH9Kv1" -n 1 --no-auth-warning DEL {}
'
```

---

## MinIO

### 1. mc alias 一次性 + 操作(同一容器里串)

```bash
docker run --rm --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR' >/dev/null &&
  mc ls dev/
"
```

### 2. 建 bucket

```bash
docker run --rm --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR' >/dev/null &&
  mc mb --ignore-existing dev/putao-dating-dev
"
```

### 3. 列 bucket 内对象(按前缀)

```bash
docker run --rm --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR' >/dev/null &&
  mc ls --recursive dev/putao-dating-dev/post-image/ | head -20
"
```

### 4. 上传 / 下载

```bash
# 上传(挂宿主目录)
docker run --rm -v /tmp:/data --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR' >/dev/null &&
  mc cp /data/foo.jpg dev/putao-dating-dev/post-image/test/foo.jpg
"

# 下载到 /tmp
docker run --rm -v /tmp:/data --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR' >/dev/null &&
  mc cp dev/putao-dating-dev/post-image/test/foo.jpg /data/
"
```

### 5. 删对象 / 整批前缀

```bash
docker run --rm --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev https://minio-api.jianjiange.site admin 'GorLDkuOhGyK5c1RXh2gaPooXgtso/MR' >/dev/null &&
  mc rm --recursive --force dev/putao-dating-dev/tmp/
"
```

**MinIO key 顶层前缀约定**(workspace 共一桶):
- `post-image/<uid>/<yyyymm>/<uuid>.<ext>` — post-service 写
- `avatar/<uid>/<yyyymm>/<uuid>.<ext>` — user-service 写
- `attachment/...` — im-service 写
- `tmp/...` — 中转上传，24h Lifecycle 自动清

---

## RocketMQ

### 1. Dashboard REST API(浏览器后端,Basic Auth)

```bash
RMQ='https://rocketmq.jianjiange.site'
AUTH='student:MwTt14eUL9s1M3'
```

### 2. 列 cluster + broker(拿建 topic 用的参数)

```bash
curl -sS -u "$AUTH" "$RMQ/cluster/list.query" \
  | jq '.data.clusterInfo.clusterAddrTable'
# 期望:{"StudentCluster":["broker-a"]}
```

### 3. 列所有 topic(workspace 自己的过滤)

```bash
curl -sS -u "$AUTH" "$RMQ/topic/list.query" \
  | jq -r '.data.topicList[] | select(test("putao"))'
```

### 4. 建 topic

```bash
curl -sS -u "$AUTH" -X POST "$RMQ/topic/createOrUpdate.do" \
  -H 'Content-Type: application/json' \
  -d '{
    "topicName":"putao-dating-dev-post-fanout-v1",
    "writeQueueNums":8,
    "readQueueNums":8,
    "perm":6,
    "order":false,
    "clusterNameList":["StudentCluster"],
    "brokerNameList":[]
  }'
```

### 5. 查某 topic 最近消息

```bash
curl -sS -u "$AUTH" \
  "$RMQ/messagePage/queryTopicByPage.query?topic=putao-dating-dev-post-fanout-v1&pageNum=1&pageSize=20" \
  | jq -r '.data.page.content[] | "\(.msgId)\t\(.bornTimestamp)\t\(.body[0:60])"'
```

### 6. 看消费组堆积 / 进度

```bash
curl -sS -u "$AUTH" "$RMQ/consumer/groupList.query" \
  | jq -r '.data[] | select(.group | test("putao")) | "\(.group)\t\(.count)\t\(.consumeType)"'

curl -sS -u "$AUTH" "$RMQ/consumer/queryConsumeStatsList.query?consumerGroup=putao-dating-dev-post-service-fanout" \
  | jq
```

### 7. 看 DLQ(`%DLQ%` 前缀)

```bash
DLQ_TOPIC='%DLQ%putao-dating-dev-post-service-fanout'
curl -sS -u "$AUTH" \
  "$RMQ/messagePage/queryTopicByPage.query?topic=$(echo $DLQ_TOPIC | jq -sRr @uri)&pageNum=1&pageSize=20" \
  | jq
```

---

## 常见排障

### 38 IP 不通(curl 超时 / ping 100% 丢)

- 教学机偶尔抽风，**等 5-10 分钟再试**
- CloudFlare 域名(`*.jianjiange.site`)走的是 CDN 代理到 38，如果 38 origin 挂了，CDN 返 522(Connection timed out)或 523(Origin Unreachable)，也是同一根因
- 不能两路并 retry，只能等

### Nacos 响应不是 JSON(jq parse error)

- 后端返了 HTML 错误页(可能 token 过期，可能 server 暂时挂)
- 重新 login 拿 token 再试
- 如果仍是 HTML:用 `curl -i` 看 HTTP status，**504 / 502** 都是后端问题

### PG `SCRAM authentication, no password provided`

- Flyway docker 的 `-password=` CLI 参数没透传(已知 bug)
- **改用 `-e FLYWAY_PASSWORD=...`** env var 形式(见 PG §4 命令模板)

### Flyway "No value provided for placeholder ${id}"

- SQL 注释里有 `${id}` 字面量被 Flyway 当占位符
- **必带 `-e FLYWAY_PLACEHOLDER_REPLACEMENT=false`**

### Flyway "Schema is up to date"(但实际表没建)

- Flyway 默认 `baselineVersion=1`，首次 baseline 在 v1 → V1 SQL 不跑
- **必带 `-e FLYWAY_BASELINE_VERSION=0`**
- 已经错过的情况:`DROP TABLE flyway_history_<svc>` + 删掉错跑的 V2-V4 业务表，重跑

### MinIO `SignatureDoesNotMatch`

- `path-style-access: true` 没设(MinIO 必须)
- mc 用法层面没问题，SDK 层面 workspace 已经在 Nacos 配置里设了

### RocketMQ `ACL ... authentication failed`

- 公网客户端必须带 AK/SK(`rocketmq-student` / `5cafa390b8a42c25`)
- Producer 在 Nacos 配置里设;Consumer 注解里用 Spring 占位符 `${rocketmq.consumer.access-key}` 注入

### RocketMQ 522/523(Dashboard)

- Dashboard 后端 down，**只能等**
- 但 broker 本身(`38.76.188.242:9876`)可能还在跑，服务侧 Producer/Consumer 不一定受影响
