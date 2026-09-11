-- =============================================================================
--  判重唯一键迁移：overtime_records / leave_usage_records 增加 active_key
-- =============================================================================
--  背景：应用层判重（RecordConflictGuard）是"先查后写"，极端并发下仍有漏网可能。
--        本迁移在数据库层加唯一索引，兜住"完全相同"的重复录入（含并发写入）。
--        注意：唯一索引只能覆盖"完全相同"，覆盖不了"包含/交叉"的部分重叠，
--        部分重叠仍需依赖应用层判重 + 全局写锁串行。
--
--  原理：active_key 仅在记录"有效"时写入，作废(VOID)时置为 NULL。
--        MySQL 唯一索引允许多个 NULL，因此作废后同一天可以重新录入。
--
--  格式（必须与 Java 实体中的生成逻辑完全一致）：
--    overtime_records   : userId | date | recordMode | HH:mm | HH:mm
--    leave_usage_records: userId | date | HH:mm | HH:mm
--    时间使用 %H:%i（两位小时），与 Java 的 LocalTime.toString()/HH:mm 对齐。
--
--  幂等性：非幂等。重复执行会因"列已存在/索引已存在"报错，属预期行为。
-- =============================================================================

-- ---------- 0. 执行前自检：以下两条查询都必须返回空结果 ----------
-- 若返回数据，说明已存在完全相同的有效记录，需先人工确认/清理，否则第 3 步建索引会失败。
--
-- select user_id, `date`, record_mode, start_time, end_time, count(*) c
--   from overtime_records
--  where status <> 'VOID'
--  group by user_id, `date`, record_mode, start_time, end_time
-- having c > 1;
--
-- select user_id, use_date, start_time, end_time, count(*) c
--   from leave_usage_records
--  where status <> 'VOID'
--  group by user_id, use_date, start_time, end_time
-- having c > 1;

-- ---------- 1. overtime_records ----------
ALTER TABLE overtime_records
    ADD COLUMN active_key VARCHAR(120) NULL
        COMMENT '判重唯一键：有效记录=userId|date|recordMode|startTime|endTime；作废为 NULL';

UPDATE overtime_records
SET active_key = CONCAT(
        user_id, '|',
        DATE_FORMAT(`date`, '%Y-%m-%d'), '|',
        record_mode, '|',
        IFNULL(TIME_FORMAT(start_time, '%H:%i'), ''), '|',
        IFNULL(TIME_FORMAT(end_time, '%H:%i'), ''))
WHERE status <> 'VOID';

ALTER TABLE overtime_records
    ADD UNIQUE KEY uk_ot_active (active_key);

-- ---------- 2. leave_usage_records ----------
ALTER TABLE leave_usage_records
    ADD COLUMN active_key VARCHAR(120) NULL
        COMMENT '判重唯一键：有效记录=userId|date|startTime|endTime；作废为 NULL';

UPDATE leave_usage_records
SET active_key = CONCAT(
        user_id, '|',
        DATE_FORMAT(use_date, '%Y-%m-%d'), '|',
        IFNULL(TIME_FORMAT(start_time, '%H:%i'), ''), '|',
        IFNULL(TIME_FORMAT(end_time, '%H:%i'), ''))
WHERE status <> 'VOID';

ALTER TABLE leave_usage_records
    ADD UNIQUE KEY uk_lur_active (active_key);

-- ---------- 3. 执行后校验：以下两条都必须返回 0 ----------
-- select count(*) from overtime_records    where status <> 'VOID' and active_key is null;
-- select count(*) from leave_usage_records where status <> 'VOID' and active_key is null;
