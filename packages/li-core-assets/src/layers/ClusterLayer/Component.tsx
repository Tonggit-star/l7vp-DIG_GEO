import type { IconImageLayerProps } from '@antv/larkmap';
import { BubbleLayer, IconImageLayer } from '@antv/larkmap';
import type { ImplementLayerProps } from '@antv/li-sdk';
import React, { useMemo } from 'react';
import { rotateIconStyle, rotateSourceData } from '../icon-rotation';

export interface ClusterLayerWrapperProps extends ImplementLayerProps {
  /** 运行时注入的普通数据源（含 parser/data/sourceConfig 其余顶层键），见 index.tsx 说明 */
  source?: any;
  /** 渲染类型：dot=圆点(默认，Bubble 视觉)；icon=整层图标(IconImageLayer) */
  renderer?: 'dot' | 'icon';
  /** 以下与「图标图层」一致的 icon 配置（仅 renderer==='icon' 时使用） */
  iconType?: 'fixed' | 'field';
  icon?: any;
  iconAtlas?: Record<string, string>;
  // rotation 不在 li-p2 的 PointLayerStyleOptions 类型里，但 L7 点图层确实支持（enableShaderEncodeStyles 含 'rotation'）
  iconStyle?: { opacity?: number; rotation?: { field: string } };
  iconLibraryField?: string;
  iconCodeField?: string;
  fallbackIconUrl?: string;
  /** 图标转向字段（visConfig.iconRotationField）：行内按该字段的角度旋转图标，空=不旋转 */
  iconRotationField?: string;
  /** 其余 visConfig/larkmap props 透传 */
  [key: string]: any;
}

// L7 source 级聚合(cluster)参数：radius=聚合半径(px)，maxZoom 语义是"查询 zoom≥该值不再细分 → 出单点"，
// 取 20 意味着地图约 21 级裂到每个单点（可上调至 22-24 让簇更早裂解）。
// clusterOptions 只认 radius/minZoom/maxZoom，勿把 maxClusterRadius/extent 等塞进来（L7 不转发）。
const CLUSTER_OPTIONS = { radius: 40, maxZoom: 20 };

/**
 * 图标渲染的收敛逻辑：
 *
 * 历史配置/表单路径可能把 `icon`(shape 名) 存成与 `iconAtlas` 不一致的形式（例如存成不带扩展名的
 * 图标 URL，而图集 key/URL 带扩展名；或 field 模式下聚合节点没有 _iconUrl）。IconImageLayer 依赖
 * `icon` 命中 `iconAtlas` 里已注册(scene.addImage)的 key，命中不了就回退成 L7 默认圆点——表现为
 * 「切了图标却仍看到圆点」。这里统一把会用到的 shape 名注册进图集、并给 field 模式补 unknown，
 * 保证任何点（含 supercluster 聚合节点）都能落到一个真实可加载的图标 URL。
 */
const DEFAULT_FALLBACK_ICON = '/icons/default-icon.svg';

const normalizeIconLayerProps = (opts: {
  iconType?: 'fixed' | 'field';
  icon?: any;
  iconAtlas?: Record<string, string>;
  fallbackIconUrl?: string;
}): { icon: any; iconAtlas: Record<string, string> } => {
  const { iconType, icon: iconProp, iconAtlas = {}, fallbackIconUrl } = opts;
  const keys = Object.keys(iconAtlas).filter(Boolean);
  const values = Array.from(new Set(Object.values(iconAtlas).filter((v): v is string => typeof v === 'string' && !!v)));

  /** 名称到「真实可加载 URL」的解析：优先图集内精确命中，其次前缀/后缀近似（如丢 .png），否则原样 */
  const resolveReal = (name?: string): string | undefined => {
    if (!name) return undefined;
    if (iconAtlas[name]) return iconAtlas[name];
    if (values.includes(name)) return name;
    const fuzzy = keys.find((k) => k.startsWith(name) || (name.length > 3 && k.endsWith(name)));
    if (fuzzy) return iconAtlas[fuzzy];
    return undefined;
  };

  // 待渲染的图集副本：凡是要作为 shape 使用的名字，确保 key=名字、value=可加载 URL。
  const atlas: Record<string, string> = { ...iconAtlas };
  const register = (name?: string, url?: string) => {
    if (name && url && !atlas[name]) atlas[name] = url;
  };

  const isFixedString = iconType !== 'field' && typeof iconProp === 'string' && !!iconProp;
  const isUrlField = iconType === 'field' || (typeof iconProp === 'string' && iconProp === '_iconUrl');
  const isMapping = !!iconProp && typeof iconProp === 'object';

  let icon: any = iconProp;

  if (isFixedString) {
    // 单一固定图标：shape=该 URL；图集里补一条 key=shape → 真实可加载 URL，保证命中。
    const real = resolveReal(iconProp) || iconProp;
    register(iconProp, real);
    if (real !== iconProp) register(real, real);
    icon = iconProp;
  } else if (isUrlField) {
    // 基于字段(_iconUrl)：叶子带各 URL，聚合节点无 _iconUrl → scale.unknown 兜底。
    // unknown 必须是图集里真实可加载的 URL（fallback 或任取一个叶子图标），不能是 404 的缺省。
    const fallbackReal = resolveReal(fallbackIconUrl) || values[0];
    const unknown = fallbackReal || DEFAULT_FALLBACK_ICON;
    register(unknown, unknown);
    const domain = keys.length ? keys : unknown;
    icon = {
      field: '_iconUrl',
      value: keys.length ? keys : [unknown],
      scale: {
        type: 'cat' as const,
        domain,
        unknown,
      },
    };
  } else if (isMapping) {
    // 固定图标 + 基于字段映射（iconField）：沿用映射对象，但把 range/unknown 收敛到可加载 URL 并注册。
    const valueList = Array.isArray(iconProp.value) ? iconProp.value : [];
    const resolvedValues = valueList.map((v: string) => resolveReal(v) || v);
    const resolvedUnknown = resolveReal(iconProp?.scale?.unknown) || resolvedValues[0] || DEFAULT_FALLBACK_ICON;
    resolvedValues.forEach((v: string) => register(v, v));
    register(resolvedUnknown, resolvedUnknown);
    icon = {
      field: iconProp.field,
      value: resolvedValues,
      scale: {
        type: 'cat' as const,
        domain: iconProp?.scale?.domain || resolvedValues,
        unknown: resolvedUnknown,
      },
    };
  }

  return { icon, iconAtlas: atlas };
};

/**
 * 聚合图层(点) ClusterLayer
 *
 * 把运行时注入的普通 source（{ data, parser, ...sourceConfig 其余顶层键 }）包一层 cluster:true 后
 * 继续以普通对象交给 larkmap BubbleLayer / IconImageLayer。走 larkmap changeData → L7 Source.setData
 * 的常规数据链路，因此流式 replace 时 L7 会自动全量重聚合，无需自持 L7 Source 实例。
 *
 * 视觉双模式（visConfig.renderer 切换，默认 dot）：
 *  - dot：BubbleLayer，聚合圆按 point_count 映射大小/填充 + 数量文本；
 *  - icon：IconImageLayer 整层图标（聚合节点=大图标+数量文本，单点=小图标），固定图标 / 基于字段
 *    两种模式与「图标图层」一致。
 *
 * 图标大小/文本的 point_count 字段映射由 defaultVisConfig/样式面板提供，此处不做兜底改写，
 * 仅保证「样式面板所见 = 渲染所得」；Component 的唯一特判是 normalizeIconLayerProps，
 * 让聚合节点（supercluster 合成、无 _iconUrl / shape 未命中图集）落到一个真实可加载的图标。
 */
const ClusterLayerWrapper: React.FC<ClusterLayerWrapperProps> = (props) => {
  const {
    source,
    renderer,
    iconType,
    icon: iconProp,
    iconAtlas,
    iconStyle,
    iconLibraryField,
    iconCodeField,
    fallbackIconUrl,
    iconRotationField,
    ...rest
  } = props;

  const clusterSource = useMemo(() => {
    if (!source) return undefined;
    // 图标转向：给每行补归一化后的 __iconRotation（未配转向字段时原样返回，不产生新对象）
    return {
      ...rotateSourceData(source, iconRotationField),
      cluster: true,
      // 预留覆盖口：若 source.clusterOptions 已带（未来表单化/历史数据），以其为准，否则用默认
      clusterOptions: { ...CLUSTER_OPTIONS, ...(source as any)?.clusterOptions },
    };
  }, [source, iconRotationField]);

  const isIconMode = renderer === 'icon';

  // 圆点(dot)模式文本可读性兜底：历史配置/默认值可能没有描边，数量数字在深色底图或深色聚合圆上
  // 看不清（表现为「默认圆点时统计数字不显示」）。仅当 label.style 完全没有描边定义时补一层白描边，
  // 已显式设置过 stroke/strokeWidth 的配置一律尊重、不覆盖（保持样式面板所见即所得）。
  const dotLabel = (() => {
    if (isIconMode) return undefined;
    const lb = rest.label as any;
    if (!lb || typeof lb !== 'object') return undefined;
    const style = lb.style;
    if (!style || typeof style !== 'object') return undefined;
    const hasStrokeDef =
      (style.stroke != null && String(style.stroke) !== '' && String(style.stroke) !== 'transparent') ||
      (style.strokeWidth != null && Number(style.strokeWidth) > 0);
    if (hasStrokeDef) return undefined;
    return { ...lb, style: { ...style, stroke: '#ffffff', strokeWidth: 1 } };
  })();

  // 图标是否可渲染：固定模式需有 icon 值；基于字段模式需有库号/代号字段（li-sdk enrichIconUrls 据此注入 _iconUrl）
  const iconUsable = useMemo(() => {
    if (!isIconMode) return false;
    if (iconType === 'field') return Boolean(iconLibraryField && iconCodeField);
    return Boolean(iconProp);
  }, [isIconMode, iconType, iconProp, iconLibraryField, iconCodeField]);

  const iconExtraProps = useMemo(() => {
    if (!isIconMode || !iconUsable) return undefined;
    const normalized = normalizeIconLayerProps({ iconType, icon: iconProp, iconAtlas, fallbackIconUrl });
    const effOpacity = iconStyle?.opacity ?? (rest as any).opacity ?? 1;
    return {
      icon: normalized.icon,
      iconAtlas: normalized.iconAtlas,
      // 图标转向：注入 iconStyle.rotation = { field: '__iconRotation' }（未配时原样返回）
      iconStyle: rotateIconStyle({ ...(iconStyle || {}), opacity: effOpacity }, iconRotationField),
    };
  }, [isIconMode, iconUsable, iconType, iconProp, iconAtlas, iconStyle, fallbackIconUrl, iconRotationField, rest]);

  if (!clusterSource) return null;

  // 图标模式配置可用 → IconImageLayer 整层图标；否则回退 BubbleLayer（含配置未完成/固定模式未选图标）
  if (isIconMode && iconUsable && iconExtraProps) {
    return (
      <IconImageLayer
        // 同 IconLayer/Component.tsx：图标默认走 image 点模型，而逐要素 rotation 只有
        // fill/fillImage/text 实现了 → 必须 billboard:false 把模型切到 fillImage，rotation 才会生效。
        // key 随 iconRotationField 变化，保证面板里改字段时图层重建（模型重建才会重新编译着色器）。
        key={`cluster-icon-rotation-${iconRotationField ?? ''}`}
        {...(rest as IconImageLayerProps)}
        {...iconExtraProps}
        {...((iconRotationField ? { billboard: false } : {}) as IconImageLayerProps)}
        source={clusterSource as any}
      />
    );
  }

  if (isIconMode) {
    console.warn('[ClusterLayer] renderer=icon 但图标配置不完整，已回退为圆点渲染。请在样式面板选固定图标或配置库号/代号字段。');
  }

  return (
    <BubbleLayer
      {...rest}
      {...(dotLabel ? { label: dotLabel } : {})}
      source={clusterSource as any}
    />
  );
};

export default ClusterLayerWrapper;
