-- ========================================
-- L7VP项目数据库初始化脚本
-- 数据库类型：达梦数据库
-- ========================================

-- 1. 创建Schema（如果不存在）
CREATE SCHEMA IF NOT EXISTS L7VP_PROJECTS;

-- 2. 创建项目表
CREATE TABLE IF NOT EXISTS L7VP_PROJECTS.PROJECTS (
  PROJECT_ID VARCHAR(50) PRIMARY KEY,
  PROJECT_NAME VARCHAR(200),
  DESCRIPTION VARCHAR(500),
  CREATE_TIME VARCHAR(50),
  APPLICATION_CONFIG CLOB,
  ASSET_PACKAGE_IDS VARCHAR(500)
);

-- 3. 创建索引（可选，提高查询性能）
CREATE INDEX IF NOT EXISTS IDX_PROJECT_NAME ON L7VP_PROJECTS.PROJECTS(PROJECT_NAME);
CREATE INDEX IF NOT EXISTS IDX_CREATE_TIME ON L7VP_PROJECTS.PROJECTS(CREATE_TIME);

-- ========================================
-- 说明：
-- 1. PROJECT_ID: 项目唯一标识符（UUID格式）
-- 2. PROJECT_NAME: 项目名称
-- 3. DESCRIPTION: 项目描述
-- 4. CREATE_TIME: 创建时间
-- 5. APPLICATION_CONFIG: 应用配置（JSON格式，使用CLOB存储）
-- 6. ASSET_PACKAGE_IDS: 资产包ID列表（JSON格式数组）
-- ========================================