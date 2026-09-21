import type { HeatmapLayerStyleAttributeValue } from '@antv/li-p2';
import { heatmapLayerStyleConfigToFlat, heatmapLayerStyleFlatToConfig } from '@antv/li-p2';
import type { LayerRegisterForm, LayerRegisterFormProps, LayerRegisterFormResultType } from '@antv/li-sdk';
import getSchema from './schema';

/**
 * 表单数据格式转换，将结构化数据转换为表单的平铺结构
 */
const toValues = (config: LayerRegisterFormResultType<HeatmapLayerStyleAttributeValue>) => {
  const { sourceConfig, visConfig } = config;
  const { parser } = sourceConfig;
  const coordinateType = (visConfig as any)?.coordinateType
    || (sourceConfig.parser?.geometry ? 'geometry' : 'table');
  const pointCoordinate = parser?.geometry
    ? { geometry: parser.geometry }
    : { longitude: parser?.x, latitude: parser?.y };

  return {
    coordinateType,
    ...pointCoordinate,
    ...heatmapLayerStyleConfigToFlat(visConfig),
  };
};

const fromValues = (values: Record<string, any>): LayerRegisterFormResultType<HeatmapLayerStyleAttributeValue> => {
  const coordinateType = values.coordinateType || 'table';
  const pointCoordinate = coordinateType === 'geometry'
    ? { geometry: values.geometry }
    : { x: values.longitude, y: values.latitude };
  const sourceConfig = { parser: { ...pointCoordinate } };
  const visConfig = heatmapLayerStyleFlatToConfig(values);
  (visConfig as any).coordinateType = coordinateType;
  return { sourceConfig, visConfig };
};

export default (props: LayerRegisterFormProps): LayerRegisterForm<HeatmapLayerStyleAttributeValue> => {
  // 属性面板表单的 Schema 定义，来自表单库 formily 的 Schema
  const schema = getSchema(props.datasetFields);
  return {
    schema,
    toValues,
    fromValues,
  };
};
