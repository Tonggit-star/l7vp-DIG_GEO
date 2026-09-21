# 前端-项目管理页面 任务清单

> **关联**: 概要设计 §6.6  
> **路径**: `website/src/pages/Project/`

---

## 任务列表

### 页面按钮布局

- [x] **FPP-001** 在 `website/src/pages/Project/index.tsx` 右上角新增"瓦片配置"按钮

### 新建项目改造

- [x] **FPP-002** 改造新建项目按钮行为 — `window.open('/new')` → `history.push('/new')`

### API 调用适配

- [x] **FPP-003** 改造 API 调用适配新接口 — ProjectController 返回格式已兼容
