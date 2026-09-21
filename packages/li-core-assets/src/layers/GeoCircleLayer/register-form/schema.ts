import type { FieldSelectOptionType } from '@antv/li-p2';
import getCoordinateSchema from './coordinate-schema';

export default (fieldList: FieldSelectOptionType[]) => {
  const numberFields = fieldList.filter((f) => f.type === 'number');
  console.log('[GeoCircleLayer.schema] fieldList len:', fieldList.length, 'with label≠name:', fieldList.filter((f: any) => f.label !== f.value).length, 'samples:', fieldList.filter((f: any) => f.label !== f.value).slice(0, 3).map((f: any) => `${f.value}→${f.label}`));

  return {
    ...getCoordinateSchema(fieldList),

    collapseItem_style: {
      type: 'void',
      'x-component': 'FormCollapse',
      'x-component-props': {
        ghost: true,
        destroyInactivePanel: true,
        defaultActiveKey: ['styleConfig'],
      },
      properties: {
        styleConfig: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': {
            header: '样式',
          },
          properties: {
            outerRadiusField: {
              type: 'string',
              title: '外圈半径',
              required: true,
              'x-decorator': 'FormItem',
              'x-component': 'FieldSelect',
              'x-decorator-props': {
                tooltip: '数据中包含外圈半径（物理长度）的数值字段',
              },
              'x-component-props': {
                allowClear: true,
                placeholder: '请选择字段',
              },
              enum: [...numberFields],
            },

            innerRadiusField: {
              type: 'string',
              title: '内圈半径',
              'x-decorator': 'FormItem',
              'x-component': 'FieldSelect',
              'x-decorator-props': {
                tooltip: '可选：数据中包含内圈半径的数值字段。填写后绘制环形（外圈-内圈）',
              },
              'x-component-props': {
                allowClear: true,
                placeholder: '不填则为实心圆',
              },
              enum: [...numberFields],
            },

            radiusUnit: {
              type: 'string',
              title: '半径单位',
              default: 'meters',
              'x-decorator': 'FormItem',
              'x-component': 'Select',
              enum: [
                { label: '米', value: 'meters' },
                { label: '千米', value: 'kilometers' },
                { label: '英里', value: 'miles' },
                { label: '海里', value: 'nauticalmiles' },
              ],
            },

            fillColor: {
              type: 'string',
              title: '填充颜色',
              default: 'rgb(90, 216, 166)',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
            },

            fillOpacity: {
              type: 'number',
              title: '透明度',
              default: 0.8,
              'x-decorator': 'FormItem',
              'x-component': 'Slider',
              'x-component-props': {
                min: 0,
                max: 1,
                step: 0.1,
              },
            },

            strokeColor: {
              type: 'string',
              title: '描边颜色',
              default: '#a9abb1',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
            },

            lineWidth: {
              type: 'number',
              title: '描边宽度',
              default: 1,
              'x-decorator': 'FormItem',
              'x-component': 'Slider',
              'x-component-props': {
                min: 0,
                max: 10,
                step: 0.5,
              },
            },

            lineType: {
              type: 'string',
              title: '描边线型',
              default: 'solid',
              'x-decorator': 'FormItem',
              'x-component': 'Select',
              enum: [
                { label: '实线', value: 'solid' },
                { label: '虚线', value: 'dash' },
              ],
            },

            clipChina: {
              type: 'boolean',
              title: '裁剪国内',
              default: false,
              'x-decorator': 'FormItem',
              'x-component': 'Switch',
              'x-decorator-props': {
                tooltip: '开启后地理圆将减去中国大陆+海南岛陆地区域（不含台湾）',
              },
            },
          },
        },
      },
    },

    collapseItem_label: {
      type: 'void',
      'x-component': 'FormCollapse',
      'x-component-props': {
        ghost: true,
        destroyInactivePanel: true,
        defaultActiveKey: ['labelConfig'],
      },
      properties: {
        labelConfig: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': {
            header: '文本标注',
          },
          properties: {
            labelField: {
              type: 'string',
              title: '标注字段',
              'x-decorator': 'FormItem',
              'x-component': 'FieldSelect',
              'x-component-props': {
                allowClear: true,
                placeholder: '请选择字段',
              },
              enum: [...fieldList],
            },
            labelColor: {
              type: 'string',
              title: '字体颜色',
              default: '#c0c0c0',
              'x-decorator': 'FormItem',
              'x-component': 'ColorPicker',
            },
            labelFontSize: {
              type: 'number',
              title: '字体大小',
              default: 15,
              'x-decorator': 'FormItem',
              'x-component': 'Slider',
              'x-component-props': {
                min: 8,
                max: 48,
                step: 1,
              },
            },
            labelTextAnchor: {
              type: 'string',
              title: '对齐方式',
              default: 'center',
              'x-decorator': 'FormItem',
              'x-component': 'Select',
              enum: [
                { label: '居中', value: 'center' },
                { label: '左对齐', value: 'left' },
                { label: '右对齐', value: 'right' },
              ],
            },
          },
        },
      },
    },

    collapseItem_other: {
      type: 'void',
      'x-component': 'FormCollapse',
      'x-component-props': {
        ghost: true,
        destroyInactivePanel: true,
        defaultActiveKey: ['otherConfig'],
      },
      properties: {
        otherConfig: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': {
            header: '其他',
          },
          properties: {
            zoom: {
              type: 'array',
              title: '缩放范围',
              default: [0, 24],
              'x-decorator': 'FormItem',
              'x-component': 'SliderRange',
              'x-component-props': {
                min: 0,
                max: 24,
                step: 1,
              },
            },
            blend: {
              type: 'string',
              title: '混合模式',
              default: 'normal',
              'x-decorator': 'FormItem',
              'x-component': 'Select',
              enum: [
                { label: 'normal', value: 'normal' },
                { label: 'additive', value: 'additive' },
                { label: 'subtractive', value: 'subtractive' },
                { label: 'max', value: 'max' },
                { label: 'min', value: 'min' },
                { label: 'none', value: 'none' },
              ],
            },
          },
        },
      },
    },
  };
};
