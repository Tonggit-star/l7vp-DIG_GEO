import type { DatasetSchema, LayerSchema } from '@antv/li-sdk';
import { getUniqueId } from '@antv/li-sdk';
import type AppService from '../../../services/app-service';
import { EditorDataset } from '../../../services/editor-dataset-manager';
import type { AddDataset } from '../../../types';
import { isValidLayer, requestIdleCallback, validateDataset } from '../../../utils';
import { getLayersBounds } from '../../../utils/spec';

export const getAddDatasetsSchema = (datasets: AddDataset[], autoCreateLayers?: boolean): DatasetSchema[] => {
  return datasets.map((dataset) => {
    let datasetSchema: DatasetSchema;
    const id = dataset.id ?? getUniqueId();
    const metadata = autoCreateLayers ? { ...dataset.metadata, _autoCreateLayers: autoCreateLayers } : dataset.metadata;

    if (dataset.type === 'local') {
      datasetSchema = {
        ...dataset,
        id,
        metadata,
        // Keep explicitly provided columns. A local dataset normally derives columns from its data rows,
        // but streaming datasets (metadata.stream=true) intentionally carry no rows (data never persisted),
        // so their columns are ONLY the ones supplied here; blanking them would drop lng/lat and make it
        // impossible to auto/manually create a layer. When no columns are given, keep the empty-array semantics.
        columns: Array.isArray(dataset.columns) && dataset.columns.length ? dataset.columns : [],
      };
    } else {
      datasetSchema = {
        ...dataset,
        id,
        metadata,
      };
    }

    return validateDataset(datasetSchema);
  });
};

export const getAddLayersSchema = (layers: LayerSchema[], appService: AppService): LayerSchema[] => {
  return layers.filter(isValidLayer).map((layer) => {
    const implementLayer = appService.getImplementLayer(layer.type);
    const visConfig = { ...implementLayer?.defaultVisConfig, ...layer.visConfig };
    return { ...layer, visConfig };
  });
};

export const fitMapBoundsWithAddLayers = (layers: LayerSchema[], datasets: DatasetSchema[], appService: AppService) => {
  if (layers.length === 0) return;

  const editorDatasets = datasets.map((d) => new EditorDataset(d, appService));
  const bounds = getLayersBounds(layers, editorDatasets);

  // 放到下一帧，图层加载到地图上渲染耗时，影响 fitBounds 流程度
  if (bounds) {
    requestIdleCallback(() => {
      appService.fitMapBounds(bounds);
    });
  }
};
