# 数据库重建 任务清单

> **关联**: 需求文档 §2 | 概要设计 §8  
> **Schema**: `DIG_GEO` (替代旧 `L7VP_PROJECTS`)  
> **数据库**: 达梦 DM8

---

## 任务列表

### DDL 编写
- [x] **DB-001** 编写 `PROJECTS` 表 DDL
  - 字段: `project_id` VARCHAR(50) PK, `project_name` VARCHAR(200), `description` VARCHAR(500), `create_time` VARCHAR(50), `update_time` VARCHAR(50), `thumbnail` VARCHAR(500), `asset_package_ids` VARCHAR(500)
  - 索引: `IDX_PROJECT_NAME`, `IDX_CREATE_TIME`

- [x] **DB-002** 编写 `DATASETS` 表 DDL
  - 字段: `dataset_id` VARCHAR(50) PK, `project_id` VARCHAR(50) FK→PROJECTS, `dataset_name` VARCHAR(200), `type` VARCHAR(50), `metadata` CLOB, `create_time` VARCHAR(50)
  - 索引: `IDX_DATASETS_PROJECT`

- [x] **DB-003** 编写 `DATASET_COLUMNS` 表 DDL
  - 字段: `column_id` VARCHAR(50) PK, `dataset_id` VARCHAR(50) FK→DATASETS, `column_name` VARCHAR(200), `column_type` VARCHAR(50), `column_index` INT
  - 索引: `IDX_COLUMNS_DATASET`

- [x] **DB-004** 编写 `DATASET_ROWS` 表 DDL
  - 字段: `row_id` VARCHAR(50) PK, `dataset_id` VARCHAR(50) FK→DATASETS, `row_index` INT, `row_data` CLOB
  - 索引: `IDX_ROWS_DATASET` (dataset_id, row_index)

- [x] **DB-005** 编写 `LAYERS` 表 DDL
  - 字段: `layer_id` VARCHAR(50) PK, `project_id` VARCHAR(50) FK→PROJECTS, `dataset_id` VARCHAR(50) FK→DATASETS(可选), `layer_name` VARCHAR(200), `type` VARCHAR(50), `vis_config` CLOB, `layer_order` INT, `create_time` VARCHAR(50)
  - 索引: `IDX_LAYERS_PROJECT` (project_id, layer_order)

- [x] **DB-006** 编写 `WIDGETS` 表 DDL
  - 字段: `widget_id` VARCHAR(50) PK, `project_id` VARCHAR(50) FK→PROJECTS, `container_id` VARCHAR(50)(自引用,可选), `widget_name` VARCHAR(200), `type` VARCHAR(50), `properties` CLOB, `slot` VARCHAR(50), `widget_order` INT
  - 索引: `IDX_WIDGETS_PROJECT` (project_id, widget_order)

- [x] **DB-007** 编写 `TILE_CONFIG` 表 DDL
  - 字段: `id` VARCHAR(10) PK(固定值"default"), `tile_url` VARCHAR(500), `tile_name` VARCHAR(100), `min_zoom` INT, `max_zoom` INT, `update_time` VARCHAR(50)

- [x] **DB-008** 编写 `ICON_CATEGORIES` 表 DDL
  - 字段: `category_id` VARCHAR(50) PK, `category_name` VARCHAR(100), `sort_order` INT, `create_time` VARCHAR(50)

- [x] **DB-009** 编写 `ICONS` 表 DDL
  - 字段: `icon_id` VARCHAR(50) PK, `category_id` VARCHAR(50) FK→ICON_CATEGORIES, `library_code` VARCHAR(100), `code_name` VARCHAR(100), `file_name` VARCHAR(500), `original_name` VARCHAR(500), `file_type` VARCHAR(20), `file_size` BIGINT, `url` VARCHAR(500), `sort_order` INT, `create_time` VARCHAR(50)
  - 唯一约束: `UNQ_ICON_CODE` (category_id, library_code, code_name)
  - 索引: `IDX_ICONS_CATEGORY` (category_id, sort_order), `IDX_ICONS_LOOKUP` (library_code, code_name)

### 部署文件
- [x] **DB-010** 更新 `java-server/src/main/resources/schema.sql` — 包含完整 DDL + 索引
- [x] **DB-011** 更新/同步 `deploy/backend/config/schema.sql`（如 deploy 目录仍在使用）
- [x] **DB-012** 删除或归档旧 `server/sql/init.sql`（Node 版 DDL）

### 验证
- [x] **DB-013** 在达梦 DM8 上执行 `schema.sql` 验证无报错
- [x] **DB-014** 验证外键约束正确（级联删除: PROJECTS→DATASETS→ROWS/COLUMNS, PROJECTS→LAYERS, PROJECTS→WIDGETS, ICON_CATEGORIES→ICONS）
- [x] **DB-015** 验证唯一约束 `(category_id, library_code, code_name)` 生效
- [x] **DB-016** 验证索引创建无误（查询计划检查）
