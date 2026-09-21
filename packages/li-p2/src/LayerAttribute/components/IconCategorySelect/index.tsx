import { connect } from '@formily/react';
import { Select } from 'antd';
import React, { useEffect, useState } from 'react';

export type IconCategorySelectProps = {
  onChange?: (value: string) => void;
  value?: string;
  placeholder?: string;
  allowClear?: boolean;
};

const Internal: React.FC<IconCategorySelectProps> = (props) => {
  const { value, onChange, placeholder, allowClear } = props;
  const [categories, setCategories] = useState<{ label: string; value: string }[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetch('/api/icons')
      .then((res) => {
        if (!res.ok) throw new Error('Failed to fetch icons');
        return res.json();
      })
      .then((data) => {
        if (Array.isArray(data)) {
          const list = data.map((cat: any) => ({
            label: cat.type,
            value: cat.type,
          }));
          setCategories(list);
        }
      })
      .catch((err) => {
        console.warn('IconCategorySelect: 加载图标分类失败', err);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  return (
    <Select
      placeholder={placeholder || '请选择图标分类'}
      value={value || undefined}
      onChange={onChange}
      allowClear={allowClear !== false}
      loading={loading}
      options={categories}
    />
  );
};

const IconCategorySelect = connect(Internal);
export default IconCategorySelect;
