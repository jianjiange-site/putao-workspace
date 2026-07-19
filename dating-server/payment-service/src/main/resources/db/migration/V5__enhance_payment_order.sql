-- V5: 订单 GRANTED 状态 + 回调通知增强
-- payment_orders 添加返回 URL 字段
ALTER TABLE payment_orders ADD COLUMN return_url VARCHAR(512);

-- notify_status 增加状态枚举
-- 兼容 V1 没有命名约束的情况：用 IF EXISTS 避免 "constraint does not exist"
ALTER TABLE payment_orders DROP CONSTRAINT IF EXISTS payment_orders_notify_status_check;
ALTER TABLE payment_orders ADD CONSTRAINT payment_orders_notify_status_check
    CHECK (notify_status IN ('PENDING', 'NOTIFIED', 'CONFIRMED', 'FAILED'));
