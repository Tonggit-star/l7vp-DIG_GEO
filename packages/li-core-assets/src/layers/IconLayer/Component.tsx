import type { IconImageLayerProps } from '@antv/larkmap';
import { IconImageLayer } from '@antv/larkmap';
import type { ImplementLayerProps } from '@antv/li-sdk';
import React, { useMemo } from 'react';
import { applyIconRotation } from '../icon-rotation';

export interface IconImageLayerWrapperProps extends IconImageLayerProps, ImplementLayerProps {
  /** 图标转向字段（visConfig.iconRotationField）：行内按该字段的角度旋转图标，空=不旋转 */
  iconRotationField?: string;
}

const IconImageLayerWrapper: React.FC<IconImageLayerWrapperProps> = (props) => {
  const { iconRotationField, iconStyle, source, ...rest } = props;

  // 角度归一化 + 注入 iconStyle.rotation；未配 iconRotationField 时原样返回（rotateSourceData 不产生新对象）
  const rotated = useMemo(
    () => applyIconRotation({ source, iconStyle, rotationField: iconRotationField }),
    [source, iconStyle, iconRotationField],
  );

  // 注意：iconRotationField 已被解构掉，不会再作为未知选项透传给 larkmap/L7 图层
  //
  // billboard:false 是 rotation 能生效的**前提**：L7 的 PointLayer 按 getModelType() 选点模型
  // （l7-layers/es/point/index.js:105），图标的名字在 scene 的 iconMap 里 → 命中
  // `iconMap.hasOwnProperty(shape) → 'image'`，而 point/models/image.js 的顶点着色器里
  // rotation/rotate_matrix 出现 0 次（只有 fill / fillImage / text 三个模型实现了逐要素旋转），
  // 于是图标永远按素材自身朝向画 → 表现为「全部朝北」，改数据、刷新页面都没用。
  // `layerType === 'fillImage' || billboard === false` 这一支在最前面，会走 fillImage（有 rotate_matrix）。
  // 代价：billboard=false 的图标是贴地平面渲染，地图俯仰(pitch≠0)时会跟着倾斜；正俯视时无差异。
  const rotationProps = iconRotationField ? { billboard: false } : {};
  return (
    <IconImageLayer
      key={`icon-rotation-${iconRotationField ?? ''}`}
      {...(rest as IconImageLayerProps)}
      {...(rotationProps as IconImageLayerProps)}
      source={rotated.source}
      iconStyle={rotated.iconStyle}
    />
  );
};
export default IconImageLayerWrapper;
