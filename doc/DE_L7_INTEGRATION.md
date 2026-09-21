# DE ↔ L7 跨项目联动 — 开发文档

> ⚠️ 本文档为已完成工作的记录。完整技术方案和待实施计划请参阅： `/root/DIG_GEO/doc/L7_DE_LINKAGE_PLAN.md`

> 更新日期：2026-08-04

---

## 一、项目概览

| 项目        | 代号 | 框架         | 端口                            | 数据库    | 描述                  |
| ----------- | ---- | ------------ | ------------------------------- | --------- | --------------------- |
| L7VP        | L7   | React + UMI4 | 8000(前端 dev) / 3001(Java API) | 达梦 DM8  | 地理可视化编辑器      |
| DataEase v2 | DE   | Vue3 + Vite  | 8100(前后端一体)                | MySQL 8.0 | 数据可视化大屏/仪表板 |

---

## 二、项目架构

### L7 项目 (`/root/DIG_GEO`)

```
website/src/pages/
  Builder/      — 地图编辑器（编辑模式）
  Share/        — 纯地图预览（专用于嵌入，无工具栏）★ 联动入口
  Preview/      — 带标题栏的预览

java-server/
  → 端口 3001，Spring Boot 2.7
  → 管理项目(projects)、数据集(datasets)、图层(layers)、瓦片配置(tile-configs)
  → 数据库：DIG_GEO schema，10 张表
```

### DE 项目 (`/opt/myproject/dataease/dataease-dev-v2`)

```
core/core-frontend/src/
  views/
    chart/components/views/components/
      ChartComponentS2.vue      — 表格渲染 ★ 联动触发点
      ChartComponentG2Plot.vue  — 图表渲染 ★ 联动触发点
      L7MapView.vue             — L7地图UserView组件
    dashboard/index.vue         — 仪表板主页
    data-visualization/         — 数据大屏主页

  store/modules/data-visualization/
    dvMain.ts                   — 核心状态管理（组件数据、联动控制）

  custom-component/
    de-frame/ComponentFrame.vue — 网页嵌入组件（iframe）★ L7嵌入方式
    component-list.ts           — 组件注册表

  components/visualization/
    LinkageSet.vue              — 联动配置对话框 ★ 修改点

core/core-backend/
  → 端口 8100，Spring Boot 3.3
  → 数据库：dataease（MySQL），Redis（缓存）
```

---

## 三、联动实现 — 已完成

### 整体方案

DE 表格/图表点击 → 提取行数据 → postMessage → L7 iframe 接收 → 地图标注

### 修改的文件清单

| 文件                       | 项目 | 改动内容                                                                       |
| -------------------------- | ---- | ------------------------------------------------------------------------------ |
| `Share/index.tsx`          | L7   | 添加 postMessage 监听，接收 highlight/filter 指令，在地图上标点/筛选           |
| `ComponentFrame.vue`       | DE   | 自动识别 L7 URL 并标记 `data-l7-map`；从 L7 API 获取数据集字段列表             |
| `LinkageSet.vue`           | DE   | DeFrame 出现在联动目标列表；DeFrame 字段映射存 `element.l7Config` 而非后端 API |
| `dvMain.ts`                | DE   | 移除之前错误的 `_forwardL7DeFrame` 方法（已清理干净）                          |
| `l7Bridge.ts`              | DE   | **新增** — `handleL7PointClick()` 函数，核心桥接逻辑                           |
| `ChartComponentS2.vue`     | DE   | 在 `action(param)` 中调用 `handleL7PointClick`                                 |
| `ChartComponentG2Plot.vue` | DE   | 同上                                                                           |
| `component-list.ts`        | DE   | 注册了 `l7-map` 图表类型（Web 组件备选方案，实际使用 DeFrame）                 |
| `canvasUtils.ts`           | DE   | `findAllViewsId` 添加 DeFrame；`canvasSave` 处理 DeFrame                       |
| `components.ts`            | DE   | 注册 L7MapAttr                                                                 |
| `chart.ts`                 | DE   | CHART_TYPE_CONFIGS 注册 l7-map                                                 |

### 新增文件

| 文件               | 项目 | 作用                                                                  |
| ------------------ | ---- | --------------------------------------------------------------------- |
| `l7Bridge.ts`      | DE   | 核心桥接：DOM 查找 L7 iframe → 读取 l7Config → 匹配字段 → postMessage |
| `L7MapView.vue`    | DE   | L7 地图的 UserView 渲染组件（备选）                                   |
| `l7-map/Attr.vue`  | DE   | L7 URL 配置面板                                                       |
| `l7-map/l7-map.ts` | DE   | 图表类型注册                                                          |

---

## 四、数据流（完整链路）

```
1. 用户在大屏编辑模式下配置联动
   → 联动设置对话框 → 勾选「网页组件」
   → 添字段映射：DE字段ID(1785809506401) → L7字段名(起点经度)
   → 保存后存入 element.l7Config = { active:true, fields:[...] }
   → 自动保存画布

2. 预览模式下点击表格行
   → ChartComponentS2.action(param)
   → handleL7PointClick(param.data)
   → param.data.dimensionList = [
        {id:1785809506399, value:"机场"},      // 类型字段
        {id:1785809506401, value:121.5},       // 起点经度
        {id:1785809506402, value:25.0}         // 起点纬度
      ]
   → 匹配 l7Config.fields: sourceField == "1785809506401" → 目标字段 "起点经度"
   → 检测到经纬度字段 → 构建 highlightPoints: [{lng:121.5, lat:25.0}]
   → postMessage({ action:'highlight', highlightPoints:[...], filters:[...] })

3. L7 iframe 收到消息
   → Share/index.tsx handleMessage()
   → setAppConfig(prev => buildHighlightConfig(prev, points))
   → LIAPP 组件更新 config prop（key 稳定，不重建地图）
   → 新增 BubbleLayer 渲染标注点
```

---

## 五、服务运行状态

### 当前运行的服务

| 服务        | 端口 | 启动命令                                                            |
| ----------- | ---- | ------------------------------------------------------------------- |
| L7 Java API | 3001 | `java -jar l7vp-server-1.0.0.jar`                                   |
| L7 前端 Dev | 8000 | `npm run start:website` (UMI dev，热更新)                           |
| DE 前后端   | 8100 | `java -jar CoreApplication.jar --spring.profiles.active=standalone` |
| MySQL       | 3306 | `service mysql start`                                               |
| Redis       | 6379 | `service redis-server start`                                        |
| 达梦 DM8    | 5236 | 系统服务                                                            |

### DE 外部配置文件

`/opt/myproject/dataease/dataease-dev-v2/config/application.yml`

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/dataease...
    username: root
    password: Password123@mysql
  data:
    redis:
      host: localhost
      password:
```

### 构建命令

```bash
# DE 前端构建
cd /opt/myproject/dataease/dataease-dev-v2/core/core-frontend
npm run build:base

# DE 后端打包（跳过前端重建）
cd /opt/myproject/dataease/dataease-dev-v2
mvn package -pl core/core-backend -am -DskipTests -Pstandalone -DskipFrontend=true

# 重启 DE
kill $(lsof -t -i:8100)
nohup java -Dspring.config.additional-location=file:/.../config/application.yml \
  -jar /.../CoreApplication.jar --spring.profiles.active=standalone > /tmp/dataease.log 2>&1 &
```

---

## 六、已知问题与待办

| 状态      | 问题                                 | 说明                                                 |
| --------- | ------------------------------------ | ---------------------------------------------------- |
| ✅ 已解决 | DeFrame 不在联动目标列表             | `LinkageSet.vue` 手动注入 DeFrame 条目               |
| ✅ 已解决 | saveLinkage 400 错误                 | DeFrame UUID 与后端 Long 类型冲突，改为前端存储      |
| ✅ 已解决 | 联动只转发被点击字段                 | 改为 hook `action(param)` 获取完整行数据             |
| ✅ 已解决 | 地图标点闪烁                         | L7 key 使用稳定 projectId                            |
| ⚠️ 待优化 | DE 前端构建偶有 prettier/eslint 报错 | `npx prettier --write` + `npx eslint --fix` 后再构建 |
| ⚠️ 待优化 | 浏览器缓存旧 JS                      | 文件名无 hash，需无痕模式或强制刷新                  |
| ⚠️ 待优化 | L7 高亮图层未自动清除旧数据          | 每次创建新图层，旧的 Map entries 未删除              |
| ❌ 未开始 | filter 分组高亮                      | 目前只支持单点高亮，不支持多类别分组着色             |
| ❌ 未开始 | 地图点击回传                         | L7 → DE 的 featureClick 回传已编码但 DE 侧未处理     |

---

## 七、调试技巧

### 查看 L7 是否收到消息

```
F12 → Console → 切换 iframe 上下文（localhost:8000）
→ 找 [L7 Share] 收到消息: 日志
```

### 查看 DE 是否发送消息

```
F12 → Console → 保持 top 上下文
→ 找 [L7 Bridge] postMessage 日志
```

### 验证 l7Config 是否持久化

```javascript
// F12 Console (top上下文)
const frames = document.querySelectorAll('iframe[data-l7-map]');
frames.forEach((f) => console.log(f.id));
```

### 重新配置联动

1. 编辑模式 → 选中表格 → 联动设置
2. 勾选「网页组件」→ 添加联动依赖字段
3. 源字段选 DE 数据集字段，目标字段选 L7 数据集字段
4. 保存后自动保存画布
