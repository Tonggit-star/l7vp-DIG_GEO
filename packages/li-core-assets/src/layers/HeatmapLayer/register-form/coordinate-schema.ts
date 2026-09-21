import type { FieldSelectOptionType } from '@antv/li-p2';
import { getDefaultLongitude, getDefaultLatitude } from '../../coordinate-match';

export default (fieldList: FieldSelectOptionType[]) => {
  return {
    collapseItem_coordinate: {
      type: 'void',
      'x-component': 'FormCollapse',
      'x-component-props': { ghost: true, destroyInactivePanel: true, defaultActiveKey: ['coordinateField'] },
      properties: {
        coordinateField: {
          type: 'void',
          'x-component': 'FormCollapse.CollapsePanel',
          'x-component-props': { header: '坐标' },
          properties: {
            coordinateType: {
              type: 'string', title: '类型', default: 'table',
              'x-decorator': 'FormItem', 'x-component': 'Radio.Group',
              enum: [{ label: '点度', value: 'table' }, { label: '度分秒', value: 'dms' }, { label: 'Geometry', value: 'geometry' }],
            },
            longitude: {
              type: 'string', title: '经度', required: true,
              'x-decorator': 'FormItem', 'x-component': 'FieldSelect',
              'x-component-props': { allowClear: true, placeholder: '请选择字段' },
              enum: [...fieldList],
              'x-reactions': [{ dependencies: ['coordinateType'], fulfill: { state: { visible: '{{ $deps[0] === "table" || $deps[0] === "dms" }}', title: '{{ $deps[0] === "dms" ? "经度(度分秒)" : "经度" }}' } } }],
            },
            latitude: {
              type: 'string', title: '纬度', required: true,
              'x-decorator': 'FormItem', 'x-component': 'FieldSelect',
              'x-component-props': { allowClear: true, placeholder: '请选择字段' },
              enum: [...fieldList],
              'x-reactions': [{ dependencies: ['coordinateType'], fulfill: { state: { visible: '{{ $deps[0] === "table" || $deps[0] === "dms" }}', title: '{{ $deps[0] === "dms" ? "纬度(度分秒)" : "纬度" }}' } } }],
            },
            geometry: {
              type: 'string', title: 'geometry', required: true,
              'x-decorator': 'FormItem', 'x-component': 'FieldSelect',
              'x-component-props': { allowClear: true, placeholder: '请选择字段' },
              enum: [...fieldList],
              'x-reactions': [{ dependencies: ['coordinateType'], fulfill: { state: { visible: '{{ $deps[0] === "geometry" }}' } } }],
            },
          },
        },
      },
    },
  };
};
