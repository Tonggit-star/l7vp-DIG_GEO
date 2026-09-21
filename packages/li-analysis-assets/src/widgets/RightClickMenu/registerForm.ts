import { ColorPicker } from '@antv/li-p2';
import type { WidgetRegisterForm, WidgetRegisterFormProps } from '@antv/li-sdk';

/**
 * 属性面板生产的数据类型定义
 */
export type Properties = {
  /** 是否开启右键菜单 */
  showRightMenu?: boolean;
  /** 是否在流式图层上提供「历史轨迹」（需数据集配置了目标标识字段） */
  showHistoryTrack?: boolean;
  /** 单次最多加载的历史轨迹点数，透传给后端 limit */
  historyMaxRows?: number;
  /** 轨迹线与轨迹点颜色 */
  trackColor?: string;
};

export default (props: WidgetRegisterFormProps): WidgetRegisterForm<Properties> => {
  // 组件资产的配置表单面板 Schema，表单库 formily 的 Schema
  const schema = {
    showRightMenu: {
      title: '是否开启',
      type: 'boolean',
      'x-decorator': 'FormItem',
      'x-component': 'Radio.Group',
      enum: [
        { label: '是', value: true },
        { label: '否', value: false },
      ],
      default: true,
    },
    showHistoryTrack: {
      title: '历史轨迹',
      tooltip: '在配置了「目标标识字段」的流式图层上右键目标，可查询并绘制其历史轨迹',
      type: 'boolean',
      'x-decorator': 'FormItem',
      'x-component': 'Radio.Group',
      'x-reactions': [
        {
          dependencies: ['showRightMenu'],
          fulfill: { state: { visible: '{{ $deps[0] }}' } },
        },
      ],
      enum: [
        { label: '开启', value: true },
        { label: '关闭', value: false },
      ],
      default: true,
    },
    historyMaxRows: {
      title: '最多点数',
      tooltip: '单次查询返回的最大历史点数（超出只保留最早的部分），受后端 flink.tsdb.history-max-rows 上限约束',
      type: 'number',
      'x-decorator': 'FormItem',
      'x-component': 'NumberPicker',
      'x-component-props': { min: 1, max: 50000, step: 1000, style: { width: 200 } },
      'x-reactions': [
        {
          dependencies: ['showRightMenu', 'showHistoryTrack'],
          fulfill: { state: { visible: '{{ $deps[0] && $deps[1] }}' } },
        },
      ],
      default: 20000,
    },
    trackColor: {
      title: '轨迹颜色',
      type: 'string',
      'x-decorator': 'FormItem',
      'x-component': 'ColorPicker',
      'x-reactions': [
        {
          dependencies: ['showRightMenu', 'showHistoryTrack'],
          fulfill: { state: { visible: '{{ $deps[0] && $deps[1] }}' } },
        },
      ],
      default: '#F86624',
    },
  };
  // 属性面板（WidgetForm/SchemaField）自带的基础组件里没有 ColorPicker，必须随 registerForm 一起提供
  return { schema, components: { ColorPicker } };
};
