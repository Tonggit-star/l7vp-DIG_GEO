import type { ILayerField } from '@antv/larkmap/es/components/LayerPopup/types';
import type { DatasetField, WidgetRegisterForm, WidgetRegisterFormProps } from '@antv/li-sdk';
import { isLocalOrRemoteDataset } from '@antv/li-sdk';

/**
 * 属性面板生产的数据类型定义
 */
export type Properties = {
  isOpen: boolean;
  trigger: 'hover' | 'click';
  items: { layerId: string; fields: ILayerField[] }[];
};

/**
 * 表单的数据结构类型
 */
type FormValues = { items: Record<string, { field: string; formatField: string }[]> } & Omit<Properties, 'items'>;

/**
 * 表单数据格式转换，将结构化数据(属性面板数据类型)转换为表单的平铺结构
 */
const toValues = (config: Properties): FormValues => {
  const { items, ...rest } = config;
  const itemsValue = items.reduce((map, next) => {
    const key = next.layerId;
    map[key] = next.fields.map((field) => {
      return { field: field.field, formatField: field.formatField };
    });
    return map;
  }, {} as Record<string, any>);

  return {
    ...rest,
    items: itemsValue,
  };
};

/**
 * 表单数据格式转换，将表单的平铺数据结构转为结构化数据(属性面板数据类型)
 */
const fromValues = (values: FormValues): Properties => {
  const { items, ...rest } = values;

  // TODO: 冗余数据剔除
  const formatItem = Object.entries(items).map(([key, fields]) => {
    return {
      layerId: key,
      fields: fields.map((field) => ({
        field: field.field,
        formatField: field.formatField !== '' ? field.formatField : undefined,
      })),
    };
  });

  return {
    ...rest,
    items: formatItem,
  };
};

/**
 * 获取图层的显示字段 formily 的 Schema 配置
 */
const getLayerFieldsFormSchemas = (props: WidgetRegisterFormProps) => {
  const { layers, datasets } = props;

  const layerSchemaList = layers
    .filter((item) => !['GridLayer', 'HexbinLayer', 'HeatmapLayer'].includes(item.type))
    .map((item) => {
      const dataset = datasets.find((items) => items.id === item.sourceConfig.datasetId);
      if (dataset === undefined || !isLocalOrRemoteDataset(dataset)) return undefined;

      const columns = dataset?.columns || [];
      const options = columns.map((f: DatasetField) => ({
        label: f.displayName ? `${f.name}（${f.displayName}）` : f.name,
        value: f.name,
      }));

      return {
        [`items.${item.id}`]: {
          type: 'array',
          'x-component': 'ArrayItems',
          'x-component-props': {
            style: {
              width: '100%',
              marginTop: 10,
            },
          },
          'x-decorator': 'FormItem',
          'x-decorator-props': {
            labelWidth: '100%',
            wrapperWidth: '100%',
            layout: 'vertical',
          },
          title: item.metadata.name,
          items: {
            type: 'object',
            properties: {
              fields: {
                type: 'void',
                'x-component': 'FormGrid',
                'x-component-props': {
                  colWrap: false,
                },
                properties: {
                  field: {
                    type: 'string',
                    enum: options,
                    'x-decorator': 'FormItem',
                    required: true,
                    'x-decorator-props': {
                      gridSpan: 7,
                    },
                    'x-component': 'Select',
                    'x-component-props': {
                      placeholder: '选择类目标签',
                    },
                  },
                  formatField: {
                    type: 'string',
                    'x-decorator': 'FormItem',
                    'x-component': 'Input',
                    'x-decorator-props': {
                      gridSpan: 6,
                    },
                    'x-component-props': {
                      placeholder: '输入类目别名',
                    },
                  },
                  remove: {
                    type: 'void',
                    'x-decorator': 'FormItem',
                    'x-component': 'ArrayItems.Remove',
                    'x-component-props': {},
                    'x-decorator-props': {
                      gridSpan: 1,
                    },
                  },
                },
              },
            },
          },
          properties: {
            add: {
              type: 'void',
              title: '添加类目',
              'x-component': 'ArrayItems.Addition',
              'x-component-props': {
                block: true,
              },
            },
          },
        },
      };
    })
    .filter((item) => item !== undefined);

  const layerSchemaMap = layerSchemaList.reduce((map, next) => Object.assign(map, next), {} as Record<string, any>);

  return layerSchemaMap;
};

export default (props: WidgetRegisterFormProps): WidgetRegisterForm<Properties, FormValues> => {
  // 构建 fieldName → displayName 映射，用于自动填充 formatField（数据库字段注释）
  const displayNameMap: Record<string, string> = {};
  props.datasets.forEach((ds) => {
    if (isLocalOrRemoteDataset(ds)) {
      (ds.columns || []).forEach((col: DatasetField) => {
        if ((col as any).displayName) displayNameMap[col.name] = (col as any).displayName;
      });
    }
  });
  console.log('[LayerPopup.registerForm] displayNameMap keys:', Object.keys(displayNameMap).length, 'samples:', Object.keys(displayNameMap).slice(0, 5));

  // 覆盖 fromValues，自动填入 formatField = 字段注释
  const fromValuesLocal = (values: FormValues): Properties => {
    const { items, ...rest } = values;
    const formatItem = Object.entries(items).map(([key, fields]) => ({
      layerId: key,
      fields: fields.map((field) => ({
        field: field.field,
        formatField: field.formatField || displayNameMap[field.field] || undefined,
      })),
    }));
    return { ...rest, items: formatItem };
  };

  // 属性面板表单的 Schema 定义，来自表单库 formily 的 Schema
  const schema = {
    isOpen: {
      title: '开启图层信息框',
      type: 'boolean',
      'x-decorator': 'FormItem',
      'x-component': 'Switch',
      default: true,
      'x-decorator-props': {
        labelCol: 9,
        wrapperCol: 15,
      },
    },
    trigger: {
      title: '打开方式',
      type: 'string',
      'x-decorator': 'FormItem',
      'x-component': 'Select',
      'x-component-props': {
        options: [
          {
            value: 'click',
            label: '点击',
          },
          {
            value: 'hover',
            label: '划入',
          },
        ],
      },
      default: 'hover',
    },
    ...getLayerFieldsFormSchemas(props),
  };

  return { schema, toValues, fromValues: fromValuesLocal };
};
