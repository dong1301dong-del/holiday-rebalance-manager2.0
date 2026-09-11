-- =============================================================================
-- 调休管家 · 第二批次改造迁移脚本
-- 日期：2026-09-12（承接 20260911_add_active_key.sql）
--
-- 覆盖改造项：
--   P4-1  users.builtin            内置管理员标记（用于不可删除/冻结/改密）
--   P2-2  token_blacklist          退出登录令牌黑名单（决策 D3：只退当前设备）
--   P5-1  holidays.type            日类型三态 → 两态（LEGAL 归一化为 RESTDAY）
--   P2-4  系统配置描述             标注哪些配置真正生效、哪些已废弃
--
-- 设计原则：**只增不改不删**。除类型归一化外不触碰任何业务数据；
--           holidays 的归一只改类型编码，不改名称、不改系数（LEGAL 与 RESTDAY 系数同为 1），
--           因此每一天的加班折算结果与迁移前完全一致。
--
-- 幂等性：所有 DDL 都用 information_schema 判定后再执行，可安全重复运行。
-- 执行：mysql -uroot -p tiaoxiu < 20260912_phase2.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) P4-1：users 增加 builtin 列（BIT(1)，与既有 Boolean 列保持一致，Hibernate validate 要求精确匹配）
-- -----------------------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'builtin'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE users ADD COLUMN builtin BIT(1) NOT NULL DEFAULT b''0'' AFTER must_change_pwd',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 把 ADMIN 角色的账号标记为内置（正常情况下只有一个）。
-- 用子查询包一层派生表，规避 MySQL「不能在 UPDATE 中直接引用被更新表」的限制。
UPDATE users SET builtin = b'1'
WHERE id IN (
    SELECT user_id FROM (
        SELECT ur.user_id AS user_id
        FROM user_roles ur JOIN roles r ON r.id = ur.role_id
        WHERE r.code = 'ADMIN'
    ) t
);

-- 关键：关闭内置管理员的「必须改密」。
-- 否则 P1-1（未改密则拦截业务接口）叠加 P4-1（禁止内置管理员改密）会形成永久锁死。
UPDATE users SET must_change_pwd = b'0' WHERE builtin = b'1';

-- -----------------------------------------------------------------------------
-- 2) P2-2 / D3：登出令牌黑名单表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS token_blacklist (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    token_hash  VARCHAR(64)  NOT NULL COMMENT '令牌 SHA-256 摘要',
    user_id     BIGINT       NULL     COMMENT '用户ID',
    expires_at  DATETIME(6)  NOT NULL COMMENT '令牌到期时间',
    created_at  DATETIME(6)  NULL     COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_blacklist_hash (token_hash),
    KEY idx_token_blacklist_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '已作废令牌黑名单';

-- -----------------------------------------------------------------------------
-- 3) P5-1：节假日日类型三态归一为两态
--    LEGAL（法定节假日）→ RESTDAY（法定休息日）。名称列保留，展示不受影响；
--    系数不变（两者都是 1），历史加班记录中的 day_type 不做改动（那是历史快照）。
-- -----------------------------------------------------------------------------
UPDATE holidays SET type = 'RESTDAY'          WHERE type = 'LEGAL';
UPDATE holidays SET official_type = 'RESTDAY' WHERE official_type = 'LEGAL';

-- -----------------------------------------------------------------------------
-- 4) P2-4 / D2：修正配置项描述，明确区分「生效中」与「已废弃」
--    生效中的折算系数会被 OvertimeService / HolidayService 真实读取；
--    其余三项属于早期版本遗留（当前加班规则为「时长 = 结束 − 开始，不扣时段、不封顶」），
--    保留数据仅为兼容历史页面，标注出来避免管理员误判。
-- -----------------------------------------------------------------------------
UPDATE system_config SET description = '【生效中】法定工作日/补班日加班折算比例'
    WHERE `key` = 'leave.ratio.workday';
UPDATE system_config SET description = '【生效中】法定休息日（含法定节假日）加班折算比例'
    WHERE `key` = 'leave.ratio.rest';
UPDATE system_config SET description = '【已废弃·不生效】单日有效加班时长封顶（小时）'
    WHERE `key` = 'overtime.dailyCap';
UPDATE system_config SET description = '【已废弃·不生效】上午时段 09:00-12:30 时长'
    WHERE `key` = 'overtime.period.am';
UPDATE system_config SET description = '【已废弃·不生效】下午时段 14:00-18:00 时长'
    WHERE `key` = 'overtime.period.pm';
