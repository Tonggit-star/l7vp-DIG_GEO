# 前端-图标库管理 任务清单

> **关联**: 需求文档 §3 | 概要设计 §6.4  
> **路径**: `website/src/components/IconLibraryModal/`

---

## 任务列表

### 类型定义

- [x] **FIL-001** 更新 `website/src/types/icon.ts` — IconCategory, IconItem 类型
- [x] **FIL-002** 重写 `website/src/services/icon.ts` — 含分类/图标CRUD + lookup API

### IconLibraryModal 主组件

- [x] **FIL-003** 新增 `website/src/components/IconLibraryModal/index.tsx` — Modal (900px), 左右分栏
- [x] **FIL-004** 状态管理 — useState: categories, selectedCategoryId, icons, loading, uploading

### 子组件（内嵌实现）

- [x] **FIL-005** CategoryPanel — 分类列表 + 新增/重命名/删除
- [x] **FIL-006** IconGrid — 网格布局 + 上传按钮
- [x] **FIL-007** IconCard — 图标预览 + 库号/代号编辑
- [x] **FIL-008** UploadProgress — loading 状态指示

### 集成

- [x] **FIL-009** Project页面添加"图标库"按钮 (PictureOutlined)
