import { usePrefixCls } from '@formily/antd-v5/esm/__builtins__';
import { connect } from '@formily/react';
import { Select } from 'antd';
import cls from 'classnames';
import React, { useEffect, useMemo, useState } from 'react';
import IconPanel from '../IconScaleSelector/IconPanel';
import type { IconItem, IconList } from '../IconScaleSelector/type';
import useStyle from './style';

export type DynamicIconSelectorProps = {
  onChange: (icon: string) => void;
  value?: string;
};

const Internal: React.FC<DynamicIconSelectorProps> = (props) => {
  const { value: defaultValue, onChange } = props;
  const prefixCls = usePrefixCls('formily-icon-selector');
  const [wrapSSR, hashId] = useStyle(prefixCls);
  const [open, setOpen] = useState(false);
  const [iconList, setIconList] = useState<IconList>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open) {
      loadIcons();
    }
  }, [open]);

  const loadIcons = async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/icons');
      if (response.ok) {
        const data = await response.json();
        if (data && Array.isArray(data)) {
          setIconList(data);
        }
      }
    } catch (error) {
      console.error('加载图标列表失败:', error);
    } finally {
      setLoading(false);
    }
  };

  const selectedIcon = useMemo(() => {
    if (!defaultValue) {
      return [];
    }
    // 如果值是 URL，直接使用
    if (defaultValue.startsWith('/')) {
      return [{ value: defaultValue, label: defaultValue }];
    }
    // 从加载的 iconList 中查找
    for (const cat of iconList) {
      const found = cat.icons.find((_item) => _item.id === defaultValue || _item.url === defaultValue);
      if (found?.url) {
        return [{ value: defaultValue, label: found.url }];
      }
    }
    return [];
  }, [defaultValue, iconList]);

  const onIconChange = (icon: IconItem) => {
    onChange(icon.url);
    setOpen(false);
  };

  return wrapSSR(
    <Select
      placeholder="请选择图标"
      open={open}
      className={cls(`${prefixCls}`, hashId)}
      onDropdownVisibleChange={(visible) => setOpen(visible)}
      dropdownRender={() => {
        return (
          <div>
            {loading && <div style={{ padding: '10px', textAlign: 'center' }}>加载中...</div>}
            {!loading && <IconPanel iconList={iconList} onChange={onIconChange} />}
          </div>
        );
      }}
      value={selectedIcon[0]?.value}
    >
      {selectedIcon.length &&
        selectedIcon.map((item) => {
          return (
            <Select.Option key={item.toString()} value={item.value}>
              <img src={item.label} style={{ width: '24px', height: '24px' }} />
            </Select.Option>
          );
        })}
    </Select>,
  );
};

const DynamicIconSelector = connect(Internal);
export default DynamicIconSelector;
