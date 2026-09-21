# L7VP 功能修复与增强需求文档 (Proposal V4)

> **版本**: v4.0
> **日期**: 2026-07-16
> **状态**: 待确认
> **关联文档**: [proposal-v3.md](./proposal-v3.md)（V3 图标映射/瓦片/DMS/多瓦片/空值/jd-wd/搜索定位）

---

## 目录

- [1. 背景](#1-背景)
- [2. 需求一：图标映射改造（固定图标模式）](#2-需求一图标映射改造固定图标模式)
- [3. 需求二：搜索定位控件](#3-需求二搜索定位控件)
- [4. 需求三：地理圆图层](#4-需求三地理圆图层)
- [5. 需求四：隐藏侧边栏透明度配置](#5-需求四隐藏侧边栏透明度配置)
- [6. 需求五：时间/日期字段类型识别](#6-需求五时间日期字段类型识别)
- [7. 实施计划](#7-实施计划)

---

## 1. 背景

经过 V1-V3 多轮迭代，L7VP 已具备基本的可视化分析能力。本轮需求聚焦于以下方向：

- 图标图层功能对齐原始 L7VP 设计
- 补充缺失的地图交互控件（搜索定位）
- 新增图层资产（地理圆）
- 修复数据治理短板（时间字段识别）

---

## 2. 需求一：图标映射改造（固定图标模式）

### 2.1 现状分析

**当前代码** (`packages/li-p2/src/LayerAttribute/IconImageLayerStyle/schema.tsx`)：

- `iconType`（Radio）：`"固定图标"` / `"基于字段"`
- **固定图标模式**：仅有一个 `iconImg`（图标形状）下拉框，选单个图标
- **基于字段模式**：`iconLibraryField`（库号字段）+ `iconCodeField`（代号字段）+ `iconImgFallback`（默认图标）
- 旧字段 `iconField`、`iconImgScale` 被标记为 `'x-display': 'hidden'`（隐藏）

**原始 master 代码** (`master/L7VP-master/.../IconImageLayerStyle/schema.tsx`)：

- 没有 `iconType` 切换，直接一个 `iconField`（基于字段）下拉
- `iconField` 有值时 → 显示 `iconImgScale`（图标映射），隐藏 `iconImg`（图标形状）
- `iconField` 无值时 → 显示 `iconImg`（图标形状），隐藏 `iconImgScale`（图标映射）

**原始 master helper** (`master/.../IconImageLayerStyle/helper.ts`)：

- `iconField` 有值：构建 `icon = { field, value: range, scale: { type: 'cat', domain, unknown } }`
- `iconField` 无值：`icon = iconImg`（字符串）

### 2.2 改造目标

| 要求 | 说明 |
|------|------|
| a. 保留"图标模式"选项 | `iconType` Radio.Group 继续存在，选项不变 |
| b. "基于字段"模式不动 | `iconLibraryField` + `iconCodeField` + `iconImgFallback` 代码、逻辑一行不改 |
| c. "固定图标"模式改造 | 将 master 的 `iconField` + `iconImgScale` + `iconImg` 整体移植到"固定图标"模式下 |
| d. 调换顺序 | `iconImg`（图标形状）/ `iconImgScale`（图标映射）放上面，`iconField`（基于字段）放下面 |
| e. master 代码只读 | 不修改 `/master/L7VP-master/` 下的任何文件 |

### 2.3 实施方案

#### 2.3.1 Schema 改造 (`packages/li-p2/.../IconImageLayerStyle/schema.tsx`)

在 `iconType === 'fixed'` 的 `x-reactions` 作用下，按如下顺序排列：

```
iconImg         (title: "图标形状", IconSelector)
                → visible: iconType === 'fixed' && iconField === undefined

iconImgScale    (title: "图标映射", IconScaleSelector)  
                → visible: iconType === 'fixed' && iconField !== undefined

iconField       (title: "基于字段", FieldSelect, 仅 string 类型字段)
                → visible: iconType === 'fixed'
```

**注意**：
- `iconField` 的 `enum` 应过滤为 `type === 'string'`（与 master 一致）
- `iconImgScale` 的 `domain` 需要动态获取 `iconField` 选中字段的唯一值
- 当 `iconField` 值变化时，重置 `iconImgScale` 的值（与 master 一致）

#### 2.3.2 Helper 改造 (`packages/li-p2/.../IconImageLayerStyle/helper.ts`)

`iconImageLayerStyleFlatToConfig` 需要处理新的"固定图标"模式分支：

```typescript
// 伪代码逻辑
if (iconType === 'field') {
  // === 不变：基于字段模式（库号+代号） ===
  icon = '_iconUrl';
  fallbackIconUrl = iconImgFallback || undefined;
} else {
  // === "固定图标"模式 ===
  if (iconField) {
    // 有基于字段 → 字段值到图标的映射（原 master 逻辑）
    const { domain, range, unknown } = iconImgScale;
    icon = { field: iconField, value: range, scale: { type: 'cat', domain, unknown } };
    // iconAtlas 从 range 动态构建
  } else {
    // 无基于字段 → 单一固定图标（现有逻辑）
    icon = iconImg;
    iconAtlas = { [iconImg]: iconImg };
  }
  fallbackIconUrl = undefined;
}
```

`iconImageLayerStyleConfigToFlat` 同样需要反向处理。

#### 2.3.3 涉及文件

| 文件 | 操作 |
|------|------|
| `packages/li-p2/src/LayerAttribute/IconImageLayerStyle/schema.tsx` | 修改：在 `iconType === 'fixed'` 分支下重构为 iconImg + iconImgScale + iconField |
| `packages/li-p2/src/LayerAttribute/IconImageLayerStyle/helper.ts` | 修改：`flatToConfig` / `configToFlat` 增加 fixed 模式下的 field-based 分支 |
| `packages/li-p2/src/LayerAttribute/IconImageLayerStyle/constant.ts` | 可能修改：默认值调整 |

#### 2.3.4 不变更文件

- `packages/li-sdk/src/components/internal/WrapperLayer/hooks/useLayerProps.ts`（`enrichIconUrls`）：仅在 `iconType === 'field'` 时触发，不受影响

---

## 3. 需求二：搜索定位控件

### 3.1 现状分析

**上一轮残留代码** (`packages/li-analysis-assets/src/widgets/SearchControl/`)：

- `index.tsx`：已注册，metadata name=`SearchControl`, displayName=`搜索定位`, type=`Auto`, category=`MapControl`
- `Component.tsx`：有基本骨架——通过 `useLayerList`/`useDatasetList` 获取数据，`AutoComplete` 搜索，`scene.setZoomAndCenter` 跳转
- `registerForm.ts`：有基本 schema——position/layerId/searchField/zoomLevel
- **缺失**：`ComponenStyle.ts`（样式文件）、`constants.tsx`（图标）、TypeScript 类型定义
- **问题**：
  - 使用手动绝对定位（`POS_STYLE` 对象）而非 `CustomControl` 组件
  - 未限制图层类型（应只允许气泡、图标图层）
  - 搜索字段选项来自所有数据集的 string 列，而非只来自选中图层的数据集
  - zoomLevel 用了 `NumberPicker`，应改为 `Slider`

**当前状态**：`packages/li-analysis-assets/src/widgets/index.ts` 中已注释掉，标注"暂缓开发"

### 3.2 功能需求

| 配置项 | 类型 | 说明 |
|--------|------|------|
| 目标图层 | Select | 选择气泡图层或图标图层 |
| 搜索字段 | Select | 选择要搜索的字段（选中图层对应数据集的字段） |
| 跳转缩放等级 | Slider | 0-24 滑动条，默认 11，可不填 |
| 放置方位 | ControlPositionSelect | 左上/右上/左下/右下，默认左上 |

交互流程：
1. 用户在搜索框中输入关键词
2. 从选中图层的数据中，筛选出"搜索字段"值包含关键词的行
3. 下拉列表显示匹配结果（最多 20 条）
4. 用户点击某条结果 → 相机视角飞至该数据的经纬度位置，缩放至设定等级

### 3.3 约束条件

- 目标图层**仅允许**气泡图层（BubbleLayer）和图标图层（IconLayer）
- 搜索字段下拉选项**仅来自**选中图层关联的数据集
- 如果未选择图层或未选择搜索字段，组件返回 `null`（不渲染）
- 样式参考 **FilterControl**（数据筛选器）组件

### 3.4 实施方案

#### 3.4.1 组件改造 (`Component.tsx`)

- 使用 `CustomControl`（`@antv/larkmap`）替代手动 `POS_STYLE`
- 导入 `Properties` 类型、`useStyle()`
- 从 `registerForm` 导出 `Properties` 类型

#### 3.4.2 样式文件 (`ComponenStyle.ts`)

参考 `FilterControl/Component/style.ts` 的模式：
```typescript
import { css } from '@emotion/css';
import { theme } from 'antd';

const useStyle = () => {
  const { useToken } = theme;
  const { token } = useToken();
  return {
    searchControl: css`
      background: ${token.colorBgContainer};
      border-radius: ${token.borderRadius}px;
      padding: 8px;
    `,
    // ...
  };
};
```

#### 3.4.3 配置面板 (`registerForm.ts`)

```typescript
export type Properties = {
  position?: PositionName;
  layerId?: string;
  searchField?: string;
  zoomLevel?: number;
};

// Schema:
{
  layerId:       { Select, 过滤 BubbleLayer + IconLayer },
  searchField:   { Select, 依赖于 layerId 的数据集列 },
  zoomLevel:     { Slider, 0-24, step=1, marks, default=11 },
  position:      { ControlPositionSelect, default='lefttop' },
}
```

#### 3.4.4 注册启用

- 取消 `packages/li-analysis-assets/src/widgets/index.ts` 中 SearchControl 的注释
- 新建 `constants.tsx`：SVG 图标组件
- 在 `index.tsx` 的 `metadata` 中添加 `icon` 属性

#### 3.4.5 涉及文件

| 文件 | 操作 |
|------|------|
| `packages/li-analysis-assets/src/widgets/SearchControl/index.tsx` | 修改：添加 icon |
| `packages/li-analysis-assets/src/widgets/SearchControl/Component.tsx` | 重写：CustomControl + 样式 + 类型 |
| `packages/li-analysis-assets/src/widgets/SearchControl/registerForm.ts` | 重写：Properties 类型 + Slider + 图层过滤 |
| `packages/li-analysis-assets/src/widgets/SearchControl/ComponenStyle.ts` | 新建 |
| `packages/li-analysis-assets/src/widgets/SearchControl/constants.tsx` | 新建：SVG 图标 |
| `packages/li-analysis-assets/src/widgets/index.ts` | 修改：取消注释 SearchControl 导出 |

---

## 4. 需求三：地理圆图层

### 4.1 背景

需要一个能在地图上绘制**地理圆**（geographic circle）的图层资产。地理圆是以地球表面某点为中心、以真实物理距离（米/千米）为半径绘制的圆，而非屏幕像素圆。

### 4.2 地理圆算法

直接复用 L7VP 绘制图形（DrawControl）组件中的圆绘制算法，该算法在 `@antv/l7-draw` 中实现。

**核心算法**（来自 `node_modules/@antv/l7-draw/es/drawer/circle-drawer.js`）：

```javascript
import { destination, distance } from '@turf/turf';

function getBoundaryPositions(startPoint, endPoint) {
  const steps = 60; // 用 60 边形逼近圆
  const dis = distance(startPoint, endPoint, { units: 'meters' });
  const positions = [];
  for (let i = 0; i < steps; i++) {
    positions.push(
      destination(startPoint, dis, i * -360 / steps, { units: 'meters' })
        .geometry.coordinates
    );
  }
  positions.push(positions[0]); // 闭合
  return positions;
}
```

**原理**：
1. 使用 `turf.distance` 计算半径距离
2. 使用 `turf.destination` 在中心点周围以等角度步长生成 60 个目标点
3. 连接所有目标点形成多边形（逼近地理圆）

### 4.3 图层设计

#### 4.3.1 配置项

地理圆图层的配置栏**与气泡图层几乎一致**，差异点：

| 配置项 | 类型 | 说明 | 与气泡图层的差异 |
|--------|------|------|------------------|
| 经度字段 | FieldSelect | 圆心经度 | 相同 |
| 纬度字段 | FieldSelect | 圆心纬度 | 相同 |
| 坐标类型 | Select | 点度/度分秒/Geometry | 相同 |
| **填充半径** | **InputNumber** | 物理长度数值 | **替代气泡的"半径"滑动条** |
| **半径单位** | **Select** | 米/千米/英里/海里 | **新增** |
| 填充颜色 | ColorPicker | 圆的填充色 | 相同 |
| 透明度 | Slider | 0-1 | 相同 |
| 描边颜色 | ColorPicker | 描边颜色 | 相同 |
| 描边宽度 | Slider | 描边宽度 | 相同 |
| 文本标注 | FieldSelect + 样式 | 标签字段和样式 | 相同 |
| 缩放范围 | SliderRange | minZoom/maxZoom | 相同 |
| 混合模式 | Select | blend | 相同 |

**移除的字段**（相比气泡图层）：`radius` 滑动条（替换为填充半径 + 单位）、`strokeOpacity`（与透明度合并）

#### 4.3.2 渲染逻辑

1. 从数据中读取每一行的经纬度（圆心）
2. 读取半径值和单位
3. 使用 turf.js `destination` 为每个圆心生成地理圆多边形（60边形）
4. 将所有地理圆渲染为 PolygonLayer

**单位转换**：所有单位统一转换为米（meters）传给 turf：
- 米: ×1
- 千米: ×1000
- 英里: ×1609.344
- 海里: ×1852

#### 4.3.3 图层实现

参考 BubbleLayer 的结构：

```
packages/li-core-assets/src/layers/GeoCircleLayer/
  index.tsx          → implementLayer() 注册
  Component.tsx      → React 组件
  register-form/
    index.ts         → toValues/fromValues 转换器
    schema.ts        → Formily schema
    coordinate-schema.ts → 坐标选择 schema（复用 BubbleLayer 的）
```

**Component.tsx 关键逻辑**：
- 使用 L7 的 PolygonLayer 渲染地理圆
- 在数据传入前，将每行数据的地标圆计算为 GeoJSON Polygon
- 将计算后的多边形作为 source data 传给 PolygonLayer

**register-form/schema.ts**：
- 复用 BubbleLayer 的 fillColor/opacity/stroke/label/blend 等字段
- 新增 `radiusValue`（InputNumber）+ `radiusUnit`（Select）

#### 4.3.4 涉及文件

| 文件 | 操作 |
|------|------|
| `packages/li-core-assets/src/layers/GeoCircleLayer/index.tsx` | 新建 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/Component.tsx` | 新建 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/register-form/index.ts` | 新建 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/register-form/schema.ts` | 新建 |
| `packages/li-core-assets/src/layers/GeoCircleLayer/register-form/coordinate-schema.ts` | 新建（复用 BubbleLayer 的） |
| `packages/li-core-assets/src/layers/index.ts` | 修改：添加 GeoCircleLayer 导出 |
| `packages/li-core-assets/src/services/index.ts` | 不需要修改 |
| `website/src/constants/project.ts` | 修改：DEFAULT_LAYERS 添加 GeoCircleLayer（可选） |

---

## 5. 需求四：隐藏侧边栏透明度配置

### 5.1 现状

`packages/li-analysis-assets/src/widgets/AnalysisLayout/registerForm.ts` 第 31-42 行定义了 `sidePanelOpacity` 字段（侧边栏透明度），类型为 Slider，范围 0-100。

该功能因未做好需要隐藏。

### 5.2 实施方案

在 `sidePanelOpacity` 配置中添加 `'x-display': 'hidden'`：

```typescript
sidePanelOpacity: {
  title: '侧边栏透明度',
  type: 'number',
  'x-display': 'hidden',  // 新增：隐藏该配置
  // ...其余不变
},
```

#### 5.2.1 涉及文件

| 文件 | 操作 |
|------|------|
| `packages/li-analysis-assets/src/widgets/AnalysisLayout/registerForm.ts` | 修改：sidePanelOpacity 添加 `'x-display': 'hidden'` |

---

## 6. 需求五：时间/日期字段类型识别

### 6.1 现状分析

#### 6.1.1 前端类型推断 (`packages/li-sdk/src/utils/dataset-parser/meta-data.ts`)

`getDatasetColumns` 函数通过采样第一条非空值来推断列类型。`isDateField` 函数仅识别 **6 种**日期格式：

```
YYYY-MM-DD HH:mm:ss
YYYY/MM/DD HH:mm:ss
YYYY-MM-DD
YYYY/MM/DD
YYYY-MM
YYYY/MM
```

**缺失的常见格式**：
- ISO 8601：`2024-01-15T10:30:00Z`、`2024-01-15T10:30:00+08:00`
- 中文日期：`2024年1月15日`
- 时间戳（秒/毫秒）：`1705312200`、`1705312200000`
- 美国格式：`MM/DD/YYYY`、`Jan 15, 2024`
- 紧凑格式：`20240115`、`20240115103000`
- 点分隔：`2024.01.15`、`01.15.2024`
- Excel 序列号日期（OADate）

#### 6.1.2 后端数据库列类型 (`DbConnectionService.java`)

**当前行为**：`queryTable()` / `previewTable()` 只从 `ResultSetMetaData` 取列名（`getColumnName`），**不取列类型**。返回给前端的 JSON 只包含 `{ rows, rowCount }`，**没有列类型信息**。

前端收到数据后调用 `getDatasetColumns`，再次通过值采样推断类型：
- 数据库中的 `DATE`/`TIMESTAMP` 列，JDBC 返回 Java `Date`/`Timestamp` 对象
- Jackson 序列化后变成字符串（如 `"2024-01-15"` / `"2024-01-15 10:30:00"`）
- 但这些字符串不一定匹配 `isDateField` 的 6 种格式

#### 6.1.3 API 数据集（中台 API）

中台 API 返回的 JSON 数据，日期字段可能以各种字符串格式出现。同样依赖前端 `isDateField` 的 6 种格式识别。

#### 6.1.4 Excel 数据集 (`data-parser.ts`)

Excel 解析使用 `cellDates: true`，日期单元格被转为 JS Date 对象。然后对 `item.t === 'd'` 的单元格，用 `item.w`（格式化字符串）替换。格式化字符串的样式取决于 Excel 中的单元格格式设置，可能不匹配 6 种格式。

### 6.2 需求说明

| 子需求 | 数据源 | 说明 |
|--------|--------|------|
| a. 数据库字段类型 | DM8、MySQL/Doris | 从系统视图/ResultSetMetaData 获取字段真实类型，DATE/TIMESTAMP 类型应标记为 `date` |
| b. API 时间字符串 | 中台 API | 增加更多常见时间字符串格式的识别 |
| c. Excel 日期字段 | 本地文件上传 | 修复 Excel 日期识别问题（已在上传时崩溃） |
| d. 明确日期识别规则 | 全部 | 文档化哪些格式会被识别为日期 |

### 6.3 实施方案

#### 6.3.1 后端：数据库列类型返回 (DbConnectionService.java)

**修改 `queryTable` / `previewTable` 返回值**，增加 `columns` 数组，从 `ResultSetMetaData` 提取类型：

```java
// 新增内部类或 Map
List<Map<String, String>> columns = new ArrayList<>();
for (int i = 1; i <= colCount; i++) {
    Map<String, String> col = new LinkedHashMap<>();
    col.put("name", meta.getColumnName(i));
    col.put("type", meta.getColumnTypeName(i)); // VARCHAR, INTEGER, DATE, TIMESTAMP...
    columns.add(col);
}
// 返回 { rows, rowCount, columns }
```

**类型映射表**（SQL 类型 → 前端类型）：

| SQL 类型 (JDBC Type Name) | 前端类型 | 说明 |
|---------------------------|----------|------|
| DATE | date | DM8/MySQL 日期 |
| DATETIME | date | MySQL 日期时间 |
| TIMESTAMP | date | DM8 时间戳 |
| TIME | string | 纯时间，暂不特殊处理 |
| VARCHAR, CHAR, TEXT, CLOB | string | 字符串 |
| INT, INTEGER, BIGINT, SMALLINT, TINYINT | number | 整数 |
| FLOAT, DOUBLE, DECIMAL, NUMERIC, NUMBER | number | 浮点数 |
| BOOLEAN, BIT | boolean | 布尔值 |

**注意**：DM8 数据库的 JDBC 类型名可能与 MySQL 不同（如 `DM_DATE`、`DM_TIMESTAMP`），需要兼容处理。

**备选方案**：如果 JDBC meta 不可靠，可通过系统视图查询：
- **DM8**：`SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = ?`
- **MySQL/Doris**：`SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?`

#### 6.3.2 前端：数据库数据集列类型传递

修改 `database-dataset/helper.ts`（`getDatabaseData`），读取后端返回的 `columns` 信息，在创建数据集时将正确的列类型传递给 `getDatasetColumns` 或直接使用。

#### 6.3.3 前端：增强日期格式识别 (`meta-data.ts`)

`isDateField` 函数增加以下格式识别：

```typescript
// 新增格式检测
// ISO 8601: '2024-01-15T10:30:00Z', '2024-01-15T10:30:00+08:00'
dayjs(value, 'YYYY-MM-DDTHH:mm:ssZ', true).isValid()
// 时间戳（毫秒）：1705312200000（13位数字）
// 时间戳（秒）：1705312200（10位数字，合理范围）
// 紧凑格式：'20240115', '20240115103030'
// 中文格式：'2024年1月15日', '2024年01月15日'
// US格式：'01/15/2024' (MM/DD/YYYY)
// 点分隔：'2024.01.15', '01.15.2024'
// 英文月份：'Jan 15, 2024', '15 Jan 2024'
```

同时需要处理"伪日期"情况（纯数字被误判为日期），需要更严格的校验。

#### 6.3.4 前端：Excel 日期处理修复

**问题**：`parserExcelToSource` 中 `cellDates: true` 使得日期转为 Date 对象，然后 `item.t === 'd'` 时用 `item.w` 替换。但格式化后的字符串可能不被 `isDateField` 识别。

**修复**：
1. 优先检查 Excel 单元格的数字格式字符串（`item.z`），若符合日期格式，直接标记为 date 类型
2. 同时确保 `item.w` 的输出格式被 `isDateField` 识别（或专门处理 Excel 日期单元格）

**关于 Excel 上传 400 错误**：`http://localhost:8000/api/projects/{id}/datasets/upload` 返回 Bad Request 400，需要检查：
- 后端文件上传大小限制
- Jackson 序列化日期对象是否报错
- 前端 `createDataset` 请求体中日期字段的序列化问题

#### 6.3.5 涉及文件

| 文件 | 操作 |
|------|------|
| `java-server/.../service/DbConnectionService.java` | 修改：queryTable/previewTable 返回 columns 类型信息 |
| `java-server/.../controller/DbConnectionController.java` | 可能修改：API 响应结构 |
| `packages/li-sdk/src/utils/dataset-parser/meta-data.ts` | 修改：isDateField 增加更多格式识别 |
| `packages/li-core-assets/src/services/database-dataset/helper.ts` | 修改：传递后端返回的列类型 |
| `packages/li-editor/src/utils/dataset-parser/data-parser.ts` | 修改：Excel 日期处理优化 |

---

## 7. 实施计划

| 序号 | 需求 | 优先级 | 预计工作量 | 涉及文件数 |
|------|------|--------|-----------|-----------|
| 1 | 图标映射改造 | 高 | 中 | ~3 |
| 2 | 搜索定位控件 | 高 | 中 | ~5 |
| 3 | 地理圆图层 | 中 | 大 | ~7 |
| 4 | 隐藏侧边栏透明度 | 低 | 极小 | 1 |
| 5 | 时间/日期字段识别 | 高 | 中 | ~5 |

### 建议实施顺序

1. **需求四**（隐藏侧边栏透明度）—— 1 行改动，先完成
2. **需求一**（图标映射改造）—— 核心功能，优先
3. **需求五**（时间/日期字段识别）—— 数据治理，影响后续所有分析
4. **需求二**（搜索定位控件）—— 新组件开发
5. **需求三**（地理圆图层）—— 新图层开发，工作量最大，最后
