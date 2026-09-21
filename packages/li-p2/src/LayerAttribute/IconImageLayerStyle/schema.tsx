import lableCollapse from '../common-schema/label-collapse';
import otherAttributesCollapse from '../common-schema/other-attributes-collapse';
import radiusCollapse from '../common-schema/point-radius-collapse';
import type { AttributeSchemaOptions } from '../types';

export default (options: AttributeSchemaOptions) => {
  const { fieldList = [] } = options;
  // 基于字段：仅允许 string 类型字段（与原始 master 一致）
  const iconFieldList = fieldList.filter((item) => item.type === 'string');

  return {
    type: 'object',
    properties: {
      collapseItem_fillIcon: {
        type: 'void',
        'x-component': 'FormCollapse',
        'x-component-props': {
          ghost: true,
          destroyInactivePanel: true,
          defaultActiveKey: ['fillIcon'],
        },
        properties: {
          fillIcon: {
            type: 'void',
            'x-component': 'FormCollapse.CollapsePanel',
            'x-component-props': {
              header: '图标类型',
            },
            properties: {
              iconType: {
                type: 'string',
                title: '图标模式',
                default: 'fixed',
                'x-decorator': 'FormItem',
                'x-component': 'Radio.Group',
                enum: [
                  { label: '固定图标', value: 'fixed' },
                  { label: '基于字段', value: 'field' },
                ],
              },

              // ===== 固定图标模式：移植原始 master 的图标映射逻辑 =====
              // 顺序：图标形状 → 图标映射 → 基于字段（与 master 顺序调换）

              iconImg: {
                type: 'string',
                title: '图标形状',
                required: true,
                'x-decorator': 'FormItem',
                'x-component': 'IconSelector',
                'x-decorator-props': {
                  tooltip: '选中一个图标作为填充图标',
                },
                'x-component-props': {
                  placeholder: '请选择图标',
                },
                'x-reactions': [
                  {
                    dependencies: ['iconType', 'iconField'],
                    fulfill: {
                      state: {
                        visible: '{{ $deps[0] === "fixed" && $deps[1] === undefined }}',
                      },
                    },
                  },
                ],
              },

              iconImgScale: {
                type: 'array',
                title: '图标映射',
                'x-decorator': 'FormItem',
                'x-component': 'IconScaleSelector',
                'x-component-props': {
                  domain:
                    '{{ $form.getFieldState("iconField",state=> { return state.dataSource.find((item) => item.value === state.value)?.domain })}}',
                },
                'x-decorator-props': {
                  tooltip: '点击可添加查看图标',
                },
                'x-reactions': [
                  {
                    dependencies: ['iconType', 'iconField'],
                    fulfill: {
                      state: {
                        visible: '{{ $deps[0] === "fixed" && $deps[1] !== undefined }}',
                      },
                    },
                  },
                ],
              },

              iconField: {
                type: 'string',
                title: '基于字段',
                'x-decorator': 'FormItem',
                'x-component': 'FieldSelect',
                'x-decorator-props': {
                  tooltip: '选中一个字段作为图标映射的依据',
                },
                'x-component-props': {
                  placeholder: '请选择字段',
                  allowClear: true,
                },
                enum: iconFieldList,
                'x-reactions': [
                  {
                    target: 'iconImgScale',
                    effects: ['onFieldValueChange'],
                    fulfill: {
                      run: "$form.setFieldState('iconImgScale',state=>{ state.value = undefined })",
                    },
                  },
                  {
                    dependencies: ['iconType'],
                    fulfill: {
                      state: {
                        visible: '{{ $deps[0] === "fixed" }}',
                      },
                    },
                  },
                ],
              },

              // ===== 基于字段模式：库号+代号双字段匹配（不改动） =====
              iconLibraryField: {
                type: 'string',
                title: '库号字段',
                required: true,
                'x-decorator': 'FormItem',
                'x-component': 'FieldSelect',
                'x-decorator-props': {
                  tooltip: '数据中代表库号(library_code)的字段',
                },
                'x-component-props': {
                  placeholder: '请选择库号字段',
                  allowClear: true,
                },
                enum: fieldList,
                'x-reactions': [
                  {
                    dependencies: ['iconType'],
                    fulfill: {
                      state: {
                        visible: '{{ $deps[0] === "field" }}',
                      },
                    },
                  },
                ],
              },

              iconCodeField: {
                type: 'string',
                title: '代号字段',
                required: true,
                'x-decorator': 'FormItem',
                'x-component': 'FieldSelect',
                'x-decorator-props': {
                  tooltip: '数据中代表代号(code_name)的字段',
                },
                'x-component-props': {
                  placeholder: '请选择代号字段',
                  allowClear: true,
                },
                enum: fieldList,
                'x-reactions': [
                  {
                    dependencies: ['iconType'],
                    fulfill: {
                      state: {
                        visible: '{{ $deps[0] === "field" }}',
                      },
                    },
                  },
                ],
              },

              // fallback 图标（基于字段模式中也可选）
              iconImgFallback: {
                type: 'string',
                title: '默认图标',
                'x-decorator': 'FormItem',
                'x-component': 'IconSelector',
                'x-decorator-props': {
                  tooltip: '基于字段模式下，未匹配到图标时使用的默认图标。不选则跳过该数据点',
                },
                'x-component-props': {
                  placeholder: '请选择默认图标（可选）',
                },
                'x-reactions': [
                  {
                    dependencies: ['iconType'],
                    fulfill: {
                      state: {
                        visible: '{{ $deps[0] === "field" }}',
                      },
                    },
                  },
                ],
              },

              // ===== 图标转向：固定图标 / 基于字段两种模式共用 =====
              // 刻意不加 x-reactions，两种模式下都显示。
              // 字段候选必须用全量 fieldList（而非上面的 iconFieldList）——后者被过滤成只剩 string 列，
              // 而航向/角度列（如 heading）是 number 类型，复用它会一个可选字段都没有。
              iconRotationField: {
                type: 'string',
                title: '图标转向',
                'x-decorator': 'FormItem',
                'x-component': 'FieldSelect',
                'x-decorator-props': {
                  tooltip:
                    '按该字段的角度旋转图标：0-360，从正北起顺时针。图标素材需「船头朝北」，此时用航向字段即可。留空则不旋转',
                },
                'x-component-props': {
                  placeholder: '请选择字段',
                  allowClear: true,
                },
                enum: fieldList,
              },

              fillOpacity: {
                type: 'number',
                title: '透明度',
                default: 1,
                'x-decorator-props': {},
                'x-decorator': 'FormItem',
                'x-component': 'Slider',
                'x-component-props': {
                  min: 0,
                  max: 1,
                  step: 0.1,
                },
              },
            },
          },
        },
      },
      collapseItem_fillRadius: radiusCollapse({ fieldList, collapseTitle: '图标大小' }),
      // label --文本图层
      collapseItem_fillLabel: lableCollapse({ fieldList }),
      collapseItem_other: otherAttributesCollapse({}),
    },
  };
};
