export default (sourceList: Record<string, any>[], visLayerList: Record<string, any>[]) => {
  return {
    type: 'object',
    properties: {
      collapseItem_base: {
        type: 'void',
        'x-component': 'FormCollapse',
        'x-component-props': {
          ghost: true,
          destroyInactivePanel: true,
          defaultActiveKey: ['baseField'],
        },
        properties: {
          baseField: {
            type: 'void',
            'x-component': 'FormCollapse.CollapsePanel',
            'x-component-props': {
              header: '基础',
            },
            properties: {
              datasetId: {
                type: 'string',
                title: '数据来源',
                required: true,
                enum: [...sourceList],
                'x-decorator': 'FormItem',
                'x-component': 'Select',
                'x-component-props': {
                  placeholder: '请选择数据来源',
                },
                'x-decorator-props': {
                  tooltip: '选择一张数据表作为图层数据来源',
                },
              },

              visType: {
                type: 'string',
                title: '可视化类型',
                required: true,
                'x-decorator': 'FormItem',
                'x-component': 'VisTypeSelect',
                'x-component-props': {
                  placeholder: '请选择可视化类型',
                },
                'x-decorator-props': {},
                enum: [...visLayerList],
              },

              // 图层备注：纯说明信息，存进 layer.metadata.description，不参与渲染
              description: {
                type: 'string',
                title: '图层备注',
                'x-decorator': 'FormItem',
                'x-component': 'TextArea',
                'x-component-props': {
                  placeholder: '选填，可记录该图层的用途、数据口径等',
                  rows: 2,
                  maxLength: 200,
                  showCount: true,
                },
                'x-decorator-props': {
                  tooltip: '仅作为说明信息保存，不影响图层渲染',
                },
              },
            },
          },
        },
      },
    },
  };
};
