import { ColorPicker } from '@antv/li-p2';
import type { WidgetRegisterForm, WidgetRegisterFormProps } from '@antv/li-sdk';

/**
 * 属性面板生产的数据类型定义
 */
export type Properties = {
  /** 是否默认开启「接入智能体」（默认关：页面必须显式授权后才接受外部控制） */
  enabled?: boolean;
  /** 长轮询挂起时长（秒），服务端上限 30 */
  pollTimeout?: number;
  /** 选中目标的高亮颜色 */
  highlightColor?: string;
};

export default (props: WidgetRegisterFormProps): WidgetRegisterForm<Properties> => {
  // 组件资产的配置表单面板 Schema，表单库 formily 的 Schema
  const schema = {
    enabled: {
      title: '默认开启',
      tooltip:
        '默认关闭：页面不接受任何外部智能体的控制。开启后本页才会轮询指令并执行。注意在 Builder 页，开启同时会让智能体可以新建/修改图层并写入项目配置（保存后生效），不只是运行时视图',
      type: 'boolean',
      'x-decorator': 'FormItem',
      'x-component': 'Radio.Group',
      enum: [
        { label: '开启', value: true },
        { label: '关闭', value: false },
      ],
      default: false,
    },
    pollTimeout: {
      title: '取令挂起时长',
      tooltip: '长轮询单次挂起秒数，越大指令越及时且请求越少；服务端上限 30 秒',
      type: 'number',
      'x-decorator': 'FormItem',
      'x-component': 'NumberPicker',
      'x-component-props': { min: 5, max: 30, step: 5, style: { width: 200 } },
      default: 25,
    },
    highlightColor: {
      title: '高亮颜色',
      type: 'string',
      'x-decorator': 'FormItem',
      'x-component': 'ColorPicker',
      default: '#F86624',
    },
  };
  // 属性面板（WidgetForm/SchemaField）自带的基础组件里没有 ColorPicker，必须随 registerForm 一起提供
  return { schema, components: { ColorPicker } };
};
