import type { IconImageLayerStyleAttributeValue } from '@antv/li-p2';
import { iconImageLayerStyleConfigToFlat, iconImageLayerStyleFlatToConfig } from '@antv/li-p2';
import type { LayerRegisterForm, LayerRegisterFormProps, LayerRegisterFormResultType } from '@antv/li-sdk';
import getSchema from './schema';
/**
 * 表单数据格式转换，将结构化数据转换为表单的平铺结构
 */
const toValues = (config: LayerRegisterFormResultType<IconImageLayerStyleAttributeValue>) => {
  const { sourceConfig, visConfig } = config;
  const { parser } = sourceConfig;
  // coordinateType 优先从 visConfig 读取（支持 dms），兜底从 parser 推断
  const coordinateType = (visConfig as any)?.coordinateType
    || (sourceConfig.parser?.geometry ? 'geometry' : 'table');
  const pointCoordinate = parser?.geometry
    ? { geometry: parser.geometry }
    : { longitude: parser?.x, latitude: parser?.y };
  return {
    coordinateType,
    ...pointCoordinate,
    ...iconImageLayerStyleConfigToFlat(visConfig),
  };
};
/**
 * 表单数据格式转换，将表单的平铺数据结构转为结构化数据
 */
const fromValues = (values: Record<string, any>): LayerRegisterFormResultType<IconImageLayerStyleAttributeValue> => {
  const coordinateType = values.coordinateType || 'table';
  // dms 和 table 模式都使用 x/y parser（数据由 parserDataWithGeo 自动转换）
  const pointCoordinate = coordinateType === 'geometry'
    ? { geometry: values.geometry }
    : { x: values.longitude, y: values.latitude };
  const sourceConfig = {
    parser: {
      ...pointCoordinate,
    },
  };
  const visConfig = iconImageLayerStyleFlatToConfig(values);
  // 将 coordinateType 写入 visConfig 以便持久化
  (visConfig as any).coordinateType = coordinateType;
  return {
    sourceConfig,
    visConfig,
  };
};

export default (props: LayerRegisterFormProps): LayerRegisterForm<IconImageLayerStyleAttributeValue> => {
  // 属性面板表单的 Schema 定义，来自表单库 formily 的 Schema
  const schema = getSchema(props.datasetFields);
  return {
    schema,
    toValues,
    fromValues,
  };
};
