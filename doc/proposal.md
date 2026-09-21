# L7VP 功能改造需求文档 (Proposal)

> **版本**: v1.2  
> **日期**: 2026-06-27  
> **状态**: 已确认

---

## 目录

- [1. 背景与目标](#1-背景与目标)
- [2. 需求一：数据库重新设计](#2-需求一数据库重新设计)
- [3. 需求二：图标库管理](#3-需求二图标库管理)
- [4. 需求三：默认瓦片地址配置](#4-需求三默认瓦片地址配置)
- [5. 需求四：iframe 嵌入兼容](#5-需求四iframe-嵌入兼容)
- [6. 实施计划](#6-实施计划)
- [7. 待确认事项](#7-待确认事项)

---

## 1. 背景与目标

### 1.1 现状问题

| 问题 | 说明 |
|------|------|
| **单 JSON 字段膨胀** | `L7VP_PROJECTS.PROJECTS.APPLICATION_CONFIG` 字段是一个 CLOB，存储整个应用的 JSON（datasets 内联数据 + layers + widgets + map）。Excel 上传 10000+ 行时，单字段过大导致保存失败 |
| **图标管理不便** | 图标放在项目目录的子文件夹中，需在服务器上手动操作文件系统，无 UI 管理界面 |
| **瓦片地址硬编码** | 默认瓦片 URL 写死在源码和 `config.js` 中，指向高德公开瓦片；内网部署后只能通过手动改 `config.js` 来调整 |
| **多 tab 跳转** | 部分功能使用 `window.open` 打开新标签页，不利于 iframe 嵌入集成 |

### 1.2 改造目标

1. **数据库遵循三范式重新设计**，拆分主表 + 数据集表 + 数据行表 + 图层表等，解决大数据量保存问题
2. **图标库可视化管理**：弹窗形式的图标上传/删除/分类管理，支持 svg/png 等格式
3. **瓦片地址可配置**：提供配置弹窗，设置默认 XYZ 瓦片地址，持久化存储
4. **全功能单 tab 内跳转**，方便 iframe 嵌入

---

## 2. 需求一：数据库重新设计

### 2.1 Schema 变更

| 项目 | 旧值 | 新值 |
|------|------|------|
| Schema 名 | `L7VP_PROJECTS` | `DIG_GEO` |
| 表数量 | 1 张 (`PROJECTS`) | 6 张（见下方 ER） |
| 数据库 | 达梦 DM8 | 达梦 DM8（不变） |
| 数据迁移 | — | 直接清空重建（无生产数据） |

### 2.2 表结构设计

```
┌──────────────────────────────────────────────────────────────────┐
│                        DIG_GEO Schema                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐    1:N     ┌─────────────────┐              │
│  │    PROJECTS      │◄──────────│    DATASETS     │              │
│  │─────────────────│           │─────────────────│              │
│  │ PK project_id    │           │ PK dataset_id   │              │
│  │    project_name  │           │ FK project_id   │              │
│  │    description   │           │    dataset_name │              │
│  │    create_time   │           │    type         │  (local/     │
│  │    update_time   │           │    metadata     │   remote/     │
│  │    thumbnail     │           │    create_time  │   raster-tile/│
│  └─────────────────┘           └────────┬────────┘   vector-tile)│
│                                         │                        │
│                          ┌──────────────┼──────────────┐         │
│                          │ 1:N          │ 1:N          │         │
│                          ▼              ▼              │         │
│  ┌─────────────────┐  ┌──────────────────────────────────┐       │
│  │ DATASET_COLUMNS │  │       DATASET_ROWS               │       │
│  │─────────────────│  │──────────────────────────────────│       │
│  │ PK column_id    │  │ PK row_id                        │       │
│  │ FK dataset_id   │  │ FK dataset_id                    │       │
│  │    column_name  │  │    row_index     (行序号, 从0开始) │       │
│  │    column_type  │  │    row_data      (CLOB, JSON数组) │       │
│  │    column_index │  └──────────────────────────────────┘       │
│  └─────────────────┘                                             │
│                                                                  │
│  ┌─────────────────┐    1:N     ┌─────────────────┐              │
│  │    PROJECTS      │◄──────────│     LAYERS      │              │
│  │   (同上)         │           │─────────────────│              │
│  └─────────────────┘           │ PK layer_id     │              │
│                                │ FK project_id   │              │
│                                │ FK dataset_id   │ (可选)        │
│                                │    layer_name   │              │
│                                │    type         │ (BubbleLayer │
│                                │    vis_config   │  /TileLayer/  │
│                                │    layer_order  │  IconLayer..) │
│                                │    create_time  │              │
│                                └─────────────────┘              │
│                                                                  │
│  ┌─────────────────┐    1:N     ┌─────────────────┐              │
│  │    PROJECTS      │◄──────────│    WIDGETS      │              │
│  │   (同上)         │           │─────────────────│              │
│  └─────────────────┘           │ PK widget_id    │              │
│                                │ FK project_id   │              │
│                                │ FK container_id │ (可选, 自引用) │
│                                │    widget_name  │              │
│                                │    type         │              │
│                                │    properties   │ (CLOB, JSON) │
│                                │    slot         │              │
│                                │    widget_order │              │
│                                └─────────────────┘              │
│                                                                  │
│  ┌─────────────────┐                                             │
│  │   TILE_CONFIG   │  (全局配置表, 仅1条记录)                      │
│  │─────────────────│                                             │
│  │ PK id           │                                             │
│  │    tile_url     │  (XYZ瓦片地址模板)                           │
│  │    tile_name    │  (底图名称)                                  │
│  │    min_zoom     │                                             │
│  │    max_zoom     │                                             │
│  │    update_time  │                                             │
│  └─────────────────┘                                             │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.3 各表详细说明

#### PROJECTS（项目主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `project_id` | VARCHAR(50) | 主键，UUID |
| `project_name` | VARCHAR(200) | 项目名称 |
| `description` | VARCHAR(500) | 项目描述 |
| `create_time` | VARCHAR(50) | 创建时间 yyyy-MM-dd HH:mm:ss |
| `update_time` | VARCHAR(50) | 最后修改时间 |
| `thumbnail` | VARCHAR(500) | 缩略图 URL |
| `asset_package_ids` | VARCHAR(500) | JSON 数组，资产包 ID 列表 |

> **索引**: `IDX_PROJECT_NAME`, `IDX_CREATE_TIME`

#### DATASETS（数据集表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `dataset_id` | VARCHAR(50) | 主键 |
| `project_id` | VARCHAR(50) | 外键 → PROJECTS |
| `dataset_name` | VARCHAR(200) | 数据集名称 |
| `type` | VARCHAR(50) | 枚举：`local` / `remote` / `raster-tile` / `vector-tile` / `doris`（预留） |
| `metadata` | CLOB | JSON，数据集元信息（不同类型有不同结构） |
| `create_time` | VARCHAR(50) | 创建时间 |

> `metadata` 内容示例（按 type 不同）：
> - `local`: `{"columnsMeta": [...], "rowCount": 10000}`
> - `raster-tile`: `{"url": "...", "minZoom": 0, "maxZoom": 18, "tileSize": 256}`
> - `remote`: `{"serviceUrl": "...", "serviceType": "wms", "layers": "..."}`
> - `doris`（预留）: `{"jdbcUrl": "...", "tableName": "...", "username": "..."}`

#### DATASET_COLUMNS（数据集列定义表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `column_id` | VARCHAR(50) | 主键 |
| `dataset_id` | VARCHAR(50) | 外键 → DATASETS |
| `column_name` | VARCHAR(200) | 列名（如 "城市", "人口", "GDP"） |
| `column_type` | VARCHAR(50) | 数据类型：`string` / `number` / `boolean` / `date` |
| `column_index` | INT | 列序号（0 开始），对应 row_data JSON 数组的下标 |

> **作用**: 列名只存一次，避免在每行数据中重复。10000 行 × 20 列 × 平均 5 字符列名 ≈ 节省 ~1MB

#### DATASET_ROWS（数据行表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `row_id` | VARCHAR(50) | 主键 |
| `dataset_id` | VARCHAR(50) | 外键 → DATASETS |
| `row_index` | INT | 行序号（0 开始） |
| `row_data` | CLOB | JSON 数组，按 column_index 顺序存储值 |

> **示例**: 列定义为 `[{idx:0, name:"城市"}, {idx:1, name:"人口"}, {idx:2, name:"GDP"}]`，行数据为 `["北京", 21540000, 41610]`

> **批量插入优化**: 使用 JDBC batch insert，10000 行数据预计 < 5 秒写入

#### LAYERS（图层表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `layer_id` | VARCHAR(50) | 主键 |
| `project_id` | VARCHAR(50) | 外键 → PROJECTS |
| `dataset_id` | VARCHAR(50) | 外键 → DATASETS（允许为空） |
| `layer_name` | VARCHAR(200) | 图层名称 |
| `type` | VARCHAR(50) | 图层类型：`BubbleLayer` / `TileLayer` / `IconLayer` / `FlowLayer` 等 |
| `vis_config` | CLOB | JSON，可视化配置（颜色、半径、透明度、图标等） |
| `layer_order` | INT | 图层叠放顺序 |
| `create_time` | VARCHAR(50) | 创建时间 |

#### WIDGETS（组件表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `widget_id` | VARCHAR(50) | 主键 |
| `project_id` | VARCHAR(50) | 外键 → PROJECTS |
| `container_id` | VARCHAR(50) | 父容器 widget_id（可选，用于嵌套布局） |
| `widget_name` | VARCHAR(200) | 组件名称 |
| `type` | VARCHAR(50) | 组件类型：`AnalysisLayout` / `ZoomControl` / `Legend` 等 |
| `properties` | CLOB | JSON，组件属性配置 |
| `slot` | VARCHAR(50) | 插槽名（如 `controls`） |
| `widget_order` | INT | 排序 |

#### TILE_CONFIG（瓦片全局配置表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(10) | 主键，固定值 `"default"`（单例） |
| `tile_url` | VARCHAR(500) | XYZ 瓦片 URL 模板（含 `{x} {y} {z}` 占位符） |
| `tile_name` | VARCHAR(100) | 底图显示名称（如 "内网卫星影像"） |
| `min_zoom` | INT | 最小缩放级别，默认 0 |
| `max_zoom` | INT | 最大缩放级别，默认 18 |
| `update_time` | VARCHAR(50) | 最后修改时间 |

> **单例设计**: 全局仅一条配置，使用 UPSERT（INSERT ON DUPLICATE UPDATE 或 MERGE INTO）

### 2.4 与原 Application JSON 的映射关系

```
原 APPLICATION_CONFIG (单 JSON)          新表结构
─────────────────────────────────        ──────────
metadata.name / description / ...    →   PROJECTS
datasets[].metadata / type           →   DATASETS
datasets[].columns                   →   DATASET_COLUMNS
datasets[].data[]                    →   DATASET_ROWS
spec.map                             →   (通过 TILE_CONFIG + basemap='Map' 替代)
spec.layers[]                        →   LAYERS
spec.widgets[]                       →   WIDGETS
```

### 2.5 扩展性预留

| 预留项 | 方式 |
|------|------|
| Doris 数据源 | `DATASETS.type` 枚举预留 `doris`，`metadata` JSON 中存储 JDBC 连接信息 |
| 其他数据库源 | 同上，新增 type 枚举值即可 |
| 列级索引 | `DATASET_COLUMNS` 预留 `column_index`，未来可加索引 |

### 2.6 API 变更

后端 API 需从**单 JSON 读写**改为**多表联查**。核心变更：

| 旧接口 | 新接口 | 说明 |
|------|------|------|
| `GET /api/projects` | 不变 | 列表仅查 PROJECTS 表，不含 datasets/layers |
| `GET /api/projects/{id}` | 调整 | 返回时联查 DATASETS + LAYERS + WIDGETS 组装完整 JSON（兼容前端） |
| `POST /api/projects` | 调整 | 需要事务写入多表 |
| `PUT /api/projects/{id}` | 调整 | 需要增量更新（判断 datasets/layers 的增删改） |
| — | `GET /api/projects/{id}/datasets/{dsId}/rows?page=0&size=500` | **新增**，分页获取数据行，避免一次性加载全量 |

> **兼容性考虑**: 前端现有代码期望接收完整的 `Application` JSON 对象。建议在 Service 层做一次**组装**，将多表查询结果重新拼装为原来的 `Application` 结构返回给前端，减少前端改动。保存时反向拆解写入各表。

### 2.7 已确认

- [x] **数据行存储格式**: ✅ 使用 JSON 数组（`["北京", 21540000]`）+ DATASET_COLUMNS 列定义表
- [x] **WIDGETS 表拆分**: ✅ 也拆，遵循三范式，保证大数据量生成的图表等不影响主表
- [x] **分页策略**: ✅ 500 条/页

---

## 3. 需求二：图标库管理

### 3.1 存储方案

```
┌─────────────────────────────────────────────────────┐
│                   图标存储架构                        │
├─────────────────────────────────────────────────────┤
│                                                     │
│   ┌──────────────┐        ┌──────────────────┐      │
│   │  文件系统      │        │    数据库 (DIG_GEO) │      │
│   │  (服务器本地)   │        │                  │      │
│   │              │        │  ICON_CATEGORIES  │      │
│   │ /opt/l7vp/   │  ◄───►│  ICONS            │      │
│   │   icons/     │  元数据 │                  │      │
│   │   ├─ cat_01/ │  关联   └──────────────────┘      │
│   │   │  icon_a.svg                                  │
│   │   │  icon_b.png                                  │
│   │   └─ cat_02/                                     │
│   │      icon_c.svg                                  │
│   └──────────────┘                                   │
│                                                     │
│   Java WebConfig 映射 /icons/** → 文件系统目录        │
│   静态资源 serve，无需 MinIO                          │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### 数据库表设计

**ICON_CATEGORIES（图标分类表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `category_id` | VARCHAR(50) | 主键 |
| `category_name` | VARCHAR(100) | 分类名称（用户可自定义，如 "通信"、"默认"、"军事"） |
| `sort_order` | INT | 排序序号 |
| `create_time` | VARCHAR(50) | 创建时间 |

**ICONS（图标表）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `icon_id` | VARCHAR(50) | 主键 |
| `category_id` | VARCHAR(50) | 外键 → ICON_CATEGORIES |
| `library_code` | VARCHAR(100) | **库号**，用户自定义，字符串类型 |
| `code_name` | VARCHAR(100) | **代号**，用户自定义，字符串类型 |
| `file_name` | VARCHAR(500) | 服务器存储的文件名（UUID 重命名避免冲突） |
| `original_name` | VARCHAR(500) | 原始上传文件名 |
| `file_type` | VARCHAR(20) | 文件类型：`svg` / `png` / `jpg` / `gif` / `webp` |
| `file_size` | BIGINT | 文件大小（字节） |
| `url` | VARCHAR(500) | 访问 URL（如 `/icons/cat_01/a1b2c3.svg`） |
| `sort_order` | INT | 分类内排序 |
| `create_time` | VARCHAR(50) | 上传时间 |

> **唯一约束**: `(category_id, library_code, code_name)` 联合唯一，同一分类下库号+代号不可重复

#### 文件存储规则

- 存储路径: `{l7vp.icons.path}/{category_id}/{uuid}.{ext}`
- 文件名: UUID 去冲突，保留原始扩展名
- 访问 URL: `/icons/{category_id}/{uuid}.{ext}`
- 配置项 `l7vp.icons.path` 在 `application.properties` 中，默认开发环境 `../website/public/icons`，生产环境 `/opt/l7vp/icons`

### 3.2 图标在图层中的使用方式

#### 方式一：固定图标（保留现有功能）

图层中所有数据点使用**同一个图标**，通过 IconSelector 直接选择某个图标。

#### 方式二：按数据字段匹配图标（新增）

图层配置中指定**两个数据字段**分别对应库号和代号：

```
数据行示例:
{ "lon": 116.4, "lat": 39.9, "库号": "通信", "代号": "antenna" }
{ "lon": 121.5, "lat": 31.2, "库号": "通信", "代号": "tower" }
{ "lon": 113.3, "lat": 23.1, "库号": "默认", "代号": "flag-red" }
```

图层配置中用户选择：
- `iconLibraryField`: 数据中哪个字段是库号 → 选择 "库号"
- `iconCodeField`: 数据中哪个字段是代号 → 选择 "代号"

渲染时：`库号=通信, 代号=antenna` → 查询 ICONS 表 → 取 URL → 渲染该图标

> **未匹配处理**: 库号+代号查不到时，使用图层配置的 fallback 默认图标

### 3.3 图标管理弹窗 UI

#### 入口

- 位置：项目列表页 (`/project`) 右上角，"新建项目"按钮旁边
- 按钮文字：**"图标库"**，带图标（如 `PictureOutlined`）

#### 弹窗布局

```
┌─────────────────────────────────────────────────────────┐
│  图标库管理                                    [X] 关闭  │
├──────────────┬──────────────────────────────────────────┤
│  分类列表     │  图标列表 (网格)                          │
│              │                                          │
│  ┌────────┐  │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐   │
│  │ 默认    │  │  │ icon │ │ icon │ │ icon │ │ icon │   │
│  │ 通信    │  │  │ .svg │ │ .png │ │ .svg │ │ .png │   │
│  │ 军事    │  │  └──────┘ └──────┘ └──────┘ └──────┘   │
│  │ 配置    │  │  ┌──────┐ ┌──────┐                     │
│  └────────┘  │  │ icon │ │ icon │  ...                 │
│              │  │ .svg │ │ .png │                      │
│  [+ 新增分类] │  └──────┘ └──────┘                      │
│              │                                          │
│  [编辑分类]   │  [上传图标] [批量删除]                    │
│  [删除分类]   │                                          │
│              │  每个图标卡片显示:                         │
│              │  - 缩略图                                │
│              │  - 文件名                                 │
│              │  - 库号 / 代号                            │
│              │  - 删除按钮                               │
│              │  - 编辑按钮（修改库号/代号）                 │
│              │                                          │
├──────────────┴──────────────────────────────────────────┤
│  底部栏: 共 X 个分类, Y 个图标                            │
└─────────────────────────────────────────────────────────┘
```

#### 功能操作

| 操作 | 交互方式 | 说明 |
|------|---------|------|
| **新增分类** | 点击左侧 "[+]" → 弹出输入框 → 填写分类名 → 确认 | |
| **重命名分类** | 右键分类 / 点击编辑按钮 → 内联编辑 | |
| **删除分类** | 点击删除 → 二次确认弹窗 "将删除该分类下所有 N 个图标，确认？" | |
| **拖动排序** | 分类列表和图标网格均支持拖拽排序 | 使用 `@hello-pangea/dnd`（项目已有依赖） |
| **上传图标** | 点击"上传图标" → 文件选择器（支持 svg/png/jpg/gif/webp，多选） | 最大单文件 2MB，不支持格式时弹窗提示 |
| **批量上传** | 一次选择多个文件 → 逐个上传 | 显示进度条 |
| **编辑图标** | 点击图标卡片的编辑按钮 → 修改库号/代号 | 库号+代号在同一分类下不可重复 |
| **删除图标** | 点击图标卡片的删除按钮 → 确认弹窗 → 删除文件和数据库记录 | |
| **缩略图** | 上传时自动生成小尺寸预览图（用于弹窗网格展示） | SVG: 保持矢量；PNG/JPG: 缩放到 80x80 |

### 3.4 后端 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/icons/categories` | 获取全部分类（含排序） |
| `POST` | `/api/icons/categories` | 新增分类 |
| `PUT` | `/api/icons/categories/{id}` | 重命名分类 / 调整排序 |
| `DELETE` | `/api/icons/categories/{id}` | 删除分类及下属所有图标 |
| `PUT` | `/api/icons/categories/reorder` | 批量更新分类排序 |
| `GET` | `/api/icons?categoryId={id}` | 获取某分类下图标列表 |
| `POST` | `/api/icons/upload` | 上传图标（multipart/form-data），参数: categoryId, libraryCode, codeName, file |
| `PUT` | `/api/icons/{id}` | 修改库号/代号 |
| `DELETE` | `/api/icons/{id}` | 删除单个图标 |
| `PUT` | `/api/icons/reorder` | 批量更新图标排序 |
| `GET` | `/api/icons/lookup?lib={库号}&code={代号}` | 根据库号+代号查找图标 URL（图层渲染用） |

> **兼容**: 现有 `GET /api/icons` 接口保留，改为从数据库 + 文件系统返回数据

### 3.5 已确认

- [x] **缩略图生成**: ✅ 不需要
- [x] **库号和代号交互**: ✅ 上传后列表统一编辑（上传时不弹窗，上传完成后在图标卡片上批量编辑库号/代号）
- [x] **图标移动到其他分类**: ✅ 可以做，拖拽式移动；工作量大则后续迭代

---

## 4. 需求三：默认瓦片地址配置

### 4.1 现状

- 硬编码位置: `website/public/config.js` 和 `website/src/pages/Project/herlper.ts`
- 默认值: `https://webst01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}`（高德卫星图）
- `config.js` 可以实现不重编译修改，但仍需手动编辑服务器文件

### 4.2 改造方案

#### 配置入口

- 位置：项目列表页 (`/project`) 右上角，"新建项目"和"图标库"按钮旁边
- 按钮文字：**"瓦片配置"**，带地图图标

#### 配置弹窗

```
┌─────────────────────────────────────────────┐
│  默认瓦片配置                         [X]    │
├─────────────────────────────────────────────┤
│                                             │
│  底图名称:  [内网卫星影像          ]         │
│                                             │
│  瓦片URL:   [https://xxx.com/tile/         ]│
│             {x}{y}{z}                       │
│             (URL 需包含 {x} {y} {z} 占位符)   │
│                                             │
│  最小缩放:  [0  ]                           │
│  最大缩放:  [18 ]                           │
│                                             │
│  [测试预览]  (打开一个简易地图预览瓦片效果)    │
│                                             │
├─────────────────────────────────────────────┤
│              [取消]    [保存]                │
└─────────────────────────────────────────────┘
```

#### 持久化与加载

```
优先级链:
  数据库 TILE_CONFIG 表 (有值)
    ↓ 无则
  config.js window.L7VP_CONFIG (兜底)
    ↓ 无则
  herlper.ts 硬编码高德 URL (最终兜底)
```

- 用户在弹窗中保存 → 写入 `DIG_GEO.TILE_CONFIG` 表
- Java 后端新增 `GET /api/tile-config`（公开接口，前端读取）
- `herlper.ts` 的 `creatApplication()` 调用此接口获取当前瓦片 URL，取代硬编码
- 如果数据库也无配置，降级到 `config.js`，最终降级到硬编码值

#### 后端 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/tile-config` | 获取当前瓦片配置（公开） |
| `PUT` | `/api/tile-config` | 更新瓦片配置 |

#### 新建项目时的行为

保持不变：自动创建一个 `raster-tile` 数据集 + 一个 `TileLayer` 图层，使用配置的瓦片 URL。

### 4.3 已确认

- [x] **测试预览**: ✅ 不需要
- [x] **URL 格式校验**: ✅ URL 包含 `{x}{y}{z}`，内网瓦片肯定可用，校验可做可不做

---

## 5. 需求四：iframe 嵌入兼容

### 5.1 现状

需要排查项目中所有 `window.open()` 调用，将其替换为同 tab 内导航或弹窗。

已知位置：

| 文件 | 功能 | 当前行为 | 改造 |
|------|------|---------|------|
| Preview 组件 | 预览项目 | `window.open` 新标签 | 同一 tab 跳转到 `/preview/{id}` |
| Export 组件 | 导出项目 | 可能打开新标签 | 同 tab 下载或弹窗展示 |

### 5.2 改造原则

- 所有页面跳转使用 React Router 的 `useNavigate` / `<Link>` 组件
- 外部链接确实需要新标签的保留（如导出 HTML 下载）
- 弹窗类交互全部使用 antd Modal/Drawer

### 5.3 已确认

- [x] **Preview（预览）**: ✅ 可以保留新开 tab
- [x] **New Project（新建项目）**: ✅ 不要新开 tab，使用弹窗或同页面跳转
- [x] **全局排查**: ✅ 除 Preview 外，其余功能统一在同一 tab 内跳转或弹窗

---

## 6. 实施计划

### 6.1 建议分阶段实施

| 阶段 | 内容 | 预估工作量 | 依赖 |
|------|------|-----------|------|
| **Phase 1** | 数据库重建：DDL + Java 后端 CRUD 改造 | 核心 | — |
| **Phase 2** | 前端适配：兼容 Application JSON 组装/拆解 | 中 | Phase 1 |
| **Phase 3** | 图标库：后端 API + 文件管理 + 弹窗 UI | 中 | Phase 1 |
| **Phase 4** | 瓦片配置：后端 API + 配置弹窗 | 小 | Phase 1 |
| **Phase 5** | iframe 兼容：排查替换 window.open | 小 | — |
| **Phase 6** | 图层图标按库号+代号匹配功能 | 中 | Phase 3 |

### 6.2 涉及改造的文件范围

```
数据库:
  java-server/src/main/resources/schema.sql    [重写]
  server/sql/init.sql                          [同步更新, Node版兜底]
  deploy/backend/config/schema.sql             [同步更新]

Java 后端:
  java-server/.../model/Project.java           [重写, 拆分多个Model]
  java-server/.../repository/                   [重写 + 新增多个Repository]
  java-server/.../service/                      [重写 + 新增]
  java-server/.../controller/                   [改造 + 新增IconController/TileConfigController]
  java-server/.../config/WebConfig.java         [调整静态资源映射]
  java-server/src/main/resources/application.properties  [新增配置项]

前端:
  website/src/pages/Project/herlper.ts          [改造, 读取API配置]
  website/src/services/project.ts               [调整API调用]
  website/src/services/icon.ts                  [调整API调用]
  website/src/pages/Project/index.tsx           [新增图标库/瓦片配置按钮]
  website/src/pages/Builder/                    [适配新数据结构]
  website/src/pages/Builder/widgets/            [Preview/Export etc.]
  website/src/types/icon.ts                     [更新类型]
  packages/li-sdk/src/specs/                    [可能需要调整类型定义]
  packages/li-p2/src/LayerAttribute/            [图标图层支持库号+代号匹配]

新增:
  website/src/components/IconLibraryModal/      [图标库管理弹窗]
  website/src/components/TileConfigModal/       [瓦片配置弹窗]
  java-server/.../model/IconCategory.java       [新增]
  java-server/.../model/IconItem.java           [新增]
  java-server/.../model/TileConfig.java         [新增]
```

---

## 7. 确认事项汇总

| # | 问题 | 优先级 | 状态 |
|---|------|--------|------|
| 1 | 数据行存 JSON 数组 + 列定义表，还是保留 JSON 对象（含列名）？ | 🔴 高 | ✅ JSON 数组 + 列定义表 |
| 2 | WIDGETS 组件表拆不拆？ | 🟡 中 | ✅ 拆，遵循三范式 |
| 3 | 数据行分页大小 500 条/页合适吗？ | 🟡 中 | ✅ 500 条/页 |
| 4 | 图标缩略图生成需要吗？ | 🟢 低 | ✅ 不需要 |
| 5 | 图标上传后库号/代号编辑交互方式？ | 🔴 高 | ✅ 上传后列表统一编辑 |
| 6 | 图标移动到其他分类？ | 🟢 低 | ✅ 拖拽移动，大则后做 |
| 7 | 瓦片配置是否需要测试预览功能？ | 🟢 低 | ✅ 不需要 |
| 8 | URL 必须含 {x}{y}{z} 校验？ | 🟡 中 | ✅ 内网 URL 含 xyz，校验可选 |
| 9 | window.open 排查范围？ | 🟡 中 | ✅ Preview 可新 tab，其余同 tab |

---

> 本文档为需求 proposal，最终实施方案需评审确认后执行。
