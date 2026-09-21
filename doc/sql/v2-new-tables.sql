-- ============================================================
-- L7VP V2 新增表 DDL (达梦 DM8)
-- 版本: v2.0
-- 日期: 2026-07-08
-- 执行前确保已连接到达梦数据库，Schema DIG_GEO 已存在
-- ============================================================

-- 切换到 DIG_GEO Schema（如需要）
-- SET SCHEMA DIG_GEO;

-- ============================================================
-- 1. DB_CONNECTIONS — 外部数据库连接配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS DIG_GEO.DB_CONNECTIONS (
  CONN_ID       VARCHAR(50)  PRIMARY KEY,
  CONN_NAME     VARCHAR(200) NOT NULL,
  DB_TYPE       VARCHAR(50)  NOT NULL,
  HOST          VARCHAR(200) NOT NULL,
  PORT          INT          NOT NULL,
  USERNAME      VARCHAR(200) NOT NULL,
  PASSWORD      VARCHAR(500) NOT NULL,
  SCHEMA_NAME   VARCHAR(200),
  CREATE_TIME   VARCHAR(50),
  UPDATE_TIME   VARCHAR(50)
);

COMMENT ON TABLE DIG_GEO.DB_CONNECTIONS IS '外部数据库连接配置表';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.CONN_ID IS '连接ID (UUID)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.CONN_NAME IS '连接名称 (用户自定义)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.DB_TYPE IS '数据库类型: MySQL / Dameng';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.HOST IS '主机IP地址';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.PORT IS '端口号';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.USERNAME IS '数据库用户名';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.PASSWORD IS '数据库密码 (内网明文存储)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.SCHEMA_NAME IS '模式名 (Schema/Database)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.CREATE_TIME IS '创建时间 (yyyy-MM-dd HH:mm:ss)';
COMMENT ON COLUMN DIG_GEO.DB_CONNECTIONS.UPDATE_TIME IS '最后修改时间 (yyyy-MM-dd HH:mm:ss)';

-- ============================================================
-- 索引
-- ============================================================
CREATE INDEX IF NOT EXISTS IDX_DB_CONN_NAME ON DIG_GEO.DB_CONNECTIONS(CONN_NAME);

-- ============================================================
-- 验证
-- ============================================================
-- 执行完成后可运行以下语句验证表是否创建成功：
-- SELECT TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = 'DIG_GEO' AND TABLE_NAME = 'DB_CONNECTIONS';
-- SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = 'DIG_GEO' AND TABLE_NAME = 'DB_CONNECTIONS';
