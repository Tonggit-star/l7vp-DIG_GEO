# L7VP 功能修复与增强需求文档 (Proposal V3)

> **版本**: v3.0
> **日期**: 2026-07-14
> **状态**: 已确认
> **关联文档**: [proposal.md](./proposal.md)（V1 数据库/图标库/瓦片/iframe）、[proposal-v2.md](./proposal-v2.md)

---

## 目录

- [1. 背景](#1-背景)
- [2. 需求一：恢复图标映射功能（iconField + iconImgScale）](#2-需求一恢复图标映射功能iconfield--iconimgscale)
- [3. 需求二：修复默认瓦片层级配置](#3-需求二修复默认瓦片层级配置)
- [4. 需求三：移除图标外圈半透明圆](#4-需求三移除图标外圈半透明圆)
- [5. 需求四：关闭 DMS 坐标转换调试日志](#5-需求四关闭-dms-坐标转换调试日志)
- [6. 需求五：常用瓦片多配置管理](#6-需求五常用瓦片多配置管理)
- [7. 需求六：数据空值防御性处理](#7-需求六数据空值防御性处理)
- [8. 需求七：经纬度自动匹配增加拼音前缀 jd/wd](#8-需求七经纬度自动匹配增加拼音前缀-jdwd)
- [9. 需求八：新增地图搜索定位控件](#9-需求八新增地图搜索定位控件)
- [10. 实施计划](#10-实施计划)

---

## 1. 背景

经过前期多轮调试，发现以下问题需要修复和完善：

| 问题 | 影响范围 | 优先级 |
|------|---------|--------|
| 图标映射功能（iconField/iconImgScale）被禁用，下拉框无数据 | 图标图层 | 🔴 P0 |
| 默认瓦片层级配置保存/读取异常 | 新建 project | 🔴 P0 |
| 图标外圈渲染半透明圆遮挡长方形图标 | 图标图层 | 🟡 P1 |
| DMS 坐标转换仍有调试日志输出 | Console 噪音 | 🟢 P2 |
| 常用瓦片仅支持单条，无法管理多个 | 瓦片配置 | 🟡 P1 |
| 数据空值导致 toString/filter 运行时异常 | 全局稳定性 | 🔴 P0 |
| 经纬度字段仅匹配中文名，不支持拼音前缀 | 图层创建 | 🟡 P1 |
| 缺少搜索定位控件 | 功能缺失 | 🟡 P1 |

---

## 2. 需求一：恢复图标映射功能（iconField + iconImgScale）

### 2.1 现状

`IconImageLayerStyle/schema.tsx` 中 `iconField` 字段（类别字段）和 `iconImgScale`（图标映射）组件存在但功能不完整：
- `iconField` 下拉框可以选中字段，但选中后 `iconImgScale` 的 `domain`（待映射的唯一值列表）为空
- `helper.ts` 中 `flatToConfig` 和 `configToFlat` 未处理这两个字段

### 2.2 期望行为

1. 用户选择 `iconField`（类别字段）后，**自动从数据集中读取该字段的所有唯一值（去重）**，填充到 `iconImgScale` 的 `domain` 中
2. 用户可为每个唯一值指定对应的图标（IconScaleSelector 组件已有 UI）
3. 保存后，该映射关系持久化到图层配置中
4. 渲染时，根据字段值匹配对应图标

### 2.3 技术方案

**后端（Java）：**
- 新增 API：`GET /api/projects/{projectId}/datasets/{datasetId}/fields/{fieldName}/unique`，返回去重后的字段值列表
- 排除 null/空字符串
- 限制返回数量（最多 100 个，超出截断并标记 `truncated: true`）

**前端（li-p2）：**
- `IconImageLayerStyle/schema.tsx`：为 `iconImgScale` 添加 `x-reactions`，当 `iconField` 变化时调用 API 获取 domain
- `helper.ts`：在 `flatToConfig` 中处理 `iconField` 为 `iconField`，处理 `iconImgScale` 转换为 `iconAtlas` 和 `icon`；在 `configToFlat` 中反向转换

**前端（li-core-assets）：**
- `IconLayer` 渲染逻辑：根据 `iconField` 和 `iconImgScale`（domain→range 映射），为每个 feature 动态设置图标 URL

---

## 3. 需求二：修复默认瓦片层级配置

### 3.1 现状

- 在 TileConfig 弹窗中修改了 minZoom/maxZoom（如 0-24），再次新建 project 时层级配置未生效
- 瓦片底图超出配置层级后黑屏（不再加载瓦片）

### 3.2 期望行为

1. 新建 project 时，瓦片图层的 `minZoom`/`maxZoom` 使用数据库中存储的默认瓦片配置
2. 超出瓦片实际层级范围时，**不黑屏**，而是显示最后一级的瓦片（模糊但可用）

### 3.3 技术方案

**后端：**
- 检查 `TileConfigController` 的保存/读取逻辑，确保 `minZoom`/`maxZoom` 字段正确存储到 DB 并返回
- 新增 `overzoom` 配置项（超出层级时是否继续显示最后一级瓦片）

**前端：**
- `creatApplication()` 创建项目时，瓦片图层的 `visConfig.minZoom/maxZoom` 从数据库配置读取（当前已从 DB 读取 URL/name，但漏了层级字段）
- 图层配置中增加 `maxZoom: 24` 的上限

---

## 4. 需求三：移除图标外圈半透明圆

### 4.1 现状

图标在渲染时外侧有一个半透明的圆形遮罩，当使用长方形图标时，四个角被这个圆形盖住。

### 4.2 期望行为

去除或隐藏这个圆形遮罩，让图标以其原始形状渲染。

### 4.3 技术方案

排查 L7 IconLayer 渲染源码中的 marker SVG 默认样式：
- 位置：`@antv/l7-component/es/marker.js` 的 `init()` 方法（第 416-449 行）
- 默认 marker 创建了一个 SVG 圆形路径（`path` 的 `d` 属性为圆形 `M512 490.666667C453.12 490.666667...Z`）
- 如果用户提供了自定义 `element`（图标 URL），不应该再叠加默认的圆形 SVG

**修改方式**：在 IconLayer 渲染时，如果已设置图标 URL，则不创建默认的圆形 marker 背景，或将 marker 背景改为透明。

实际实现：在 `l7-component/es/marker.js` 的 `init()` 中，如果 `element` 已设置，跳过默认 SVG 的创建；或者修改 IconLayer 的渲染逻辑，直接使用自定义 DOM 元素替代默认 marker。

---

## 5. 需求四：关闭 DMS 坐标转换调试日志

### 5.1 现状

度分秒坐标转换功能已稳定，但 Console 中仍有调试日志输出。

### 5.2 期望行为

移除或注释掉相关 `console.log` 输出。

### 5.3 技术方案

搜索代码中的 DMS 转换相关日志（关键词：`DMS`、`dms`、`度分秒`、`convert`），移除或改为 `process.env.NODE_ENV === 'development'` 条件输出。

---

## 6. 需求五：常用瓦片多配置管理

### 6.1 现状

"常用瓦片"弹窗仅支持维护单个瓦片配置（URL + 名称）。

### 6.2 期望行为

1. 弹窗改为左右布局：**左侧菜单栏**（瓦片列表）+ **右侧详情面板**
2. 支持**新增/删除**多个瓦片配置
3. 新建 project 时，在进入 Builder 页面前**弹出选择窗**，展示所有已配置的瓦片（支持多选），选中的瓦片自动创建对应的数据集和图层

### 6.3 技术方案

**数据库（Java）：**
- 新增表 `TILE_CONFIGS`：`ID, NAME, TILE_URL, MIN_ZOOM, MAX_ZOOM, CREATE_TIME, UPDATE_TIME`
- CRUD API：`GET/POST/PUT/DELETE /api/tile-configs`
- 向后兼容：保留原有单条配置的 API

**前端（website）：**
- 重写 TileConfig 弹窗为左右布局
- 新增 `TileSelector` 弹窗组件，在 `New.tsx` 创建项目后弹出
- 多选瓦片后，为每个选中的瓦片自动生成 `raster-tile` dataset + `TileLayer`

---

## 7. 需求六：数据空值防御性处理

### 7.1 现状

数据集中存在 null/undefined 值时，信息框、属性面板、标签字段等功能出现运行时异常：
- `Cannot read properties of null (reading 'toString')`
- `Cannot read properties of undefined (reading 'filter')`

### 7.2 期望行为

所有读取数据字段值的地方，增加空值防御：`null`/`undefined` 转为空字符串 `''` 或跳过。

### 7.3 技术方案

**影响范围**，对以下位置增加空值守卫 `value ?? ''` 或 `value != null ? String(value) : ''`：
- `LayerPopup/Component.tsx`（信息框）- `feature[field]` 取值处
- `PropertiesPanel/Component.tsx`（属性面板）- `feature[key]` 渲染处
- `ChinaAdminLayer/helper.ts` - 标签字段渲染
- `IconLayer` 及其他图层的 label 渲染逻辑
- `getElementTypePortal`（larkmap）- 传入 feature 前做数据清洗

通用工具函数：
```typescript
// 安全获取字段值
export const safeValue = (val: any): string => {
  if (val === null || val === undefined) return '';
  if (typeof val === 'object') return JSON.stringify(val);
  return String(val);
};
```

---

## 8. 需求七：经纬度自动匹配增加拼音前缀 jd/wd

### 8.1 现状

新建图层时，系统自动检测数据集字段名包含"经度"/"纬度"的字段，并自动填入经纬度映射下拉框。但不支持拼音前缀。

### 8.2 期望行为

增加对 `jd`（经度）和 `wd`（纬度）字段名称的检测。

### 8.3 技术方案

在 `li-core-assets` 各图层的 `coordinate-schema.ts` 中，字段自动匹配逻辑增加：

```typescript
const LONGITUDE_PATTERNS = ['经度', 'lng', 'longitude', 'lon', 'jd'];
const LATITUDE_PATTERNS = ['纬度', 'lat', 'latitude', 'wd'];
```

匹配规则：字段名**包含**以上任一关键词（不区分大小写），且优先匹配精确度更高的（中文 > 英文 > 拼音）。

---

## 9. 需求八：新增地图搜索定位控件

### 9.1 现状

无搜索定位功能，用户无法快速搜索某个要素并跳转。

### 9.2 期望行为

新增 Widget 资产 `SearchControl`：
- **样式参考**："数据筛选器"（FilterControl）组件
- **配置项**：
  | 字段 | 类型 | 说明 |
  |------|------|------|
  | 数据集 | Select | 选择目标数据集 |
  | 搜索字段 | FieldSelect | 选择用于搜索的字段 |
  | 定位字段（经度） | FieldSelect | 搜索结果对应的经度字段 |
  | 定位字段（纬度） | FieldSelect | 搜索结果对应的纬度字段 |
  | 缩放等级 | Number | 跳转后的地图缩放等级（0-24，默认 11） |
- **交互流程**：
  1. 用户在下拉框/搜索框中输入关键词
  2. 实时过滤匹配的数据行
  3. 点击某个结果 → 地图相机 flyTo 到对应经纬度 + 缩放至设定等级

### 9.3 技术方案

**新增文件：**
- `packages/li-analysis-assets/src/widgets/SearchControl/`（完整 Widget 结构）
  - `index.tsx` - 注册
  - `Component.tsx` - 搜索 UI + 地图交互
  - `registerForm.ts` - 配置表单
  - `ComponenStyle.ts` - 样式

**核心逻辑：**
```typescript
// 使用 @antv/larkmap 的 useScene 获取 scene 实例
const scene = useScene();

// 搜索结果点击
const onSelectResult = (record: any) => {
  const lng = record[lngField];
  const lat = record[latField];
  scene.setZoomAndCenter(zoomLevel, [lng, lat]);
};
```

**注册到默认 Widget 列表：**
- `DEFAULT_ANALYSIS_WIDGETS` 中增加 SearchControl 配置

---

## 10. 实施计划

| 阶段 | 需求 | 改动范围 | 估时 |
|------|------|---------|------|
| **Phase 1** | #6 空值防御 | 前端 li-core-assets / li-analysis-assets | 小 |
| **Phase 1** | #7 jd/wd 检测 | 前端 li-core-assets coordinate-schema | 小 |
| **Phase 1** | #4 关闭 DMS 日志 | 前端 li-analysis-assets | 小 |
| **Phase 2** | #1 图标映射恢复 | 后端 API + 前端 li-p2 + li-core-assets | 中 |
| **Phase 2** | #2 瓦片层级修复 | 后端 + 前端 website | 中 |
| **Phase 2** | #3 图标外圈透明 | 前端 l7-component marker 补丁 | 小 |
| **Phase 3** | #5 多瓦片管理 | 后端 DB + API + 前端 website 组件 | 大 |
| **Phase 3** | #8 搜索定位控件 | 前端 li-analysis-assets 新 Widget | 中 |

---

> **注**：Phase 1 的改动（#4, #6, #7）为小范围修复，可立即实施。
> Phase 2 和 Phase 3 需要更多改动，建议分批进行。
