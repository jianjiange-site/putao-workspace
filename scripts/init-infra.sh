#!/bin/bash
# ========================================
# Putao Workspace 基建初始化脚本 (Bash)
# ========================================

WS="$(cd "$(dirname "$0")/.." && pwd)"
PG_HOST="38.76.188.242"
PG_PORT="5433"
PG_USER="jianjian_test"
PG_PASS="MpR5rGjss2Ly6vJFAhaxAwNqVAGVoP7V"
DB="putao_dating_dev"
REDIS_HOST="38.76.188.242"
REDIS_PORT="6380"
REDIS_PASS="sNuP9gZScsj88QbEyTujffOvRCCH9Kv1"
REDIS_DB="1"
MINIO_API="https://minio-api.jianjiange.site"
MINIO_AK="admin"
MINIO_SK="GorLDkuOhGyK5c1RXh2gaPooXgtso/MR"

echo "========================================"
echo "Putao Workspace 基建初始化脚本"
echo "========================================"
echo ""

echo "[1/4] 创建数据库 $DB (如果不存在)..."
docker run --rm -e PGPASSWORD="$PG_PASS" postgres:16-alpine \
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres \
  -c "CREATE DATABASE \"$DB\";" 2>/dev/null
echo "     OK: 数据库已创建或已存在"

echo ""
echo "[2/4] 跑 Flyway 迁移..."

migrate() {
  local SVC=$1
  local HIST=$2
  echo "     迁移 $SVC..."
  docker run --rm \
    -e FLYWAY_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$DB" \
    -e FLYWAY_USER="$PG_USER" \
    -e FLYWAY_PASSWORD="$PG_PASS" \
    -e FLYWAY_TABLE="$HIST" \
    -e FLYWAY_BASELINE_ON_MIGRATE=true \
    -e FLYWAY_BASELINE_VERSION=0 \
    -e FLYWAY_PLACEHOLDER_REPLACEMENT=false \
    -v "$WS/dating-server/$SVC/src/main/resources/db/migration:/flyway/sql" \
    flyway/flyway:10 migrate
}

migrate "user-service" "flyway_history_user"
migrate "post-service" "flyway_history_post"
migrate "match-service" "flyway_history_match"
migrate "mobile-gateway" "flyway_history_gateway"
migrate "payment-service" "flyway_history_payment"
migrate "im-service" "flyway_history_im"

echo ""
echo "[3/4] 创建 MinIO Bucket..."
docker run --rm --entrypoint=/bin/sh minio/mc:latest -c "
  mc alias set dev $MINIO_API $MINIO_AK '$MINIO_SK' >/dev/null 2>&1
  mc mb --ignore-existing dev/$DB
"
echo "     OK: Bucket 已创建或已存在"

echo ""
echo "[4/4] 验证 Redis 连接..."
docker run --rm redis:7-alpine \
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASS" -n "$REDIS_DB" --no-auth-warning PING
echo "     OK: Redis 连接正常"

echo ""
echo "========================================"
echo "初始化完成！"
echo "========================================"
echo ""
echo "启动服务前请确保 Docker 已运行。"
echo ""
