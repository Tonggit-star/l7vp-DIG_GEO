import type { FieldSelectOptionType } from '@antv/li-p2';
import getBubbleStyleSchema from '@antv/li-p2/dist/esm/LayerAttribute/BubbleLayerStyle/schema';
import getIconStyleSchema from '@antv/li-p2/dist/esm/LayerAttribute/IconImageLayerStyle/schema';
import getCoordinateSchema from './coordinate-schema';

/**
 * L7 cluster 聚合数据里出现的合成字段（非数据集真实列）：
 *  - 聚合节点(cluster)带 point_count / point_count_abbreviated，叶子带 point_count=1
 * 注入到两套样式 schema 的 fieldList，使「基于字段」的填充/半径/文本标注下拉能选到它们。
 * 仅样式 schema 注入；坐标 schema 用原始字段（避免聚合字段被误选成经纬度）。
 */
const CLUSTER_FIELDS: FieldSelectOptionType[] = [
  { value: 'point_count', label: '聚合数量', type: 'number', typeName: '数值', typeColor: 'green' },
  { value: 'point_count_abbreviated', label: '聚合数量(缩写)', type: 'number', typeName: '数值', typeColor: 'green' },
];
const CLUSTER_FIELD_VALUES = CLUSTER_FIELDS.map((f) => f.value);

const withClusterFields = (fieldList: FieldSelectOptionType[]): FieldSelectOptionType[] => {
  const list = [...fieldList];
  CLUSTER_FIELDS.forEach((f) => {
    if (!list.some((x) => x.value === f.value)) {
      list.push(f);
    }
  });
  return list;
};

/** 依据「点标记(renderer)」控制某组样式面板的显示：display:none 隐藏但仍保留字段值（来回切不丢） */
const rendererReaction = (target: 'dot' | 'icon') => [
  {
    dependencies: ['renderer'],
    fulfill: {
      state: {
        display: `{{ $deps[0] === "${target}" ? "visible" : "none" }}`,
      },
    },
  },
];

const withRendererVisibility = (properties: Record<string, any>, target: 'dot' | 'icon') => {
  return Object.fromEntries(
    Object.entries(properties).map(([key, node]) => {
      const next = { ...(node as Record<string, any>) };
      const reactions = Array.isArray(next['x-reactions']) ? [...next['x-reactions']] : [];
      next['x-reactions'] = [...reactions, ...rendererReaction(target)];
      return [key, next];
    }),
  );
};

/** 深搜 schema 对象，把命中的字段默认值设为指定值（不依赖 collapse 层级；已有的 default 一律覆盖以贴合聚合语义） */
const setDefaultsDeep = (node: any, defaults: Record<string, unknown>): void => {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return;
  Object.keys(node).forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(defaults, key) && node[key] && typeof node[key] === 'object' && !Array.isArray(node[key])) {
      // node[key] 是该字段的 schema 节点
      node[key] = { ...node[key], default: defaults[key] };
      setDefaultsDeep(node[key], defaults);
    } else {
      setDefaultsDeep(node[key], defaults);
    }
  });
};

/** 图标图层的库号/代号/图标映射/转向字段选择器不应出现 point_count 等合成字段（它们是聚合算出来的，不是数据列） */
const removeClusterFieldsFromSelectors = (node: any): any => {
  if (!node || typeof node !== 'object') return node;
  if (Array.isArray(node)) {
    return node
      .map(removeClusterFieldsFromSelectors)
      .filter((item: any) => !(item && typeof item === 'object' && 'value' in item && CLUSTER_FIELD_VALUES.includes(item.value)));
  }
  const next: Record<string, any> = {};
  Object.keys(node).forEach((key) => {
    if (
      (key === 'iconLibraryField' || key === 'iconCodeField' || key === 'iconField' || key === 'iconRotationField') &&
      node[key] &&
      typeof node[key] === 'object' &&
      'enum' in node[key]
    ) {
      next[key] = { ...node[key], enum: removeClusterFieldsFromSelectors(node[key].enum) };
    } else if (node[key] && typeof node[key] === 'object') {
      next[key] = removeClusterFieldsFromSelectors(node[key]);
    } else {
      next[key] = node[key];
    }
  });
  return next;
};

/** icon 模式缺省就带聚合语义：数量文本 + 按 point_count 变大的图标 */
const ICON_STYLE_DEFAULTS: Record<string, unknown> = {
  iconType: 'fixed',
  fillOpacity: 1,
  labelField: 'point_count_abbreviated',
  labelColor: '#3b3b3b',
  labelFontSize: 13,
  labelTextAnchor: 'center',
  labelTextOffset: [0, 16],
  labelStroke: '#ffffff',
  labelStrokeWidth: 1,
  radiusField: 'point_count',
  radiusRange: [16, 46],
};

export default (fieldList: FieldSelectOptionType[]) => {
  const styleFields = withClusterFields(fieldList);
  const iconSchema = getIconStyleSchema({ fieldList: styleFields });

  // icon 样式默认值预填 + 库号/代号选择器剔除合成字段
  setDefaultsDeep(iconSchema.properties, ICON_STYLE_DEFAULTS);
  const iconProperties = removeClusterFieldsFromSelectors(iconSchema.properties);

  return {
    ...getCoordinateSchema(fieldList),
    // 「点标记」：圆点(默认) / 图标
    collapseItem_renderer: {
      type: 'void',
      'x-component': 'FormCollapse',
      'x-component-props': {
        ghost: true,
        destroyInactivePanel: false,
        defaultActiveKey: ['rendererPanel'],
      },
      properties: {
        rendererPanel: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': {
            header: '显示类型',
          },
          properties: {
            renderer: {
              type: 'string',
              title: '点标记',
              default: 'dot',
              'x-decorator': 'FormItem',
              'x-component': 'Radio.Group',
              enum: [
                { label: '圆点', value: 'dot' },
                { label: '图标', value: 'icon' },
              ],
            },
          },
        },
      },
    },
    // 圆点模式 = Bubble 样式（数量圆）
    ...withRendererVisibility(getBubbleStyleSchema({ fieldList: styleFields }).properties, 'dot'),
    // 图标模式 = 图标图层样式（整层图标 + 数量文本）
    ...withRendererVisibility(iconProperties, 'icon'),
  };
};
