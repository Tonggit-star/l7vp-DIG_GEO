# 前端-瓦片配置 任务清单

> **关联**: 需求文档 §4 | 概要设计 §6.5  
> **路径**: `website/src/components/TileConfigModal/`

---

## 任务列表

### 类型定义

- [x] **FTC-001** 新增 `website/src/types/tile-config.ts`

### API Service

- [x] **FTC-002** 新增 `website/src/services/tile-config.ts`

### TileConfigModal 弹窗

- [x] **FTC-003** 新增 `website/src/components/TileConfigModal/index.tsx`

### 集成

- [x] **FTC-004** 在项目列表页添加"瓦片配置"按钮

### 瓦片加载优先级

- [x] **FTC-005** 改造 `website/src/pages/Project/herlper.ts` — 优先级链: DB → window.L7VP_CONFIG → 高德兜底
- [x] **FTC-006** Builder 新建项目 basemap 初始化 — 共享 helper.ts 逻辑
