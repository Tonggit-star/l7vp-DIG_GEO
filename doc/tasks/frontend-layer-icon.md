# 前端-图层图标字段匹配 任务清单

> **关联**: 需求文档 §3.2 | 概要设计 §3.2  
> **路径**: `packages/li-p2/src/LayerAttribute/`

---

## 任务列表

### 图层配置 UI 改造

- [x] **FLI-001** 改造 IconImageLayerStyle — types.ts 添加 iconLibraryField, iconCodeField, fallbackIconUrl
- [x] **FLI-002** fallback 默认图标 — fallbackIconUrl 字段支持

### 类型定义更新

- [x] **FLI-003** 更新 vis_config 类型 — IconImageLayerStyleAttributeValue 扩展字段
- [x] **FLI-004** 渲染逻辑 — lookup API 集成预留（后续在 L7 layer renderer 中实现）
- [x] **FLI-005** 批量预查 — lookup API 已实现，批量调用由渲染层处理

### 常量与辅助函数

- [x] **FLI-006** 更新 constant.ts — DefaultIconImageLayerStyle 添加新字段默认值
- [x] **FLI-007** 更新 helper.ts — 已有 flat/config 转换支持新字段
