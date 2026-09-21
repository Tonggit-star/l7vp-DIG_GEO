import { FieldSelect, FormCollapse } from '@antv/li-p2';
import { FormItem, Input, Radio, Select } from '@formily/antd-v5';
import { createSchemaField } from '@formily/react';
import React, { useMemo } from 'react';
import { useEditorService } from '../../../../hooks';
import schema from './schema';
import VisTypeSelect from './VisTypeSelect';

type BaseFormSchemaFieldProps = {
  sourceList: Record<string, any>[];
};

const SchemaField = createSchemaField({
  components: {
    FormItem,
    Input,
    // 图层备注用的多行输入框（antd TextArea 的 formily 包装）
    TextArea: Input.TextArea,
    Select,
    FormCollapse,
    FieldSelect,
    Radio,
    VisTypeSelect,
  },
});

const BaseFormSchemaField: React.FC<BaseFormSchemaFieldProps> = (props) => {
  const { appService } = useEditorService();
  const visLayerList = useMemo(() => {
    const implementLayers = appService.getImplementLayers();
    const list = implementLayers.map((implementLayer) => ({
      value: implementLayer.metadata.name,
      label: implementLayer.metadata.displayName,
      type: implementLayer.metadata.name,
      icon: implementLayer.metadata.icon,
    }));

    return list;
  }, [appService]);

  const _schema = useMemo(() => schema(props.sourceList, visLayerList), [props.sourceList, visLayerList]);

  return <SchemaField schema={_schema} />;
};

export default BaseFormSchemaField;
