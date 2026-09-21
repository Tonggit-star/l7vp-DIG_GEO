import type { PositionName } from '@antv/l7';
import type { WidgetRegisterForm, WidgetRegisterFormProps } from '@antv/li-sdk';

/**
 * 属性面板生产的数据类型定义
 */
export type Properties = {
  position?: PositionName;
  label?: string;
  layerId?: string;
  datasetId?: string;
  searchField?: string;
  zoomLevel?: number;
};

export default (props: WidgetRegisterFormProps): WidgetRegisterForm<Properties> => {
  // 过滤出气泡图层和图标图层
  const layers = (props.layers || []) as any[];
  const layerOptions: { label: string; value: string }[] = layers
    .filter((l: any) => {
      const type = l.type || l.metadata?.name || '';
      return type === 'BubbleLayer' || type === 'IconLayer';
    })
    .map((l: any) => ({
      label: l.metadata?.displayName || l.metadata?.name || l.name || String(l.id),
      value: String(l.id),
    }));

  // layerId → datasetId 映射
  const layerDsMap: Record<string, string> = {};
  for (const l of layers as any[]) {
    const dsId = l.sourceConfig?.datasetId;
    if (dsId) layerDsMap[String(l.id)] = dsId;
  }

  // 从所有数据集中收集 string 类型字段
  const fieldOptions: { label: string; value: string }[] = [];
  const seen = new Set<string>();
  for (const ds of (props.datasets || []) as any[]) {
    for (const col of (ds.columns || [])) {
      if ((col.type === 'string') && !seen.has(col.name)) {
        seen.add(col.name);
        const label = (col as any).displayName ? `${col.name}（${(col as any).displayName}）` : col.name;
        fieldOptions.push({ label, value: col.name });
      }
    }
  }
  console.log('[SearchControl.registerForm] fieldOptions with comment:', fieldOptions.filter((f: any) => f.label !== f.value).length,
    'samples:', fieldOptions.filter((f: any) => f.label !== f.value).slice(0, 3).map((f: any) => `${f.value}→${f.label}`));

  const schema = {
    label: {
      title: '搜索框名称',
      type: 'string',
      required: true,
      'x-decorator': 'FormItem',
      'x-component': 'Input',
      'x-component-props': {
        placeholder: '请输入搜索框显示名称',
      },
      default: '搜索定位',
    },

    layerId: {
      title: '目标图层',
      type: 'string',
      required: true,
      'x-decorator': 'FormItem',
      'x-component': 'Select',
      'x-component-props': {
        placeholder: '请选择气泡或图标图层',
        allowClear: true,
        options: layerOptions,
      },
    },

    datasetId: {
      type: 'string',
      'x-display': 'hidden',
      'x-reactions': [
        {
          dependencies: ['layerId'],
          fulfill: {
            run:
              `$form.setFieldState('datasetId', state => { state.value = (${JSON.stringify(layerDsMap)})[$deps[0]] || '' })`,
          },
        },
      ],
    },

    searchField: {
      title: '搜索字段',
      type: 'string',
      required: true,
      'x-decorator': 'FormItem',
      'x-component': 'Select',
      'x-component-props': {
        placeholder: '请选择搜索字段',
        allowClear: true,
        options: fieldOptions,
      },
    },

    zoomLevel: {
      title: '跳转缩放等级',
      type: 'number',
      'x-decorator': 'FormItem',
      'x-component': 'NumberPicker',
      'x-component-props': {
        min: 0,
        max: 24,
        placeholder: '默认 11 级',
      },
      default: 11,
    },

    position: {
      title: '放置方位',
      type: 'string',
      'x-decorator': 'FormItem',
      'x-component': 'ControlPositionSelect',
      default: 'lefttop',
    },
  };

  return { schema };
};
