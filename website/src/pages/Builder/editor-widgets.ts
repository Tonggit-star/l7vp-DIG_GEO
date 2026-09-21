import type { ImplementEditorWidget } from '@antv/li-editor';
import {
  DatabaseDataset,
  DatasetsPanel,
  FetchDataset,
  FiltersPanel,
  Folder,
  LayersPanel,
  MapSetting,
  UploadDataset,
  WidgetsPanel,
  ZhongtaiApiDataset,
  ZhongtaiTableDataset,
} from '@antv/li-editor/dist/esm/widgets';

import AgentConfigBridge from './widgets/AgentConfigBridge';
import BackToProjects from './widgets/BackToProjects';
import DatasetPreview from './widgets/DatasetPreview';
import Export from './widgets/Export';
import Screenshot from './widgets/Screenshot';
import Share from './widgets/Share';
import StreamDataset from './widgets/StreamDataset';

export const DefaultEditorWidgets: ImplementEditorWidget[] = [
  DatasetsPanel,
  FiltersPanel,
  LayersPanel,
  WidgetsPanel,
  // UploadDataset, 为了排序，放到下面了
  // MapSetting,
];

// 自定义编辑器的控件
// 注意：AgentConfigBridge 只进 editorWidgetsWithBuilder——它会把智能体指令写进项目配置，
// 只该在 Builder 页存在；预览/模板页（Preview/Template.tsx 用的是 editorWidgets）不该有它。
export const editorWidgets: ImplementEditorWidget[] = [DatasetPreview, Export];
export const editorWidgetsWithBuilder: ImplementEditorWidget[] = [
  AgentConfigBridge,
  DatasetPreview,
  ZhongtaiApiDataset,
  ZhongtaiTableDataset,
  DatabaseDataset,
  UploadDataset,
  StreamDataset,
  Folder,
  FetchDataset,
  // TilesetsDataset,
  Screenshot,
  MapSetting,
  Share,
  BackToProjects,
];
