# L7VP 功能增强需求文档 (Proposal V5)

> **版本**: v5.0
> **日期**: 2026-07-17
> **状态**: 已确认
> **关联文档**: [proposal-v4.md](./proposal-v4.md)

---

## 目录

- [1. 需求一：EPSG:4326 瓦片支持](#1-需求一epsg4326-瓦片支持)
- [2. 需求二：共享链接 / 嵌入发布](#2-需求二共享链接--嵌入发布)
- [3. 需求三：GeoCircleLayer 注册修复 + 布尔减运算](#3-需求三geocirclelayer-注册修复--布尔减运算)
- [4. 需求四：筛选器配置保存修复](#4-需求四筛选器配置保存修复)
- [5. 实施计划](#5-实施计划)

---

## 1. 需求一：EPSG:4326 瓦片支持

### 1.1 背景

内网瓦片有两类坐标系：
- **EPSG:3857**（Web 墨卡托投影）：Y 轴从上往下编号（XYZ / Slippy Map）
- **EPSG:4326**（地理坐标投影）：Y 轴从下往上编号（TMS）

两者 Y 轴编号方向相反，需要在加载瓦片时区分处理。

### 1.2 功能需求

| 配置项 | 类型 | 选项 | 说明 |
|--------|------|------|------|
| CRS | Select | `EPSG:3857` / `EPSG:4326` | 坐标系类型 |
| TILE_SCHEME | Select | `XYZ` / `TMS` | 瓦片编号方式，控制 Y 轴方向 |

两个字段**独立配置**，不绑定。

### 1.3 涉及位置

1. **瓦片数据集配置**（TilesetsDataset / AddDataset 弹窗中）
2. **默认瓦片配置页**（TileConfigModal）
3. **瓦片选择弹窗**（TileSelectorModal）：显示 CRS + TILE_SCHEME 信息

### 1.4 数据库变更

TILE_CONFIG 表新增字段：

```sql
-- TILE_SCHEME: 瓦片编号方式，XYZ 或 TMS
ALTER TABLE DIG_GEO.TILE_CONFIG ADD TILE_SCHEME VARCHAR(10) DEFAULT 'XYZ';
```

（CRS 字段用户已手动添加）

### 1.5 渲染逻辑

L7 加载瓦片时，根据 CRS + TILE_SCHEME 构建不同的 tile URL template：
- **EPSG:3857 + XYZ**：标准 `{z}/{x}/{y}` 格式
- **EPSG:4326 + TMS**：需要翻转 Y 轴：`{z}/{x}/{reverseY}`，其中 `reverseY = 2^z - 1 - y`

### 1.6 涉及文件

| 文件 | 操作 |
|------|------|
| `java-server/.../model/TileConfig.java` | 新增 `tileScheme` 字段 |
| `java-server/.../repository/TileConfigRepository.java` | SQL 增改包含 TILE_SCHEME |
| `java-server/.../service/TileConfigService.java` | CRUD 支持新字段 |
| `website/src/components/TileConfigModal/index.tsx` | 添加 CRS + TILE_SCHEME 下拉框 |
| `website/src/components/TileSelectorModal/index.tsx` | 显示 CRS + TILE_SCHEME 信息 |
| `website/src/pages/Builder/index.tsx` | 创建瓦片图层时传递 CRS + TILE_SCHEME |
| `packages/li-editor/src/widgets/TilesetsDataset/` | 添加 CRS + TILE_SCHEME 配置 |
| `packages/li-core-assets/src/layers/TileLayer/` | 瓦片渲染适配 TMS Y 轴翻转 |

---

## 2. 需求二：共享链接 / 嵌入发布

### 2.1 背景

需要一个功能生成嵌入链接，用于 DataEase 等可视化大屏中嵌入地图。现有"预览"功能存在问题（看不到图层），暂不修复，新建"共享"功能。

### 2.2 功能需求

**触发入口**：编辑器工具栏新增"共享"按钮。

**弹窗内容**：
- 共享链接：`{origin}/share/{projectId}`
- 快速复制按钮（一键复制链接）
- 预览按钮（新标签页打开链接）

**共享页面** (`/share/{projectId}`)：
- 纯地图视图，**没有任何编辑器 UI**
- 保留：地图画布、图层渲染、信息窗（Popup）、属性面板（PropertiesPanel）、图例、筛选器、搜索定位等全部运行时交互组件
- 移除：左侧配置栏、顶部工具栏、数据集面板、图层面板、组件面板等所有编辑功能

### 2.3 技术方案

#### 2.3.1 前端路由

在 Umi 路由配置中新增 `/share/:id` 路由，复用 Builder 页面的 RuntimeApp 组件，但不渲染编辑器侧边栏。

#### 2.3.2 共享弹窗组件

新建 `ShareModal` 组件：
```
Props:
  - projectId: string
  - visible: boolean
  - onClose: () => void

Content:
  - 链接文本框（只读）
  - 复制按钮（navigator.clipboard.writeText）
  - 预览按钮（window.open）
```

#### 2.3.3 共享页面实现

方案：复用 `RuntimeApp`（只渲染地图 + 图层 + 交互组件），不渲染 `EditorLayout`。项目数据通过 API 加载。

数据加载流程：
1. 页面加载时，根据 URL 中的 `projectId` 调用 `GET /api/projects/{id}` 获取项目配置
2. 从项目配置中提取 `layers` + `widgets`
3. 仅实例化 `type === 'Auto'` 的组件（如 FilterControl、SearchControl、LegendWidget、PropertiesPanel 等地图上的交互组件）
4. 不渲染任何 `type !== 'Auto'` 的编辑器组件

#### 2.3.4 涉及文件

| 文件 | 操作 |
|------|------|
| `website/config/routes.ts` | 新增 `/share/:id` 路由 |
| `website/src/pages/Share/index.tsx` | 新建共享页面 |
| `website/src/components/ShareModal/index.tsx` | 新建共享弹窗 |
| `website/src/pages/Builder/index.tsx` | 工具栏添加"共享"按钮 |
| `website/src/pages/Builder/editor-widgets.ts` | 注册 ShareModal 或共享按钮 |

---

## 3. 需求三：GeoCircleLayer 注册修复 + 布尔减运算

### 3.1 背景

GeoCircleLayer 前期已完成代码编写并通过编译，但在地图编辑器的"可视化类型"下拉框中不可见。

### 3.2 排查计划

1. 检查 `li-core-assets/src/layers/index.ts` 是否正确导出
2. 检查 `li-core-assets` 的 `dist` 构建产物是否包含 GeoCircleLayer
3. 检查 `website` 的资产加载链：`asset.ts` → `LICoreAssets` → 图层列表
4. 与正常工作的图层（如 BubbleLayer）对比注册流程的每一步
5. 检查 `implementLayer` 注册时的 `metadata.type` 和图层分类逻辑

### 3.3 布尔减运算（火力圈）

**功能**：GeoCircleLayer 配置栏新增开关"裁剪中国大陆"，开启后每个地理圆减去中国大陆陆地区域。

**技术方案**：
- 引入 Turf.js `difference` 运算
- 中国陆地区域边界使用项目已有的 `china_boundary.json`（或合并 `china_province.json`）
- 为每个圆生成的多边形，调用 `turf.difference(circlePolygon, chinaPolygon)`
- 开关为图层级：整个图层的所有圆统一行为

**注意事项**：
- `turf.difference` 对复杂多边形（如中国边界）性能开销大，需缓存中国边界 GeoJSON
- 若圆完全在海外（无交集），`difference` 返回原圆
- 若圆完全在大陆内部（结果为 null），该圆不渲染

### 3.4 涉及文件

| 文件 | 操作 |
|------|------|
| `packages/li-core-assets/src/layers/GeoCircleLayer/Component.tsx` | 新增布尔减运算逻辑 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/register-form/schema.ts` | 新增"裁剪中国大陆"开关 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/register-form/index.ts` | visConfig 增加 `clipChina` 字段 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/index.tsx` | defaultVisConfig 增加 `clipChina: false` |
| `packages/li-core-assets/src/layers/index.ts` | 确认导出正确 |
| 排查注册链相关文件 | 修复注册问题 |

---

## 4. 需求四：筛选器配置保存修复

### 4.1 背景

筛选器（FilterControl）配置的筛选条件在保存后丢失，每次复现。筛选器组件本身存在，但内部的筛选条件（字段、运算符、默认值）被清空。

### 4.2 排查计划

1. 检查 FilterControl 的 `defaultFilters` 在 `ApplicationAssembler.disassemble()` 中的序列化路径
2. 检查 `ApplicationAssembler.assemble()` 中 `defaultFilters` 的反序列化/还原
3. 检查前端保存时的 payload 是否包含 `defaultFilters`
4. 检查数据库 WIDGETS 表的 `PROPERTIES` 字段是否完整存储了筛选器配置

### 4.3 可能根因

`defaultFilters` 的类型 `FilterConfigType[]` 中可能包含不可序列化的字段（如函数回调、React 组件引用），导致 JSON 序列化时丢失。

### 4.4 涉及文件

| 文件 | 操作 |
|------|------|
| `java-server/.../service/ApplicationAssembler.java` | 检查 disassemble/assemble 对 FilterControl 的序列化 |
| `packages/li-analysis-assets/src/widgets/FilterControl/registerForm.ts` | 检查 Properties 类型定义 |
| `packages/li-analysis-assets/src/widgets/FilterControl/Component/` | 检查 FilterConfigType 的数据结构 |

---

## 5. 实施计划

| 序号 | 需求 | 优先级 | 预计工作量 | 涉及文件数 |
|------|------|--------|-----------|-----------|
| 1 | EPSG:4326 瓦片支持 | 高 | 中 | ~8 |
| 2 | 共享链接 / 嵌入发布 | 高 | 大 | ~5 |
| 3 | GeoCircleLayer 注册修复 + 布尔减 | 中 | 中 | ~6 |
| 4 | 筛选器配置保存修复 | 高 | 小 | ~3 |

### 建议实施顺序

1. **需求四**（筛选器保存 bug）—— 先修 bug，影响最小
2. **需求一**（EPSG:4326 瓦片）—— 独立功能，不依赖其他
3. **需求三**（GeoCircleLayer）—— 排查 + 增强
4. **需求二**（共享链接）—— 新页面，工作量最大
