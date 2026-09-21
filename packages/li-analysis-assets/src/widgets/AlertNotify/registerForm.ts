import type { PositionName } from '@antv/l7';
import { ColorPicker } from '@antv/li-p2';
import type { WidgetRegisterForm } from '@antv/li-sdk';

/**
 * 属性面板生产的数据类型定义
 */
export type Properties = {
  /** 是否显示面板 */
  enabled?: boolean;
  /** 放置方位（默认右上） */
  position?: PositionName;
  /** 面板标题 */
  title?: string;
  /** 同时显示条数 */
  displayCount?: number;
  /** 队列上限（超出后挤出级别最低且最旧的） */
  queueSize?: number;
  /** 面板宽度 px */
  width?: number;
  /** 面板最大高度 px（超出后列表内部滚动） */
  maxHeight?: number;
  /** 轮播间隔（秒），0 = 不自动轮播 */
  intervalSec?: number;
  /** 鼠标悬停时暂停轮播 */
  pauseOnHover?: boolean;
  /** 新到的紧急信息强制回到顶部 */
  urgentJumpTop?: boolean;
  /** 头部显示各级别条数 */
  showLevelCount?: boolean;
  /** 紧急主色 */
  urgentColor?: string;
  /** 紧急底色 */
  urgentBg?: string;
  /** 告警主色 */
  warningColor?: string;
  /** 告警底色（默认淡黄） */
  warningBg?: string;
  /** 通知主色 */
  noticeColor?: string;
  /** 通知底色 */
  noticeBg?: string;
  /** 演示用：持续模拟新信息 */
  simulate?: boolean;
  /** 演示用：模拟新信息间隔（秒） */
  simulateIntervalSec?: number;
};

export default (): WidgetRegisterForm<Properties> => {
  // 组件资产的配置表单面板 Schema，表单库 formily 的 Schema。
  // 注意：这里给的是「字段名 → schema」的**平铺映射**，外层不要包 type/properties——
  // 属性面板（WidgetForm）会把它整体摊进 properties 里（与图层样式面板的用法不同）。
  const schema = {
    // 字段多，按 FormCollapse 分组；键名即字段名
    collapseItem_alertNotify: {
      type: 'void',
      'x-component': 'FormCollapse',
      'x-component-props': {
        ghost: true,
        destroyInactivePanel: true,
        defaultActiveKey: ['base', 'display'],
      },
      properties: {
        base: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': { header: '基础' },
          properties: {
            enabled: {
              title: '显示面板',
              type: 'boolean',
              'x-decorator': 'FormItem',
              'x-component': 'Switch',
              default: true,
            },
            position: {
              title: '放置方位',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ControlPositionSelect',
              default: 'topright',
            },
            title: {
              title: '面板标题',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'Input',
              default: '实时告警',
            },
          },
        },
        display: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': { header: '展示' },
          properties: {
            displayCount: {
              title: '同时显示条数',
              tooltip: '面板里同时可见的条数，队列超出的部分靠轮播滚动展示',
              type: 'number',
              'x-decorator': 'FormItem',
              'x-component': 'NumberPicker',
              'x-component-props': { min: 1, max: 30, precision: 0, addonAfter: '条' },
              default: 10,
            },
            queueSize: {
              title: '队列上限',
              tooltip: '内存里最多保留多少条；超出时挤出「级别最低且最旧」的，紧急/告警不会被通知洪流挤掉',
              type: 'number',
              'x-decorator': 'FormItem',
              'x-component': 'NumberPicker',
              'x-component-props': { min: 10, max: 500, step: 10, precision: 0, addonAfter: '条' },
              default: 100,
            },
            width: {
              title: '面板宽度',
              type: 'number',
              'x-decorator': 'FormItem',
              'x-component': 'NumberPicker',
              'x-component-props': { min: 220, max: 600, step: 20, precision: 0, addonAfter: 'px' },
              default: 320,
            },
            maxHeight: {
              title: '面板最大高度',
              tooltip: '超出高度的部分在面板内滚动',
              type: 'number',
              'x-decorator': 'FormItem',
              'x-component': 'NumberPicker',
              'x-component-props': { min: 160, max: 800, step: 20, precision: 0, addonAfter: 'px' },
              default: 420,
            },
            showLevelCount: {
              title: '显示级别计数',
              type: 'boolean',
              'x-decorator': 'FormItem',
              'x-component': 'Switch',
              default: true,
            },
          },
        },
        roll: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': { header: '轮播' },
          properties: {
            intervalSec: {
              title: '轮播间隔',
              tooltip: '每隔多少秒窗口下移一行，到底后回到顶部；0 = 不自动轮播',
              type: 'number',
              'x-decorator': 'FormItem',
              'x-component': 'NumberPicker',
              'x-component-props': { min: 0, max: 60, precision: 0, addonAfter: '秒' },
              default: 5,
            },
            pauseOnHover: {
              title: '悬停暂停',
              tooltip: '鼠标移到面板上时暂停轮播，移开恢复（只停滚动，不停收数）',
              type: 'boolean',
              'x-decorator': 'FormItem',
              'x-component': 'Switch',
              default: true,
            },
            urgentJumpTop: {
              title: '紧急强制置顶',
              tooltip: '新到的紧急信息立即回到列表顶部；关闭后只有级别排序生效、不打断当前视图',
              type: 'boolean',
              'x-decorator': 'FormItem',
              'x-component': 'Switch',
              default: true,
            },
          },
        },
        color: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': { header: '配色' },
          properties: {
            urgentColor: {
              title: '紧急主色',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
              default: '#FF4D4F',
            },
            urgentBg: {
              title: '紧急底色',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
              default: '#FFF1F0',
            },
            warningColor: {
              title: '告警主色',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
              default: '#D48806',
            },
            warningBg: {
              title: '告警底色',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
              default: '#FFF7CC',
            },
            noticeColor: {
              title: '通知主色',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
              default: '#1F1F1F',
            },
            noticeBg: {
              title: '通知底色',
              tooltip: '通知不抢注意力，默认与面板底色一致（白色）',
              type: 'string',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
              default: '#FFFFFF',
            },
          },
        },
        demo: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': { header: '数据（演示）' },
          properties: {
            simulate: {
              title: '模拟新信息',
              tooltip:
                '演示用：先灌入 20 条示例数据，之后按下面间隔持续产生新告警（用来验证置顶与滚动）。接入真实数据源后请关闭',
              type: 'boolean',
              'x-decorator': 'FormItem',
              'x-component': 'Switch',
              default: true,
            },
            simulateIntervalSec: {
              title: '模拟间隔',
              type: 'number',
              'x-decorator': 'FormItem',
              'x-component': 'NumberPicker',
              'x-component-props': { min: 2, max: 120, precision: 0, addonAfter: '秒' },
              default: 10,
            },
          },
        },
      },
    },
  };

  // 属性面板（WidgetForm/SchemaField）自带的基础组件里没有 ColorPicker，必须随 registerForm 一起提供
  return { schema, components: { ColorPicker } };
};
