import type { IconImageLayerStyleAttributeValue } from './types';

/**
 * 平铺数据转图层样式数据
 * 将表单的平铺数据转为图层样式的数据结构
 * */
export const iconImageLayerStyleFlatToConfig = (style: Record<string, any>) => {
  const { iconType, iconLibraryField, iconCodeField, iconImg, iconImgScale, iconField, iconImgFallback } = style;

  let icon: any = iconImg;
  let fallbackIconUrl: string | undefined = undefined;

  if (iconType === 'field') {
    // === 基于字段模式：库号+代号（不改动） ===
    // 使用 _iconUrl 预计算字段（由 useLayerProps 中的 enrichIconUrls 填充）
    icon = '_iconUrl';
    fallbackIconUrl = iconImgFallback || undefined;
  } else if (iconField) {
    // === 固定图标模式 + 基于字段：字段值映射图标（原始 master 逻辑） ===
    const { domain, range, unknown } = iconImgScale || {};
    const safeDomain = domain || [];
    const safeRange = range || [];
    // 构建 scale：unknown 是必须的，没有的话 L7 会把未匹配数据渲染为蓝色默认圆点
    // 用 range 中第一个图标作为 fallback，若 range 为空则用 default-icon.svg
    const scaleUnknown = unknown || safeRange[0] || '/icons/default-icon.svg';
    icon = {
      field: iconField,
      value: safeRange,
      scale: {
        type: 'cat' as const,
        domain: safeDomain,
        unknown: scaleUnknown,
      },
    };
    fallbackIconUrl = undefined;
  } else {
    // === 固定图标模式 + 无基于字段：单一固定图标 ===
    fallbackIconUrl = undefined;
  }

  // 构建 iconAtlas
  let iconAtlas: Record<string, string> = {};
  if (iconType === 'fixed') {
    if (iconField && iconImgScale?.range) {
      // field-based 模式：从 range 构建 iconAtlas
      (iconImgScale.range as string[]).forEach((iconId: string) => {
        if (iconId) iconAtlas[iconId] = iconId;
      });
      // 保证 scale.unknown 对应的 URL 在 atlas 中有条目（与 scale 中的 unknown 值一致）
      const fallbackUrl = iconImgScale.unknown || iconImgScale.range?.[0] || '/icons/default-icon.svg';
      if (fallbackUrl && !iconAtlas[fallbackUrl]) {
        iconAtlas[fallbackUrl] = fallbackUrl;
      }
    } else if (iconImg) {
      // 单一固定图标
      iconAtlas = { [iconImg]: iconImg };
    }
  }
  // field 模式下 iconAtlas 由 enrichIconUrls 动态构建，不需要预填

  const styleConfig: IconImageLayerStyleAttributeValue = {
    iconAtlas,
    icon,
    iconType: iconType || 'fixed',
    iconLibraryField: iconType === 'field' ? iconLibraryField : undefined,
    iconCodeField: iconType === 'field' ? iconCodeField : undefined,
    fallbackIconUrl,
    // 图标转向：与 iconType 正交，两种模式共用；只存字段名，运行时注入见 li-core-assets/icon-rotation.ts
    iconRotationField: style.iconRotationField || undefined,
    fillColor: style.fillColor,
    radius: style.radiusField
      ? {
          field: style.radiusField,
          value: style.radiusRange,
        }
      : style.radius,
    iconStyle: {
      opacity: style.fillOpacity,
    },
    label: {
      field: style.labelField,
      visible: Boolean(style.labelField),
      style: {
        fill: style.labelColor,
        fontSize: style.labelFontSize,
        textAnchor: style.labelTextAnchor,
        textOffset: style.labelTextOffset,
        stroke: style.labelStroke,
        strokeWidth: style.labelStrokeWidth,
      },
    },
    minZoom: style.zoom?.[0],
    maxZoom: style.zoom?.[1],
    blend: style.blend,
  };

  return styleConfig;
};
/**
 * 图层样式数据转平铺数据
 * 将图层样式的数据结构转为表单的平铺数据
 * */
export const iconImageLayerStyleConfigToFlat = (styleConfig: Partial<IconImageLayerStyleAttributeValue>) => {
  const { radius, label, icon, iconStyle, minZoom = 0, maxZoom = 24, blend, iconType, iconLibraryField, iconCodeField, fallbackIconUrl, iconRotationField } = styleConfig || {};

  let iconImgScale: any = undefined;
  let iconFieldValue: string | undefined = undefined;
  let iconImgValue: string | undefined;

  if (iconType === 'field') {
    // === 基于字段模式（库号+代号）：不改动 ===
    iconImgValue = undefined;
    iconFieldValue = undefined;
    iconImgScale = undefined;
  } else if (typeof icon === 'object' && icon?.value) {
    // === 固定图标模式 + 基于字段：还原 iconField 和 iconImgScale ===
    iconImgValue = undefined;
    iconImgScale = {
      range: icon.value,
      domain: icon.scale?.domain || [],
      unknown: icon.scale?.unknown || undefined,
    };
    iconFieldValue = Array.isArray(icon.field) ? icon.field[0] : icon.field;
  } else {
    // === 固定图标模式 + 无基于字段：单一固定图标 ===
    iconImgValue = typeof icon === 'string' ? icon : undefined;
    iconImgScale = undefined;
    iconFieldValue = undefined;
  }

  const config = {
    iconType: iconType || 'fixed',
    iconImg: iconImgValue,
    iconImgScale,
    iconField: iconFieldValue,
    iconImgFallback: iconType === 'field' ? fallbackIconUrl : undefined,
    iconLibraryField: iconType === 'field' ? iconLibraryField : undefined,
    iconCodeField: iconType === 'field' ? iconCodeField : undefined,
    iconRotationField,
    fillOpacity: iconStyle?.opacity,
    radiusField: typeof radius === 'object' && !Array.isArray(radius) ? radius?.field : undefined,
    radiusRange: typeof radius === 'object' && !Array.isArray(radius) ? radius?.value : undefined,
    radius: typeof radius !== 'object' ? radius : undefined,
    labelField: label?.field,
    labelColor: label?.style?.fill,
    labelFontSize: label?.style?.fontSize,
    labelTextAnchor: label?.style?.textAnchor,
    labelTextOffset: label?.style?.textOffset,
    labelStroke: label?.style?.stroke,
    labelStrokeWidth: label?.style?.strokeWidth,
    zoom: [minZoom, maxZoom],
    blend,
  };

  return config;
};
