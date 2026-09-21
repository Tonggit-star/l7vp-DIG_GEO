# L7VP 概要设计文档 (High-Level Design)

> **版本**: v1.1  
> **日期**: 2026-06-27  
> **状态**: 已确认  
> **关联需求**: [proposal.md](./proposal.md)

---

## 目录

- [1. 设计概述](#1-设计概述)
- [2. 系统架构](#2-系统架构)
- [3. 模块划分](#3-模块划分)
- [4. 模块间关系与数据流](#4-模块间关系与数据流)
- [5. 后端设计](#5-后端设计)
- [6. 前端设计](#6-前端设计)
- [7. API 设计](#7-api-设计)
- [8. 数据库设计概要](#8-数据库设计概要)
- [9. 关键时序图](#9-关键时序图)
- [10. 配置与部署](#10-配置与部署)
- [11. 待确认事项](#11-待确认事项)

---

## 1. 设计概述

### 1.1 设计目标

| 目标 | 说明 |
|------|------|
| **大数据量支持** | Excel 10000+ 行数据可正常保存和加载，不再受单 CLOB 字段限制 |
| **数据库规范化** | Schema 从单表改为 9 张表，遵循三范式 |
| **图标可视化管理** | 弹窗形式的图标上传/删除/分类/排序管理 |
| **瓦片地址可配置** | 全局默认 XYZ 瓦片 URL 通过弹窗配置，持久化到数据库 |
| **iframe 兼容** | 除 Preview 外，所有功能在同一 tab 内完成 |
| **前端改动最小化** | 后端负责 Application JSON 组装/拆解，尽量保持前端现有数据结构 |

### 1.2 核心设计决策

| # | 决策 | 说明 |
|---|------|------|
| D1 | **数据行与元数据分离** | 项目元数据通过组装后的 Application JSON 传输，数据行通过独立 API 传输 |
| D2 | **后端组装/拆解** | Java Service 层负责多表 ↔ Application JSON 的转换，前端无感 |
| D3 | **按需懒加载** | 项目打开时先加载元数据（秒开），数据行异步拉取（略有延迟） |
| D4 | **前端解析 Excel** | 复用现有 `xlsx` 库逻辑，解析后通过批量 API 一次性 POST 到后端 |
| D5 | **自动保存去数据化** | 自动保存只保存元数据，不含数据行，大幅缩小保存体积 |

---

## 2. 系统架构

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                          前端 (React + TypeScript)                    │
│                                                                     │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐   │
│  │  项目管理页面      │  │  Builder 编辑器    │  │  Preview 预览     │   │
│  │  /project         │  │  /builder/:id     │  │  /preview/:id    │   │
│  │                  │  │                  │  │                  │   │
│  │ - 项目列表        │  │ - LIEditor       │  │ - 独立地图展示    │   │
│  │ - IconLibraryModal│  │ - EditorState    │  │                  │   │
│  │ - TileConfigModal │  │ - RuntimeApp     │  │                  │   │
│  │ - AddOrEditProject│  │ - DatasetsPanel  │  │                  │   │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘   │
│           │                     │                     │              │
│           │          ┌──────────┴──────────┐          │              │
│           │          │  Application        │          │              │
│           │          │  Assembler          │          │              │
│           │          │  (数据组装/拆解适配)   │          │              │
│           │          └──────────┬──────────┘          │              │
│           │                     │                     │              │
│           └──────────┬──────────┴─────────────────────┘              │
│                      │                                              │
│              HTTP REST API                                           │
└──────────────────────┼──────────────────────────────────────────────┘
                       │
┌──────────────────────┼──────────────────────────────────────────────┐
│                      ▼           后端 (Java Spring Boot)              │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      Controller 层                             │   │
│  │  ProjectController │ IconController │ TileConfigController   │   │
│  │  ThumbnailController (现有)                                    │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                │                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                       Service 层                               │   │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌───────────┐  │   │
│  │  │ Project    │ │ Dataset    │ │ Icon       │ │ TileConfig│  │   │
│  │  │ Service    │ │ Service    │ │ Service    │ │ Service   │  │   │
│  │  └────────────┘ └────────────┘ └────────────┘ └───────────┘  │   │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────────────┐     │   │
│  │  │ Layer      │ │ Widget     │ │ Application           │     │   │
│  │  │ Service    │ │ Service    │ │ Assembler (组装/拆解)    │     │   │
│  │  └────────────┘ └────────────┘ └────────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                │                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                     Repository 层 (JdbcTemplate)               │   │
│  │  ProjectRepo │ DatasetRepo │ DatasetColumnRepo │ DatasetRowRepo│   │
│  │  LayerRepo   │ WidgetRepo  │ IconCategoryRepo  │ IconRepo     │   │
│  │  TileConfigRepo                                              │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                │                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                      数据层                                    │   │
│  │  ┌─────────────────────┐    ┌────────────────────────────┐    │   │
│  │  │ 达梦 DM8 (DIG_GEO)   │    │ 文件系统                     │    │   │
│  │  │ - 9 张业务表         │    │ - /opt/l7vp/icons/          │    │   │
│  │  │                     │    │ - /opt/l7vp/thumbnails/      │    │   │
│  │  └─────────────────────┘    └────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 技术栈

| 层 | 技术 | 说明 |
|---|------|------|
| 前端框架 | React 18 + TypeScript | 不变 |
| UI 组件库 | antd 5.x | 不变 |
| 状态管理 | EditorState (immer + Subscribable) | 不变 |
| 拖拽 | @hello-pangea/dnd | 不变（图标库分类/图标排序使用） |
| Excel 解析 | xlsx (SheetJS) | 不变 |
| 后端框架 | Java Spring Boot 2.x | 不变 |
| 数据库访问 | Spring JdbcTemplate | 不变 |
| 数据库 | 达梦 DM8 | 不变 |
| 文件存储 | 服务器本地文件系统 | 不变（无 MinIO 依赖） |

---

## 3. 模块划分

### 3.1 后端模块

```
java-server/src/main/java/com/antv/l7vp/
├── controller/
│   ├── ProjectController.java      [改造] 项目 CRUD + 数据集上传入口
│   ├── IconController.java         [重写] 图标分类/图标管理 + 文件上传
│   ├── TileConfigController.java   [新增] 瓦片全局配置
│   └── ThumbnailController.java    [保留] 缩略图上传
├── service/
│   ├── ProjectService.java         [重写] 项目 CRUD + 组装/拆解编排
│   ├── DatasetService.java         [新增] 数据集元数据 + 列定义 + 行数据
│   ├── LayerService.java           [新增] 图层 CRUD
│   ├── WidgetService.java          [新增] 组件 CRUD
│   ├── IconService.java            [新增] 图标分类/图标管理 + 文件操作
│   ├── TileConfigService.java      [新增] 瓦片配置读写
│   └── ApplicationAssembler.java   [新增] Application JSON 组装/拆解
├── repository/
│   ├── ProjectRepository.java      [重写]
│   ├── DatasetRepository.java      [新增]
│   ├── DatasetColumnRepository.java[新增]
│   ├── DatasetRowRepository.java   [新增]
│   ├── LayerRepository.java        [新增]
│   ├── WidgetRepository.java       [新增]
│   ├── IconCategoryRepository.java [新增]
│   ├── IconRepository.java         [新增]
│   └── TileConfigRepository.java   [新增]
├── model/
│   ├── Project.java                [改造]
│   ├── Dataset.java                [新增]
│   ├── DatasetColumn.java          [新增]
│   ├── DatasetRow.java             [新增]
│   ├── Layer.java                  [新增]
│   ├── Widget.java                 [新增]
│   ├── IconCategory.java           [新增]
│   ├── IconItem.java               [新增]
│   └── TileConfig.java             [新增]
└── config/
    └── WebConfig.java              [改造] 图标目录静态资源映射
```

#### 模块职责

| 模块 | 职责 | 依赖 |
|------|------|------|
| **ProjectController** | 项目 REST API，接收/返回 Application JSON | ProjectService |
| **IconController** | 图标分类 CRUD、图标上传/删除/排序、库号+代号查找 | IconService |
| **TileConfigController** | 瓦片配置的读写 | TileConfigService |
| **ProjectService** | 项目 CRUD 编排，调用 Assembler 进行 JSON ↔ 多表转换 | ApplicationAssembler, DatasetService, LayerService, WidgetService |
| **DatasetService** | 数据集元数据管理、列定义管理、行数据批量操作、分页查询 | DatasetRepository, DatasetColumnRepository, DatasetRowRepository |
| **LayerService** | 图层 CRUD、排序 | LayerRepository |
| **WidgetService** | 组件 CRUD、嵌套关系、排序 | WidgetRepository |
| **IconService** | 分类 CRUD、图标 CRUD、文件写入/删除、排序 | IconCategoryRepository, IconRepository, File System |
| **TileConfigService** | 全局单例配置 UPSERT | TileConfigRepository |
| **ApplicationAssembler** | 核心转换器：Application JSON → 多表实体 和 多表实体 → Application JSON | 所有 Repository |

### 3.2 前端模块

```
website/src/
├── pages/
│   ├── Project/
│   │   ├── index.tsx                   [改造] 新增图标库/瓦片配置入口按钮
│   │   ├── New.tsx                     [改造] 新建项目改为弹窗，不新开 tab
│   │   ├── herlper.ts                  [改造] creatApplication 改为从 API 读取瓦片配置
│   │   └── components/
│   │       └── AddOrEditProject/
│   │           └── index.tsx           [保留] 新建/编辑项目弹窗
│   ├── Builder/
│   │   ├── index.tsx                   [改造] 数据行异步加载逻辑
│   │   └── widgets/
│   │       ├── Screenshot/             [保留]
│   │       ├── Preview/                [改造] 保留新开 tab
│   │       └── Export/                 [保留]
│   └── Preview/
│       └── App.tsx                     [保留] 新开 tab 预览
├── components/
│   ├── IconLibraryModal/
│   │   ├── index.tsx                   [新增] 图标库管理弹窗主组件
│   │   ├── CategoryPanel.tsx           [新增] 左侧分类列表面板
│   │   ├── IconGrid.tsx                [新增] 右侧图标网格
│   │   ├── IconCard.tsx                [新增] 单个图标卡片
│   │   └── UploadProgress.tsx          [新增] 批量上传进度条
│   └── TileConfigModal/
│       └── index.tsx                   [新增] 瓦片配置弹窗
├── services/
│   ├── project.ts                      [改造] API 调用适配新接口
│   ├── icon.ts                         [重写] 图标 API 调用
│   └── tile-config.ts                  [新增] 瓦片配置 API 调用
└── types/
    ├── icon.ts                         [更新] 图标类型定义
    └── tile-config.ts                  [新增] 瓦片配置类型定义

packages/
├── li-editor/src/
│   ├── widgets/
│   │   ├── DatasetsPanel/              [改造] 数据预览使用分页 API
│   │   └── UploadDataset/              [改造] Excel 上传后走批量 API
│   ├── services/
│   │   ├── editor-state.ts             [改造] 支持懒加载标记 _lazy
│   │   └── editor-dataset-manager.ts   [改造] 支持异步加载数据行
│   └── utils/
│       └── application.ts              [改造] 序列化时排除数据行
└── li-p2/src/LayerAttribute/
    └── IconImageLayerStyle/             [改造] 新增库号/代号字段映射
```

---

## 4. 模块间关系与数据流

### 4.1 模块依赖关系

```
┌────────────────────────────────────────────────────────────┐
│                        前端                                 │
│                                                            │
│  ProjectPage ──→ IconLibraryModal    (弹窗，独立)           │
│       │         TileConfigModal      (弹窗，独立)           │
│       │                                                     │
│       ▼                                                     │
│  Builder ──→ LIEditor ──→ EditorState                      │
│                │              │                             │
│                ▼              ▼                             │
│           RuntimeApp    EditorDatasetManager                │
│           (地图渲染)      (数据集生命周期)                     │
│                │              │                             │
│                └──────┬───────┘                             │
│                       │                                     │
│              自动保存 (debounce 300ms)                       │
│              数据行异步加载                                   │
│                       │                                     │
│                       ▼                                     │
│              API Service Layer                              │
│       (project.ts / icon.ts / tile-config.ts)               │
└───────────────────────┬────────────────────────────────────┘
                        │ HTTP
┌───────────────────────┴────────────────────────────────────┐
│                      后端                                   │
│                       │                                     │
│  Controller ──→ Service ──→ ApplicationAssembler           │
│       │              │              │                       │
│       │              │      ┌───────┴────────┐              │
│       │              │      │  JSON ↔ Tables  │              │
│       │              │      │  组装 / 拆解     │              │
│       │              │      └───────┬────────┘              │
│       │              │              │                       │
│       │              ▼              ▼                       │
│       │         Repository ◄─── 达梦 DM8 (DIG_GEO)          │
│       │                                                     │
│       └──────── IconService ──→ 文件系统 (/opt/l7vp/icons/) │
│                                                        │    │
└────────────────────────────────────────────────────────┘
```

### 4.2 核心数据流

#### 4.2.1 项目加载流程

```
用户打开项目
     │
     ▼
GET /api/projects/{id}
     │
     ▼
后端 ApplicationAssembler.assemble(projectId)
  ├── PROJECTS 表 → 查项目元数据
  ├── DATASETS 表 → 查数据集列表 (不含数据行)
  ├── DATASET_COLUMNS 表 → 查每个数据集的列定义
  ├── LAYERS 表 → 查图层列表
  └── WIDGETS 表 → 查组件列表
     │
     ▼
组装为 Application JSON:
{
  version: "v0.2",
  metadata: { name, description, creatTime },
  datasets: [
    { id, type, metadata, columns: [...], data: [], _rowCount: 10000, _lazy: true }
  ],
  spec: { map: { basemap: "Map" }, layers: [...], widgets: [...] }
}
     │
     ▼
前端接收 → EditorState 初始化
     │
     ├── Map/Layers/Widgets 立即渲染
     │
     └── 检测到 local dataset 有 _lazy: true
            │
            ▼
        异步调用 GET /api/projects/{id}/datasets/{dsId}/rows?page=0&size=500
            │  (循环拉取全部页，或一次性拉取全量)
            ▼
        EditorState 注入数据行
            │
            ▼
        Layer 渲染数据点位
```

#### 4.2.2 Excel 上传并保存流程

```
用户拖拽 Excel 文件
     │
     ▼
前端 xlsx 解析 → 得到 JSON 数组 [{...}, {...}, ...]
     │
     ▼
调用 POST /api/projects/{id}/datasets/upload
  Body: {
    datasetName: "xxx",
    type: "local",
    columns: [{ name: "城市", type: "string", index: 0 }, ...],
    rows: [["北京", 21540000], ["上海", 24870000], ...]
  }
     │
     ▼
后端 DatasetService.createDatasetWithRows()
  事务 {
    1. INSERT INTO DATASETS (...)
    2. INSERT INTO DATASET_COLUMNS (批量)
    3. INSERT INTO DATASET_ROWS (JDBC batch, 每批 500 条)
  }
     │
     ▼
返回 { datasetId, rowCount }
     │
     ▼
前端更新 EditorState
  datasets.push({ id: datasetId, type: "local", data: rows, ... })
     │
     ▼
RuntimeApp 检测到新 dataset → Layer 渲染
     │
     ▼
300ms 后自动保存触发
  PUT /api/projects/{id}
  Body: Application JSON (datasets 中不含 data 数组，只含元数据)
```

#### 4.2.3 自动保存流程（改造后）

```
用户在 Builder 中操作 (改图层颜色 / 加组件 / 调顺序 ...)
     │
     ▼
EditorState 变更 → 300ms debounce
     │
     ▼
序列化当前 state 为 Application JSON
  (serializeApplicationForSave):
  - metadata ✓
  - datasets (只含元数据: id, type, metadata, columns; 不含 data)
  - spec.map ✓
  - spec.layers ✓
  - spec.widgets ✓
     │
     ▼
PUT /api/projects/{id}
     │
     ▼
后端 ApplicationAssembler.disassemble(json)
  事务 {
    1. UPDATE PROJECTS SET project_name, description, update_time
    2. 对比 DATASETS: 新增/更新/删除
    3. 对比 LAYERS: 新增/更新/删除
    4. 对比 WIDGETS: 新增/更新/删除
  }
  (不操作 DATASET_ROWS)
```

### 4.3 模块交互矩阵

| | ProjectService | DatasetService | LayerService | WidgetService | IconService | TileConfigService | ApplicationAssembler |
|---|---|---|---|---|---|---|---|
| **ProjectController** | ✓ | — | — | — | — | — | — |
| **IconController** | — | — | — | — | ✓ | — | — |
| **TileConfigController** | — | — | — | — | — | ✓ | — |
| **ProjectService** | — | ✓ | ✓ | ✓ | — | — | ✓ |
| **DatasetService** | — | — | — | — | — | — | — |
| **IconService** | — | — | — | — | — | — | — |
| **ApplicationAssembler** | — | ✓ | ✓ | ✓ | — | — | — |

> 注: ApplicationAssembler 是一个**工具类**（非 Service），被 ProjectService 调用，负责纯数据转换

---

## 5. 后端设计

### 5.1 ApplicationAssembler（核心转换器）

这是整个改造的**关键模块**，承担 Application JSON ↔ 多表的双向转换。

#### 5.1.1 组装（Assemble）：多表 → JSON

```
输入: projectId
输出: Application (TypeScript 兼容的 JSON)

流程:
  1. ProjectRepository.findById(projectId)           → Project
  2. DatasetRepository.findByProjectId(projectId)     → List<Dataset>
  3. 对每个 Dataset:
     a. DatasetColumnRepository.findByDatasetId()     → List<DatasetColumn>
     b. 组装 columns 数组
     c. 对于 local 类型: 设置 data=[], _lazy=true,
        _rowCount=Dataset.metadata 中的 rowCount
     d. 对于 raster-tile 类型: metadata 中包含 url/minZoom/maxZoom
  4. LayerRepository.findByProjectId(projectId)       → List<Layer>
     (按 layer_order 排序)
  5. WidgetRepository.findByProjectId(projectId)      → List<Widget>
     (按 widget_order 排序，处理 container_id 嵌套)
  6. 组装为完整 Application JSON

返回结构:
{
  "version": "v0.2",
  "metadata": { "name": "...", "description": "...", "creatTime": "..." },
  "datasets": [
    {
      "id": "ds_001",
      "type": "local",
      "metadata": { "name": "数据", "_rowCount": 10000 },
      "columns": [
        { "name": "城市", "type": "string", "index": 0 },
        { "name": "人口", "type": "number", "index": 1 }
      ],
      "data": []              // 空数组，标注懒加载
    }
  ],
  "spec": {
    "map": { "basemap": "Map", "config": { "zoom": 5, "center": [104, 32] } },
    "layers": [ ... ],
    "widgets": [ ... ]
  }
}
```

#### 5.1.2 拆解（Disassemble）：JSON → 多表

```
输入: Application JSON (不含 data 行)
输出: void (写入各表)

流程:
  1. 从 JSON 中提取 projectName, description → UPDATE PROJECTS
  2. 处理 datasets 数组:
     a. 查询当前 DB 中已有的 datasets
     b. 对比 JSON 中的数据集:
        - ID 匹配 → UPDATE (仅更新 metadata 元信息，不操作行数据)
        - JSON 中有、DB 中无 → INSERT
        - DB 中有、JSON 中无 → DELETE (级联删除 columns + rows)
  3. 处理 layers 数组:
     a. 用同样对比策略增删改
  4. 处理 widgets 数组:
     a. 用同样对比策略增删改
     b. 处理 container_id 嵌套关系

对比策略: 基于 ID 做集合运算
  toInsert = jsonList.filter(j => !dbList.find(d => d.id === j.id))
  toUpdate = jsonList.filter(j => dbList.find(d => d.id === j.id))
  toDelete = dbList.filter(d => !jsonList.find(j => j.id === d.id))
```

#### 5.1.3 数据行处理（独立于 Assembler）

数据行**不经过** ApplicationAssembler，由 DatasetService 独立处理：

```
上传: DatasetService.createDatasetWithRows()
      → 单次事务写入 DATASETS + DATASET_COLUMNS + DATASET_ROWS

查询: DatasetService.getRowsByDatasetId(datasetId, page, size)
      → 分页查询 DATASET_ROWS，组装为 { rows: [[...], [...]], total, page, size }

批量删除: DatasetService.deleteRowsByDatasetId(datasetId)
      → 级联删除该数据集所有行
```

### 5.2 关键服务设计

#### 5.2.1 DatasetService

```java
public class DatasetService {

    // 创建数据集并批量写入数据行（事务性）
    @Transactional
    public CreateDatasetResult createDatasetWithRows(
        String projectId,
        String datasetName,
        String type,           // "local"
        List<ColumnDef> columns,
        List<JsonArray> rows   // 每行是一个 JSON 数组
    ) {
        // 1. INSERT DATASETS
        // 2. INSERT DATASET_COLUMNS (batch)
        // 3. INSERT DATASET_ROWS (JDBC batch, batchSize=500)
        // 4. 返回 datasetId + rowCount
    }

    // 分页获取数据行
    public PagedRows getRows(String datasetId, int page, int size) {
        // SELECT * FROM DATASET_ROWS
        // WHERE dataset_id = ? ORDER BY row_index LIMIT ? OFFSET ?
        // 同时返回列定义用于前端组装
    }

    // 删除数据集（级联删除列定义和数据行）
    @Transactional
    public void deleteDataset(String datasetId) {
        // DELETE FROM DATASET_ROWS WHERE dataset_id = ?
        // DELETE FROM DATASET_COLUMNS WHERE dataset_id = ?
        // DELETE FROM DATASETS WHERE dataset_id = ?
    }

    // 更新数据集元信息（名称等）
    public void updateDatasetMeta(String datasetId, String name, JsonNode metadata) {
        // UPDATE DATASETS SET dataset_name = ?, metadata = ? WHERE dataset_id = ?
    }
}
```

#### 5.2.2 IconService

```java
public class IconService {

    private final String iconsBasePath; // 来自 application.properties

    // 分类 CRUD
    public List<IconCategory> listCategories();
    public IconCategory createCategory(String name);
    public IconCategory updateCategory(String id, String name);
    public void deleteCategory(String id); // 级联删除图标 + 文件
    public void reorderCategories(List<CategoryOrder> orders);

    // 图标 CRUD
    public List<IconItem> listIcons(String categoryId);
    public IconItem uploadIcon(String categoryId, MultipartFile file); // 初始库号/代号为空
    public IconItem updateIconMeta(String iconId, String libraryCode, String codeName);
    public void deleteIcon(String iconId); // 删除文件 + 数据库记录
    public void reorderIcons(List<IconOrder> orders);

    // 按库号+代号查找
    public IconItem lookupByCode(String libraryCode, String codeName);

    // 文件存储
    private String saveFile(MultipartFile file, String categoryId) {
        // 1. 生成 UUID 文件名: UUID.randomUUID() + 原始扩展名
        // 2. 确保目录存在: {iconsBasePath}/{categoryId}/
        // 3. 写入文件
        // 4. 返回访问 URL: /icons/{categoryId}/{uuid}.{ext}
    }
}
```

#### 5.2.3 TileConfigService

```java
public class TileConfigService {

    public TileConfig getConfig() {
        // SELECT * FROM TILE_CONFIG WHERE id = 'default'
        // 如果不存在返回 null
    }

    public TileConfig saveConfig(TileConfig config) {
        // MERGE INTO TILE_CONFIG (达梦语法)
        // 或: DELETE + INSERT (简单)
    }
}
```

---

## 6. 前端设计

### 6.1 数据加载策略

当前 EditorState 中数据集的结构：

```typescript
// 当前: 数据内联
type DatasetSchema = {
  id: string;
  type: 'local';
  metadata: { name: string };
  data: any[];    // ← 所有行数据内联在这里
  columns: ColumnSchema[];
};
```

改造后引入懒加载标记：

```typescript
// 改造后: 支持懒加载
type LocalDatasetSchema = BaseDataset & {
  type: 'local';
  metadata: {
    name: string;
    _rowCount?: number;   // 后端返回的总行数
    _lazy?: boolean;      // true = 需要异步加载数据
  };
  data: any[];            // 加载前为 [], 加载后为完整数据
  columns: ColumnSchema[];
};
```

#### 加载时序（改造后：全部加载）

```
Builder 页面挂载
  │
  ├─ 1. 同步加载
  │   GET /api/projects/{id}
  │   → 初始化 EditorState (datasets 中 local 类型 data=[])
  │   → Map 容器渲染（底图可见）
  │   → Layer 面板渲染（图层列表可见，但数据为空）
  │
  └─ 2. 异步全量加载 (useEffect)
      检测 local datasets 有 _lazy=true
      │
      并行请求 (多个数据集同时加载):
      GET /api/projects/{id}/datasets/ds_001/rows?page=0&size=500
      GET /api/projects/{id}/datasets/ds_002/rows?page=0&size=500
      │
      循环拉取每页（前端自动翻页直到拉完所有行）
      │
      ▼
      EditorState.updateState(draft => {
        draft.datasets.find(d => d.id === dsId).data = allLoadedRows  // 全部行
      })
      │
      ▼
      Layer 重渲染 → 图标/气泡等出现在地图上（一次性全部显示）
```

> **确认**: 数据行全部加载后再渲染，不截断。延迟（2-3 秒）可接受。

### 6.2 自动保存序列化改造

```typescript
// 位置: packages/li-editor/src/utils/application.ts

// 新增函数: 序列化时排除数据行
export function getApplicationSchemaForSave(state: EditorContextState): Application {
  const app = getApplicationSchemaFromEditorState(state);

  app.datasets = app.datasets.map(ds => {
    if (ds.type === 'local') {
      return {
        ...ds,
        data: undefined,  // 移除数据行，后端不更新 DATASET_ROWS
      };
    }
    return ds;
  });

  return app;
}
```

### 6.3 Excel 上传流程改造

```typescript
// 位置: packages/li-editor/src/widgets/UploadDataset/

// 改造后流程:
async function handleExcelUpload(file: File, projectId: string) {
  // 1. 前端解析 Excel (不变)
  const source = await parserExcelToSource(file);

  // 2. 构造批量上传请求
  const columns = source.columns.map((col, idx) => ({
    name: col.name,
    type: col.type,
    index: idx,
  }));

  const rows = source.data.map(row =>
    columns.map(col => row[col.name])
  );

  // 3. 调用批量上传 API
  const result = await api.post(`/api/projects/${projectId}/datasets/upload`, {
    datasetName: source.metadata.name,
    type: 'local',
    columns,
    rows,
  });

  // 4. 更新 EditorState (使用返回的 datasetId)
  updateState(draft => {
    draft.datasets.push({
      id: result.datasetId,
      type: 'local',
      metadata: {
        name: source.metadata.name,
        _rowCount: result.rowCount,
        _lazy: false, // 刚上传的，前端已有完整数据
      },
      data: source.data, // 前端解析的数据直接使用，无需再从后端拉
      columns: source.columns,
    });
  });
}
```

> **注意**: 上传后前端内存中已有完整数据，`_lazy: false` 直接可用于渲染，不需要再次从后端加载。后续**再次打开**项目时才会走懒加载路径。

### 6.4 图标库管理弹窗 (IconLibraryModal)

```
IconLibraryModal/
├── index.tsx              # 弹窗主组件
│   ├── antd Modal (宽度 900px)
│   ├── 左右分栏布局 (分类列表 240px | 图标网格 flex)
│   ├── 底部状态栏 (共 X 个分类, Y 个图标)
│   └── 状态管理: useReducer 本地状态
├── CategoryPanel.tsx       # 左侧分类列表
│   ├── 可拖拽排序 (@hello-pangea/dnd)
│   ├── 选中高亮
│   ├── 新增分类 (Modal.confirm 输入框)
│   ├── 重命名分类 (内联编辑)
│   └── 删除分类 (Popconfirm)
├── IconGrid.tsx            # 右侧图标网格
│   ├── 网格布局 (CSS Grid, 6 列)
│   ├── 支持拖拽排序
│   ├── **支持拖拽到左侧分类列表实现移动分类**
│   ├── 多选支持 (批量删除)
│   └── 工具栏: [上传图标] [批量删除]
└── IconCard.tsx            # 单个图标卡片
    ├── 图标预览 (img 标签, 80x80)
    ├── 原始文件名
    ├── 库号 / 代号 (可编辑)
    ├── 编辑按钮 → 展开库号/代号编辑
    ├── 删除按钮 → Popconfirm
    └── **draggable 属性，可拖到左侧目标分类上**
```

> **图标移动分类**: 选中图标（或拖动图标卡片），拖放到左侧分类列表的目标分类名上 → PUT /api/icons/{id} 更新 categoryId → 两个分类下都有相同图标是合理的（不强制唯一）

#### 组件状态

```typescript
type IconLibraryState = {
  categories: IconCategory[];     // 全部分类
  selectedCategoryId: string | null;
  icons: IconItem[];              // 当前选中分类的图标
  loading: boolean;
  uploadProgress: number | null;  // 批量上传进度
};
```

#### 数据流

```
IconLibraryModal 打开
  │
  ├─ useEffect → GET /api/icons/categories → 加载分类列表
  │
  └─ 用户点击分类 → GET /api/icons?categoryId=xxx → 加载图标列表

用户操作:
  上传图标 → POST /api/icons/upload (multipart) → 刷新图标列表
  编辑库号/代号 → PUT /api/icons/{id} → 更新本地状态
  删除图标 → DELETE /api/icons/{id} → 刷新图标列表
  新增分类 → POST /api/icons/categories → 刷新分类列表
  删除分类 → DELETE /api/icons/categories/{id} → 刷新
  排序 → PUT /api/icons/categories/reorder 或 /api/icons/reorder
```

### 6.5 瓦片配置弹窗 (TileConfigModal)

```typescript
// 组件结构简单，单个文件即可
function TileConfigModal({ open, onClose }) {
  // 本地状态
  const [tileUrl, setTileUrl] = useState('');
  const [tileName, setTileName] = useState('');
  const [minZoom, setMinZoom] = useState(0);
  const [maxZoom, setMaxZoom] = useState(18);

  // 打开时加载现有配置
  useEffect(() => {
    if (open) {
      getTileConfig().then(config => {
        if (config) {
          setTileUrl(config.tileUrl);
          setTileName(config.tileName);
          setMinZoom(config.minZoom);
          setMaxZoom(config.maxZoom);
        }
      });
    }
  }, [open]);

  // 保存
  const handleSave = async () => {
    await saveTileConfig({ tileUrl, tileName, minZoom, maxZoom });
    message.success('瓦片配置已保存');
    onClose();
  };

  return (
    <Modal open={open} onCancel={onClose} onOk={handleSave}>
      <Form>
        <Form.Item label="底图名称"><Input value={tileName} /></Form.Item>
        <Form.Item label="瓦片URL"><Input value={tileUrl} /></Form.Item>
        <Form.Item label="最小缩放"><InputNumber value={minZoom} /></Form.Item>
        <Form.Item label="最大缩放"><InputNumber value={maxZoom} /></Form.Item>
      </Form>
    </Modal>
  );
}
```

### 6.6 项目列表页按钮布局

```
┌─────────────────────────────────────────────────────────┐
│  L7VP                                         [图标库]  │
│                                               [瓦片配置]│
│  ┌─────────────┐  ┌─────────────┐  [+ 新建项目]         │
│  │  项目卡片1   │  │  项目卡片2   │                      │
│  └─────────────┘  └─────────────┘                      │
└─────────────────────────────────────────────────────────┘
```

按钮组放在页面右上角，与"新建项目"按钮同行。

**新建项目**: 从 `window.open('/new')` 改为 React Router `navigate('/new')`，**同一浏览器标签页**内跳转，不弹窗也不新开 tab。路由 `/new` 保留，仍是独立页面。

### 6.7 iframe 兼容改造

```typescript
// 改造前
// Project/index.tsx:68:
//   window.open(history.createHref('/new'));  ← 新开 tab

// 改造后
// Project/index.tsx:
//   navigate('/new');  ← 同一 tab 内跳转

// 改造清单:
// ✅ Preview → 保留 window.open (新 tab，业务需要独立展示)
// 🔧 New Project → window.open → navigate() 同 tab 跳转
// 🔧 Export → 确保同 tab 下载
// 🔧 全局排查其他 window.open 调用 → 替换为 navigate() 或 Modal
//    已知位置: Project/index.tsx:68
```

---

## 7. API 设计

### 7.1 完整 API 清单

#### 项目管理

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `GET` | `/api/projects` | — | `Project[]` | 列表（不含 applicationConfig） |
| `POST` | `/api/projects` | `Project` (含 applicationConfig) | `Project` | 创建项目（首次保存含初始配置） |
| `GET` | `/api/projects/{id}` | — | `Application` | **组装后**的完整 JSON，local dataset data=[]，带 `_lazy` 和 `_rowCount` |
| `PUT` | `/api/projects/{id}` | `Application` (data 已排除) | `void` | 更新元数据 + layers + widgets，不操作行数据 |
| `DELETE` | `/api/projects/{id}` | — | `void` | 级联删除所有子表数据 |
| `PUT` | `/api/projects/{id}/thumbnail` | `{ thumbnailUrl }` | `void` | 不变 |

#### 数据集 & 数据行

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `POST` | `/api/projects/{id}/datasets/upload` | `{ datasetName, type, columns, rows }` | `{ datasetId, rowCount }` | 创建数据集 + 列定义 + 批量写入行数据（单事务） |
| `GET` | `/api/projects/{id}/datasets/{dsId}/rows` | Query: `?page=0&size=500` | `{ rows, total, page, size, columns }` | 分页查询数据行 |
| `DELETE` | `/api/projects/{id}/datasets/{dsId}` | — | `void` | 删除数据集及其列定义和数据行 |

#### 图标管理

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `GET` | `/api/icons/categories` | — | `IconCategory[]` | 全部分类（含排序） |
| `POST` | `/api/icons/categories` | `{ name }` | `IconCategory` | 新增分类 |
| `PUT` | `/api/icons/categories/{id}` | `{ name }` | `void` | 重命名分类 |
| `DELETE` | `/api/icons/categories/{id}` | — | `void` | 删除分类 + 级联删除图标和文件 |
| `PUT` | `/api/icons/categories/reorder` | `[{ id, sortOrder }]` | `void` | 批量更新排序 |
| `GET` | `/api/icons` | Query: `?categoryId={id}` | `IconItem[]` | 某分类下图标列表 |
| `POST` | `/api/icons/upload` | FormData: categoryId + file(s) | `IconItem[]` | 上传（支持多文件），初始库号/代为空 |
| `PUT` | `/api/icons/{id}` | `{ libraryCode, codeName }` | `void` | 编辑库号/代号 |
| `DELETE` | `/api/icons/{id}` | — | `void` | 删除图标 + 文件 |
| `PUT` | `/api/icons/reorder` | `[{ id, sortOrder }]` | `void` | 批量更新排序 |
| `GET` | `/api/icons/lookup` | Query: `?lib=通信&code=antenna` | `{ url }` | 按库号+代号查找图标 URL |

> **兼容**: 现有 `GET /api/icons`（无参数时返回全部）保留，改为从数据库组装

#### 瓦片配置

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `GET` | `/api/tile-config` | — | `TileConfig \| null` | 获取全局瓦片配置（公开，无需认证） |
| `PUT` | `/api/tile-config` | `TileConfig` | `void` | 更新瓦片配置 |

#### 缩略图（不变）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/thumbnails/upload` | 不变 |
| `GET` | `/api/health` | 不变 |

### 7.2 请求/响应示例

#### POST /api/projects/{id}/datasets/upload

```json
// Request
{
  "datasetName": "城市人口数据",
  "type": "local",
  "columns": [
    { "name": "城市", "type": "string", "index": 0 },
    { "name": "人口", "type": "number", "index": 1 },
    { "name": "GDP", "type": "number", "index": 2 }
  ],
  "rows": [
    ["北京", 21540000, 41610],
    ["上海", 24870000, 44652],
    ["深圳", 17560000, 32388]
  ]
}

// Response
{
  "datasetId": "ds_a1b2c3d4",
  "rowCount": 3
}
```

#### GET /api/projects/{id}/datasets/{dsId}/rows?page=0&size=500

```json
// Response
{
  "rows": [
    ["北京", 21540000, 41610],
    ["上海", 24870000, 44652]
  ],
  "total": 10000,
  "page": 0,
  "size": 500,
  "columns": [
    { "name": "城市", "type": "string", "index": 0 },
    { "name": "人口", "type": "number", "index": 1 },
    { "name": "GDP", "type": "number", "index": 2 }
  ]
}
```

#### POST /api/icons/upload (multipart/form-data)

```
FormData:
  categoryId: "cat_001"
  files: [file1.svg, file2.png, file3.svg]

Response:
[
  {
    "iconId": "icon_001",
    "categoryId": "cat_001",
    "libraryCode": "",       // 空，等用户在列表中编辑
    "codeName": "",
    "originalName": "antenna.svg",
    "url": "/icons/cat_001/a1b2c3d4.svg",
    "fileType": "svg",
    "fileSize": 2048,
    "sortOrder": 0
  },
  ...
]
```

---

## 8. 数据库设计概要

> 详细字段定义见 [proposal.md 第 2 章](./proposal.md#2-需求一数据库重新设计)，此处仅列出表清单和关系。

### 8.1 表清单

| # | 表名 | Schema | 说明 | 行量级 |
|---|------|--------|------|--------|
| 1 | `PROJECTS` | `DIG_GEO` | 项目主表 | 数十~数百 |
| 2 | `DATASETS` | `DIG_GEO` | 数据集元数据 | 每项目 1~10 |
| 3 | `DATASET_COLUMNS` | `DIG_GEO` | 列定义 | 每数据集 2~50 |
| 4 | `DATASET_ROWS` | `DIG_GEO` | 数据行 | 每数据集 0~100000+ |
| 5 | `LAYERS` | `DIG_GEO` | 图层配置 | 每项目 1~20 |
| 6 | `WIDGETS` | `DIG_GEO` | 组件配置 | 每项目 5~30 |
| 7 | `TILE_CONFIG` | `DIG_GEO` | 全局瓦片配置 | 1 条 |
| 8 | `ICON_CATEGORIES` | `DIG_GEO` | 图标分类 | 10~20 |
| 9 | `ICONS` | `DIG_GEO` | 图标元数据 | 100~500 |

### 8.2 ER 关系

```
PROJECTS ──1:N──→ DATASETS ──1:N──→ DATASET_COLUMNS
    │                │
    │                └──1:N──→ DATASET_ROWS
    │
    ├──1:N──→ LAYERS ────→ DATASETS (可选 FK)
    │
    └──1:N──→ WIDGETS ──→ WIDGETS (自引用 container_id)

ICON_CATEGORIES ──1:N──→ ICONS

TILE_CONFIG (独立全局表)
```

### 8.3 关键索引

| 表 | 索引 | 用途 |
|---|------|------|
| `DATASET_ROWS` | `(dataset_id, row_index)` | 分页查询 |
| `DATASETS` | `(project_id)` | 按项目查数据集 |
| `LAYERS` | `(project_id, layer_order)` | 按项目查图层排序 |
| `WIDGETS` | `(project_id, widget_order)` | 按项目查组件排序 |
| `ICONS` | `(category_id, sort_order)` | 分类内排序 |
| `ICONS` | `(library_code, code_name)` UNIQUE | 库号+代号查找 |
| `PROJECTS` | `(project_name)` | 按名称搜索 |

---

## 9. 关键时序图

### 9.1 Excel 上传 10000 行数据

```
用户          UploadDataset        API             后端 DB
 │                │                 │                 │
 │  拖拽Excel     │                 │                 │
 │──────────────→│                 │                 │
 │                │                 │                 │
 │                │ xlsx解析 (前端)  │                 │
 │                │ 2-3秒, CPU密集   │                 │
 │                │                 │                 │
 │                │ POST /datasets/  │                 │
 │                │ upload           │                 │
 │                │────────────────→│                 │
 │                │                 │                 │
 │                │                 │ BEGIN TRANS     │
 │                │                 │ INSERT DATASETS │
 │                │                 │───────────────→│
 │                │                 │ INSERT COLUMNS  │
 │                │                 │ (batch)         │
 │                │                 │───────────────→│
 │                │                 │ INSERT ROWS     │
 │                │                 │ (JDBC batch 500)│
 │                │                 │ loop 20 次      │
 │                │                 │───────────────→│
 │                │                 │ COMMIT          │
 │                │                 │                 │
 │                │ {datasetId,     │                 │
 │                │  rowCount:10000}│                 │
 │                │←────────────────│                 │
 │                │                 │                 │
 │  显示"上传成功" │                 │                 │
 │←──────────────│                 │                 │
 │                │                 │                 │
 │  (300ms后)     │                 │                 │
 │  自动保存      │ PUT /projects/id│                 │
 │  (元数据,无行)  │────────────────→│                 │
```

### 9.2 打开含大数据集的项目

```
用户          Builder         API              后端 DB
 │                │              │                 │
 │  打开项目      │              │                 │
 │──────────────→│              │                 │
 │                │              │                 │
 │                │ GET /projects/{id}             │
 │                │─────────────→│                 │
 │                │              │ assemble()      │
 │                │              │ 查5表, 不含rows  │
 │                │              │───────────────→│
 │                │              │←───────────────│
 │                │ Application  │                 │
 │                │ (data=[],     │                 │
 │                │  _lazy=true)  │                 │
 │                │←─────────────│                 │
 │                │              │                 │
 │  界面渲染      │              │                 │
 │  地图/面板可见  │              │                 │
 │←──────────────│              │                 │
 │                │              │                 │
 │                │ (异步加载数据) │                 │
 │                │              │                 │
 │                │ GET /projects/{id}/datasets/   │
 │                │   {dsId}/rows?page=0&size=500  │
 │                │─────────────→│                 │
 │                │              │ SELECT          │
 │                │              │───────────────→│
 │                │              │←───────────────│
 │                │ {rows,total} │                 │
 │                │←─────────────│                 │
 │                │              │                 │
 │                │ (如果total>500, 循环拉取剩余页)  │
 │                │ ...直到拉完   │                 │
 │                │              │                 │
 │  注入数据       │              │                 │
 │  Layer渲染     │              │                 │
 │  图标/气泡出现  │              │                 │
 │←──────────────│              │                 │
```

---

## 10. 配置与部署

### 10.1 application.properties 新增/变更

```properties
# 数据库 (不变)
spring.datasource.url=jdbc:dm://localhost:5236/DIG_GEO
spring.datasource.username=SYSDBA
spring.datasource.password=SYSDBA

# SQL 初始化
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema.sql

# 图标存储路径
l7vp.icons.path=/opt/l7vp/icons
# 开发环境可覆盖: ../website/public/icons

# 图标访问 URL 前缀
l7vp.icons.url-prefix=/icons

# 缩略图存储路径 (不变)
l7vp.thumbnails.path=/opt/l7vp/thumbnails

# 上传文件大小限制
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=50MB
```

### 10.2 schema.sql 结构

```sql
-- 新 schema
CREATE SCHEMA IF NOT EXISTS DIG_GEO;

-- 项目主表
CREATE TABLE DIG_GEO.PROJECTS (...);

-- 数据集相关
CREATE TABLE DIG_GEO.DATASETS (...);
CREATE TABLE DIG_GEO.DATASET_COLUMNS (...);
CREATE TABLE DIG_GEO.DATASET_ROWS (...);

-- 图层与组件
CREATE TABLE DIG_GEO.LAYERS (...);
CREATE TABLE DIG_GEO.WIDGETS (...);

-- 图标库
CREATE TABLE DIG_GEO.ICON_CATEGORIES (...);
CREATE TABLE DIG_GEO.ICONS (...);

-- 瓦片配置
CREATE TABLE DIG_GEO.TILE_CONFIG (...);

-- 索引
CREATE INDEX ...
```

### 10.3 部署注意事项

| 项目 | 说明 |
|------|------|
| 数据库 | 首次部署执行 `schema.sql` 初始化表结构，旧 `L7VP_PROJECTS` schema 可保留或手动删除 |
| 图标迁移 | 现有 `website/public/icons/` 下的图标需迁移到新位置，并在 ICON_CATEGORIES 和 ICONS 表中录入元数据；可编写一次性迁移脚本 |
| config.js | `window.L7VP_CONFIG.tileLayerUrl` 降级为兜底，数据库优先 |
| 文件权限 | 确保 Java 进程对 `l7vp.icons.path` 有读写权限 |

### 10.4 旧代码归档

| 目录/文件 | 处理方式 |
|------|------|
| `server/` (Node.js Express 后端) | 移动到 `archive/legacy-node-server/` |
| `server/sql/init.sql` (旧 DDL) | 移动到 `archive/` 或直接删除（新 schema.sql 在 java-server 中） |
| `deploy/backend/config/schema.sql` | 同步更新为新 schema（如 deploy 目录仍在使用） |

> 旧代码统一移到 `archive/` 目录下，不再维护。如后续确认无用可整体删除。

---

## 11. 确认事项汇总

| # | 问题 | 状态 |
|---|------|------|
| 1 | ApplicationAssembler 放在后端 Service 层（Java），前端尽量无感 | ✅ 确认 |
| 2 | 数据行不分片，一次性批量 POST（10000 行约 2-5MB，2-3 秒可接受） | ✅ 确认 |
| 3 | 打开项目时数据行**全部加载**后再渲染，不截断 | ✅ 确认 |
| 4 | 图标拖拽移动到其他分类（拖到左侧目标分类），**现在就做** | ✅ 确认 |
| 5 | 新建项目：同 tab 内跳转（`navigate('/new')` 替换 `window.open('/new')`），路由 `/new` 保留 | ✅ 确认 |
| 6 | 旧代码（`server/` 等）移到 `archive/` 目录归档 | ✅ 确认 |

---

> **下一步**: 进入详细设计阶段，细化每个模块的类图、接口签名和前端组件 Props/State 定义。
