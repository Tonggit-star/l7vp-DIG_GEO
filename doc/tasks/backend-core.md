# 后端核心层 任务清单

> **关联**: 概要设计 §5  
> **范围**: Model、Repository、Service、ApplicationAssembler  
> **路径**: `java-server/src/main/java/com/antv/l7vp/`

---

## 任务列表

### Phase 1: Model 层（实体类）

- [x] **BC-001** 改造 `model/Project.java`
- [x] **BC-002** 新增 `model/Dataset.java`
- [x] **BC-003** 新增 `model/DatasetColumn.java`
- [x] **BC-004** 新增 `model/DatasetRow.java`
- [x] **BC-005** 新增 `model/Layer.java`
- [x] **BC-006** 新增 `model/Widget.java`
- [x] **BC-007** 新增 `model/IconCategory.java`
- [x] **BC-008** 新增 `model/IconItem.java`
- [x] **BC-009** 新增 `model/TileConfig.java`

### Phase 2: Repository 层（数据访问）

- [x] **BC-010** 改造 `repository/ProjectRepository.java`
- [x] **BC-011** 新增 `repository/DatasetRepository.java`
- [x] **BC-012** 新增 `repository/DatasetColumnRepository.java`
- [x] **BC-013** 新增 `repository/DatasetRowRepository.java`
- [x] **BC-014** 新增 `repository/LayerRepository.java`
- [x] **BC-015** 新增 `repository/WidgetRepository.java`
- [x] **BC-016** 新增 `repository/IconCategoryRepository.java`
- [x] **BC-017** 新增 `repository/IconRepository.java`
- [x] **BC-018** 新增 `repository/TileConfigRepository.java`

### Phase 3: Service 层

- [x] **BC-019** 重写 `service/ProjectService.java`
- [x] **BC-020** 新增 `service/DatasetService.java`
- [x] **BC-021** 新增 `service/LayerService.java`
- [x] **BC-022** 新增 `service/WidgetService.java`
- [x] **BC-023** 新增 `service/IconService.java`
- [x] **BC-024** 新增 `service/TileConfigService.java`

### Phase 4: ApplicationAssembler（核心转换器）

- [x] **BC-025** 新增 `service/ApplicationAssembler.java` — assemble()
- [x] **BC-026** `ApplicationAssembler.disassemble()` — 多表写入

### Phase 5: DTO/辅助类

- [x] **BC-027** 新增 DTO 类 (CreateDatasetRequest, CreateDatasetResult, PagedRows, ColumnDef, IconLookupResult)
