# DE ↔ L7 跨项目地图联动 — 完整技术方案

> 版本: v2.0 | 日期: 2026-08-05

---

## 一、项目背景

| 项目             | 框架               | 端口                  | 数据库        | 角色            |
| ---------------- | ------------------ | --------------------- | ------------- | --------------- |
| L7VP (L7)        | React + UMI4       | 8000(dev) / 3001(API) | 达梦 DM8      | 地理可视化地图  |
| DataEase v2 (DE) | Vue3 + Spring Boot | 8100                  | MySQL + Redis | 数据大屏/仪表板 |

L7 地图以网页组件形式（DeFrame / iframe）嵌入 DE 大屏。双方通过 `window.postMessage` 通信，不经过服务端。

---

## 二、需求定义

### 需求一：单点上图 ✅ 已完成

DE 表格行含经纬度字段 → 点击行 → 地图标出该点。

### 需求二：多点上图（待实施）

DE 柱状图/饼图按类别聚合（如"区域"）→ 点击某类别 → 查询 DE 数据集该类别全部明细行 → 提取每行经纬度 → 地图批量标点。

### 需求三：路段上图（待实施）

DE 柱状图按区域聚合 → 点击某区域 → 查询 DE 数据集该区域所有路段 → 提取每行起点/终点经纬度 → 地图绘制连线。

### 图层管理

| 操作           | 行为                         |
| -------------- | ---------------------------- |
| 首次点击       | 标点/标线                    |
| 同数据再次点击 | 撤销该标注（Toggle）         |
| DE「清除联动」 | 清除所有 L7 标注，恢复纯底图 |

### 样式配置

在 DeFrame 属性面板配置点/线的颜色、大小、透明度。默认关闭（普通网页不受影响）。

---

## 三、已完成的改动

### 3.1 核心链路

```
DE 图表点击 → action(param)
  → handleL7PointClick({ ...param.data, viewId })
  → DOM 查找 iframe[data-l7-map]
  → 从 componentData 读 elem.l7Configs[viewId]
  → 按 sourceField 匹配 dimensionList 中的字段 ID
  → 提取经纬度 → postMessage { action:'highlight', highlightPoints, filters }
  → L7 Share 收到 → setAppConfig（增量更新）→ BubbleLayer 标点
```

### 3.2 已修改的文件

| 文件                            | 改动                                                       |
| ------------------------------- | ---------------------------------------------------------- |
| `Share/index.tsx` (L7)          | postMessage 监听，highlight/filter 处理                    |
| `ComponentFrame.vue` (DE)       | 自动识别 L7 URL + `data-l7-map` 标记；调 L7 API 获取字段   |
| `LinkageSet.vue` (DE)           | DeFrame 出现在联动目标列表；l7Configs 按 sourceViewId 分存 |
| `l7Bridge.ts` (DE) **新增**     | 核心桥接：查找 iframe → 匹配字段 → postMessage             |
| `ChartComponentS2.vue` (DE)     | 2 行：import + `action()` 中调用 handleL7PointClick        |
| `ChartComponentG2Plot.vue` (DE) | 同上                                                       |
| `canvasUtils.ts` (DE)           | `findAllViewsId` 添加 DeFrame                              |
| `dvMain.ts` (DE)                | 清理旧方法，无新增逻辑                                     |

### 3.3 数据结构

```javascript
// 存储在 DeFrame 组件的 element 上
elem.l7Configs = {
  表格的视图ID: {
    active: true,
    fields: [
      { sourceField: '1785809506401', targetL7Field: '起点经度' },
      { sourceField: '1785809506402', targetL7Field: '起点纬度' },
    ],
  },
  饼图的视图ID: {
    active: true,
    fields: [{ sourceField: '1785809506399', targetL7Field: '类型' }],
  },
};
```

---

## 四、待实施计划

### 4.1 数据结构扩展

`l7Config` 增加 `tableId` 和 `role`：

```javascript
l7Configs[viewId] = {
  active: true,
  tableId: 12345, // 数据集表ID
  fields: [
    { sourceField: 'ID1', targetL7Field: '区域', role: 'filter' },
    { sourceField: 'ID2', targetL7Field: '经度', role: 'lng' },
    { sourceField: 'ID3', targetL7Field: '纬度', role: 'lat' },
    // 路段模式：
    { sourceField: 'ID4', targetL7Field: '起点经度', role: 'lng1' },
    { sourceField: 'ID5', targetL7Field: '起点纬度', role: 'lat1' },
    { sourceField: 'ID6', targetL7Field: '终点经度', role: 'lng2' },
    { sourceField: 'ID7', targetL7Field: '终点纬度', role: 'lat2' },
  ],
};
```

`role` 决定运行时行为：

| role 分布                           | 模式             | 动作                          |
| ----------------------------------- | ---------------- | ----------------------------- |
| 仅 `filter`                         | 纯筛选           | 直接发 filter 给 L7           |
| `lng` + `lat`                       | 点模式           | 查 DE 数据集 → 提取坐标       |
| `lng1` + `lat1` + `lng2` + `lat2`   | 线段模式         | 查 DE 数据集 → 提取起终点坐标 |
| 纯 `lng` + `lat` 且行数据中直接有值 | 单点模式（现有） | 不需查询，直接用              |

新增样式配置 `l7Style`：

```javascript
elem.l7Style = {
  enabled: false, // 默认关闭
  pointColor: '#F86624',
  pointRadius: 10,
  pointOpacity: 0.85,
  lineColor: '#F86624',
  lineWidth: 2,
  lineOpacity: 0.85,
  lineStyle: 'solid',
};
```

---

### 4.2 实施步骤

#### Phase 1：样式配置（基础）~1.5h

| 步骤    | 文件                   | 内容                                                                             |
| ------- | ---------------------- | -------------------------------------------------------------------------------- |
| **1.1** | `component-list.ts`    | DeFrame 模板增加 `l7Style` 默认值（`enabled: false`）                            |
| **1.2** | `de-frame/Attr.vue`    | 新增「L7 联动标注」折叠面板：开关 + 点颜色/半径/透明度 + 线颜色/线宽/透明度/线型 |
| **1.3** | `l7Bridge.ts`          | 读取 `elem.l7Style`；`enabled === false` 时跳过；postMessage 中附带 `style` 参数 |
| **1.4** | `Share/index.tsx` (L7) | `buildHighlightConfig` 用 `msg.style` 覆盖硬编码默认值                           |

**验证**：配置面板显示正常、关闭时不联动、开启后地图标注使用配置的颜色/大小。

---

#### Phase 2：数据集查询（多点上图）~2h

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| **2.1** | `LinkageSet.vue` | 字段映射配置增加 `role` 下拉（filter / lng / lat / lng1 / lat1 / lng2 / lat2）；保存时存入 `l7Config.fields[].role` |
| **2.2** | `l7Bridge.ts` | 新增 `fetchDatasetRows(tableId, filterFieldId, filterValue)` — 调 `POST /de2api/chartData/getData` 查明细行 |
| **2.3** | `l7Bridge.ts` | 重写匹配逻辑：分析 `role` → 判断模式（单点/多点/线）→ 纯 filter 直接发；多点/线模式调 `fetchDatasetRows` 提取坐标 |
| **2.4** | `l7Bridge.ts` | 多点模式：查询返回多行 → 每行提取 lng/lat → 构建 `highlightPoints: [{lng,lat}, ...]` → postMessage |

**验证**：配置柱状图联动(role=lng+lat) → 点击柱子 → DE 调 API 查明细 → 地图批量标出所有点。

---

#### Phase 3：路段上图 ~1.5h

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| **3.1** | `Share/index.tsx` (L7) | 新增 `highlightLines` action → 创建 FlowLayer（起终点连线） |
| **3.2** | `l7Bridge.ts` | 线段模式：读取 `role=lng1/lat1/lng2/lat2` → 查询返回多行 → 构建 `lines: [{fromLng,fromLat,toLng,toLat}]` → postMessage `highlightLines` |
| **3.3** | `Share/index.tsx` (L7) | `removeHighlightConfig` 增加对 FlowLayer 的清理 |

**验证**：配置柱状图联动(role=lng1+lat1+lng2+lat2) → 点击柱子 → 地图绘制所有路段连线。

---

#### Phase 4：Toggle + 清除联动 ~1.5h

| 步骤 | 文件 | 内容 |
| --- | --- | --- |
| **4.1** | `l7Bridge.ts` | 新增 `overlayRegistry: Map<string, Set<string>>`；生成 layer ID = `de_overlay_{sourceViewId}_{rowHash}`；点击时检查是否已存在 → 存在发 `clearHighlight`，不存在发 `highlight` |
| **4.2** | `l7Bridge.ts` | 导出 `clearL7Overlays()`：遍历所有 DeFrame iframe → postMessage `{ action: 'clearOverlays' }` |
| **4.3** | `Share/index.tsx` (L7) | 新增 `clearHighlight` action（删指定 ID 图层）+ `clearOverlays` action（删所有 `de_overlay_` 前缀图层） |
| **4.4** | DE 清除联动回调 | 在清除联动按钮回调中调用 `clearL7Overlays()` |

**验证**：点击行标注 → 再点同数据行消失；点击「清除联动」→ 所有标注清除。

---

#### Phase 5：注释标记 + 代码收敛 ~1h

| 步骤    | 内容                                                                                                |
| ------- | --------------------------------------------------------------------------------------------------- |
| **5.1** | 所有修改处添加 `// L7_INTEGRATION:` 注释标记                                                        |
| **5.2** | `l7Bridge.ts` 文件头写入模块说明                                                                    |
| **5.3** | 确认 DE 侧改动收敛在 4 个文件内（`l7Bridge.ts`、`Attr.vue`、`LinkageSet.vue`、`component-list.ts`） |
| **5.4** | 全链路测试：单点/多点/路段 + toggle + 样式 + 清除联动                                               |

---

### 4.3 实施路线图

```
Phase 1 (样式配置)      ████████░░░░░░░░  ~1.5h
Phase 2 (数据集查询)    ░░░░░░░░████████░░  ~2h
Phase 3 (路段上图)      ░░░░░░░░░░░░░░████  ~1.5h
Phase 4 (Toggle/清除)   ░░░░░░░░░░░░░░░░░  ~1.5h
Phase 5 (注释标记)      ░░░░░░░░░░░░░░░░░  ~1h
────────────────────────────────────────────
总计                    ~7.5h
```

---

### 4.4 改动文件收敛

| 文件                   | 改动类型             | 预估行数 |
| ---------------------- | -------------------- | -------- |
| `l7Bridge.ts`          | **重写**             | ~150 行  |
| `de-frame/Attr.vue`    | 修改（新增折叠面板） | ~60 行   |
| `LinkageSet.vue`       | 修改                 | ~30 行   |
| `Share/index.tsx` (L7) | 修改                 | ~50 行   |
| `component-list.ts`    | 修改（默认值）       | ~10 行   |

共 **5 个文件**，其中 DE 侧 **4 个文件**，L7 侧 **1 个文件**。

---

## 五、注释标记规范

所有新增/修改处统一标记：

```javascript
// ================================================================
// L7_INTEGRATION: [改动描述]
// 日期: 2026-08-05
// ================================================================
... 改动代码 ...
// ================================================================
```

---

## 六、ARM Docker 兼容性

| 关注点                   | 结论                                                       |
| ------------------------ | ---------------------------------------------------------- |
| 前端代码（TS/Vue/React） | ✅ 纯 JS，Vite 编译，无关架构                              |
| 后端代码（Spring Boot）  | ✅ JVM 跨架构                                              |
| 达梦 JDBC 驱动           | ⚠️ 需替换 ARM 版 `DmJdbcDriver.jar`                        |
| Docker 基础镜像          | ✅ `eclipse-temurin:21-jre-alpine` 支持 arm64              |
| MySQL / Redis            | ✅ 官方支持 arm64                                          |
| npm 原生模块             | ✅ 项目无原生依赖（无 `node-gyp`）                         |
| 文件路径大小写           | ⚠️ `import` 路径需与文件名完全一致（Linux ARM 区分大小写） |

---

## 七、构建与部署

### 开发环境

```bash
# DE 前端构建
cd /opt/myproject/dataease/dataease-dev-v2/core/core-frontend
npm run build:base

# DE 后端打包
cd /opt/myproject/dataease/dataease-dev-v2
mvn package -pl core/core-backend -am -DskipTests -Pstandalone -DskipFrontend=true

# 重启 DE
kill $(lsof -t -i:8100)
nohup java -Dspring.config.additional-location=file:/.../config/application.yml \
  -jar /.../CoreApplication.jar --spring.profiles.active=standalone > /tmp/dataease.log 2>&1 &
```

### ARM Docker 构建

```bash
# DE Docker 镜像（ARM64）
docker build --platform linux/arm64 \
  -f offline-deploy/Dockerfile.app \
  -t dataease-app:latest .

# L7 Docker 镜像（ARM64）
docker build --platform linux/arm64 \
  -t l7vp-backend:latest .
```

### 离线部署

DE 项目自带 `offline-deploy/` 目录，含 `deploy.sh` 一键部署脚本。需替换达梦 JDBC 驱动为 ARM 版本。

---

## 八、调试技巧

| 场景                    | 方法                                                                             |
| ----------------------- | -------------------------------------------------------------------------------- |
| 验证 L7 收到消息        | F12 → Console → 切 iframe 上下文（localhost:8000）→ 找 `[L7 Share] 收到消息:`    |
| 验证 DE 发送消息        | F12 → Console → top 上下文 → 找 `[L7 Bridge] postMessage`                        |
| 浏览器缓存旧 JS         | 无痕模式打开，或 DevTools → Network → Disable cache                              |
| 查看 l7Configs 是否保存 | Console 输入 `document.querySelectorAll('iframe[data-l7-map]')` 检查 iframe 存在 |
