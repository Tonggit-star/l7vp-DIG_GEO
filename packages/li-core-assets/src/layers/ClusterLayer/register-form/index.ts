import type { BubbleLayerStyleAttributeValue, IconImageLayerStyleAttributeValue } from '@antv/li-p2';
import {
  bubbleLayerStyleConfigToFlat,
  bubbleLayerStyleFlatToConfig,
  iconImageLayerStyleConfigToFlat,
  iconImageLayerStyleFlatToConfig,
} from '@antv/li-p2';
import type { LayerRegisterForm, LayerRegisterFormProps, LayerRegisterFormResultType } from '@antv/li-sdk';
import getSchema from './schema';

/**
 * 聚合图层的样式支持「点标记(renderer)」两种模式，各自用对应 li-p2 的样式类型与 flat 转换：
 *  - dot  → BubbleLayerStyle（数量圆，默认）
 *  - icon → IconImageLayerStyle（整层图标，固定/基于字段；radius/label 等共享语义字段在 form 中同名）
 * 两套配置彼此独立存储（visConfig.renderer 区分），切换模式会以对方模式已有值重建样式
 * （与「切换可视化类型重置样式」的平台既有行为一致）。
 */
const getRenderer = (visConfig: any): 'dot' | 'icon' => (visConfig?.renderer === 'icon' ? 'icon' : 'dot');

const toValues = (config: LayerRegisterFormResultType<BubbleLayerStyleAttributeValue | IconImageLayerStyleAttributeValue>) => {
  const { sourceConfig, visConfig } = config as any;
  const { parser } = sourceConfig;
  const renderer = getRenderer(visConfig);
  const coordinateType = visConfig?.coordinateType || (sourceConfig.parser?.geometry ? 'geometry' : 'table');
  const pointCoordinate = parser?.geometry
    ? { geometry: parser.geometry }
    : { longitude: parser?.x, latitude: parser?.y };

  const styleFlat =
    renderer === 'icon'
      ? iconImageLayerStyleConfigToFlat(visConfig as IconImageLayerStyleAttributeValue)
      : bubbleLayerStyleConfigToFlat(visConfig as BubbleLayerStyleAttributeValue);

  return {
    coordinateType,
    renderer,
    ...pointCoordinate,
    ...styleFlat,
  };
};

const fromValues = (values: Record<string, any>): LayerRegisterFormResultType => {
  const renderer = values.renderer === 'icon' ? 'icon' : 'dot';
  const coordinateType = values.coordinateType || 'table';
  // coordinateType==='geometry' 分支保留作历史数据防御（UI 坐标面板已只提供 table/dms）
  const pointCoordinate = coordinateType === 'geometry'
    ? { geometry: values.geometry }
    : { x: values.longitude, y: values.latitude };
  const sourceConfig = {
    parser: {
      ...pointCoordinate,
    },
  };

  let visConfig: Record<string, any>;
  if (renderer === 'icon') {
    visConfig = iconImageLayerStyleFlatToConfig(values);
    // 首次切到图标（label/radius 面板尚未展开过 → flat 里没有这些键）时预填聚合语义：
    // 数量文本 + 按 point_count 变大的图标，保证默认可用
    if (values.labelField === undefined) {
      visConfig.label = {
        field: 'point_count_abbreviated',
        visible: true,
        style: {
          fill: '#3b3b3b',
          fontSize: 13,
          textAnchor: 'center' as const,
          textOffset: [0, 16] as [number, number],
        },
      };
    }
    if (values.radiusField === undefined && (values.radius === undefined || values.radius === '')) {
      visConfig.radius = { field: 'point_count', value: [16, 46] };
    }
  } else {
    visConfig = bubbleLayerStyleFlatToConfig(values) as unknown as Record<string, any>;
  }

  (visConfig as any).renderer = renderer;
  (visConfig as any).coordinateType = coordinateType;

  return {
    sourceConfig,
    visConfig,
  };
};

export default (props: LayerRegisterFormProps): LayerRegisterForm => {
  // 属性面板表单的 Schema 定义，来自表单库 formily 的 Schema
  const schema = getSchema(props.datasetFields);
  return {
    schema,
    toValues,
    fromValues,
  };
};
