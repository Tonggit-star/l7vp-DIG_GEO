import type { ImplementEditorWidget } from '../types';
import {
  DatasetPreview,
  DatasetsPanel,
  Export,
  FetchDataset,
  FiltersPanel,
  Folder,
  LayersPanel,
  MapSetting,
  TilesetsDataset,
  UploadDataset,
  WidgetsPanel,
  ZhongtaiApiDataset,
  ZhongtaiTableDataset,
} from '../widgets';

export const Registry_Default_Editor_Widgets: ImplementEditorWidget[] = [
  DatasetsPanel,
  FiltersPanel,
  LayersPanel,
  WidgetsPanel,
  UploadDataset,
  TilesetsDataset,
  FetchDataset,
  ZhongtaiTableDataset,
  ZhongtaiApiDataset,
  DatasetPreview,
  MapSetting,
  Export,
  Folder,
];
