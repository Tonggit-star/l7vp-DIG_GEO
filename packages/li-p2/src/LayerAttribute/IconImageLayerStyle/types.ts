import type { IconImageLayerOptions } from '@antv/l7-composite-layers';
import type { FieldSelectOptionType } from '../types';
import type { CommonProps } from '../types/common';

/**
 * 图标图层样式属性值（扩展字段用于图标库匹配）
 */
export type IconImageLayerStyleAttributeValue = Omit<IconImageLayerOptions, 'source'> & {
  /** 图标模式: fixed=固定图标, field=基于字段(库号+代号) */
  iconType?: 'fixed' | 'field';
  /** 库号字段名 — 从数据行中提取 library_code */
  iconLibraryField?: string;
  /** 代号字段名 — 从数据行中提取 code_name */
  iconCodeField?: string;
  /** 未匹配时的 fallback 图标 URL */
  fallbackIconUrl?: string;
  /**
   * 图标转向：按该字段的角度（0-360，从正北起顺时针）旋转图标，空=不旋转。
   *
   * 只存字段名（可持久化）；角度归一化与 L7 样式注入在 li-core-assets 的 IconLayer/ClusterLayer
   * Component 里做（见 layers/icon-rotation.ts）——因为归一化要用函数，而配置是 JSON 存不下来。
   *
   * 与 iconType（固定/基于字段）正交：两者都走同一个内层 L7 PointLayer，rotation 是独立于 shape
   * 的另一个样式通道，故固定图标与基于字段两种模式共用这同一份配置。
   */
  iconRotationField?: string;
};

/**
 * 组件类型定义
 */
export interface IconImageLayerStyleAttributeProps extends CommonProps {
  fieldList: FieldSelectOptionType[];
  initialValues: IconImageLayerStyleAttributeValue;
  onChange?: (values: IconImageLayerStyleAttributeValue) => void;
}
