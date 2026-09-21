import type { ImplementWidget, WidgetRegisterFormProps } from '@antv/li-sdk';
import { Form } from '@formily/antd-v5';
import type { Form as FormInstance } from '@formily/core';
import { createForm, onFieldValueChange, onFormValuesChange } from '@formily/core';
import { useMemoizedFn } from 'ahooks';
import classNames from 'classnames';
import { debounce } from 'lodash-es';
import React, { useMemo } from 'react';
import { usePrefixCls } from '../../../../hooks';
import WidgetSchemaField from './SchemaField';
import useStyle from './style';

type WidgetFormProps = {
  className?: string;
  initialValues: Record<string, any>;
  registerForm: ImplementWidget['registerForm'];
  registerFormProps: WidgetRegisterFormProps;
  onChange: (values: Record<string, any>) => void;
};

const WidgetForm: React.FC<WidgetFormProps> = (props) => {
  const { className, initialValues, registerForm, registerFormProps, onChange } = props;
  const prefixCls = usePrefixCls('widget-form');
  const styles = useStyle();

  const registerFormData = useMemo(() => {
    const result = typeof registerForm === 'function' ? registerForm(registerFormProps) : registerForm;
    return result;
  }, [registerForm, registerFormProps]);

  const handleFormValuesChange = useMemoizedFn((formIns: FormInstance<any>) => {
    const values = formIns.values;
    console.log('[WidgetForm] save triggered, keys:', Object.keys(values), 'defaultFilters:', JSON.stringify(values.defaultFilters)?.substring(0, 200));
    const result = registerFormData.fromValues ? registerFormData.fromValues(values) : values;
    onChange(result);
  });

  const schema = useMemo(() => {
    const properties = registerFormData.schema;
    return {
      type: 'object',
      properties: { ...properties },
    };
  }, [registerFormData.schema]);

  const formInstance = useMemo(() => {
    const _initialValues = registerFormData.toValues ? registerFormData.toValues(initialValues) : initialValues;
    const form = createForm({
      initialValues: _initialValues,
      effects() {
        // 即时保存（无 debounce），避免复杂组件（如 FilterConfiguration）修改后丢失
        onFormValuesChange((formIns: FormInstance<any>) => {
          handleFormValuesChange(formIns);
        });
      },
    });

    return form;
    // schema 发生更新重新生成新的表单实例
  }, [schema]);

  return (
    <Form
      className={classNames(prefixCls, styles.widgetForm, className)}
      form={formInstance}
      labelCol={8}
      wrapperCol={16}
      colon={false}
      layout="horizontal"
      labelAlign="left"
      wrapperAlign="right"
    >
      <WidgetSchemaField schema={schema} components={registerFormData.components} />
    </Form>
  );
};

export default WidgetForm;
