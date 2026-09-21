import { usePrefixCls } from '@formily/antd-v5/esm/__builtins__';
import { connect } from '@formily/react';
import { Select } from 'antd';
import cls from 'classnames';
import React, { useEffect, useMemo, useState } from 'react';
import type { IconItem, IconList } from '../IconScaleSelector/type';
import useStyle from './style';

export type IconListProps = {
  onChange: (icon: string) => void;
  value?: string;
  /** 外部传入的图标分类过滤（来自表单中 iconCategory 字段的值） */
  category?: string;
};

const Internal: React.FC<IconListProps> = (props) => {
  const { value: defaultValue, onChange, category: externalCategory } = props;
  const prefixCls = usePrefixCls('formily-icon-selector');
  const [wrapSSR, hashId] = useStyle(prefixCls);
  const [open, setOpen] = useState(false);
  const [iconList, setIconList] = useState<IconList>([]);
  const [loading, setLoading] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<string>('');

  // 组件挂载时加载图标列表，确保选中图标能正确显示 URL
  useEffect(() => {
    loadIcons();
  }, []);

  // 打开下拉框时也刷新图标列表
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
    // 如果值本身是 URL，直接使用
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
    // 兜底：显示空
    return [];
  }, [defaultValue, iconList]);

  // 有效分类：如果外部传入了 category，用外部值；否则用内部 selectedCategory
  const effectiveCategory = externalCategory !== undefined ? externalCategory : selectedCategory;

  const filteredIcons = useMemo(() => {
    if (!effectiveCategory) {
      return iconList.flatMap((cat) => cat.icons);
    }
    const category = iconList.find((cat) => cat.type === effectiveCategory);
    return category ? category.icons : [];
  }, [iconList, effectiveCategory]);

  const onIconChange = (icon: IconItem) => {
    // 统一传 URL，兼容图集构建
    onChange(icon.url);
    setOpen(false);
  };

  const categories = iconList.map((cat) => ({
    value: cat.type,
    label: cat.type,
  }));

  return wrapSSR(
    <Select
      placeholder={externalCategory !== undefined ? `请选择${externalCategory}图标` : '请选择图标'}
      open={open}
      className={cls(`${prefixCls}`, hashId)}
      onDropdownVisibleChange={(visible) => setOpen(visible)}
      dropdownRender={() => {
        return (
          <div style={{ padding: '8px' }}>
            {loading && <div style={{ padding: '10px', textAlign: 'center' }}>加载中...</div>}
            {!loading && (
              <div>
                {/* 当外部没有控制 category 时，显示内部分类选择器 */}
                {externalCategory === undefined && (
                  <div style={{ marginBottom: '8px' }}>
                    <Select
                      placeholder="请选择图标分类"
                      value={selectedCategory}
                      onChange={setSelectedCategory}
                      style={{ width: '100%' }}
                      options={categories}
                      allowClear
                    />
                  </div>
                )}
                <div className={cls(`${prefixCls}__icon-panel`, hashId)}>
                  {filteredIcons.length > 0 ? (
                    filteredIcons.map((icon) => (
                      <img
                        key={icon.id}
                        src={icon.url}
                        onClick={() => onIconChange(icon)}
                        style={{
                          width: '40px',
                          height: '40px',
                          margin: '4px',
                          cursor: 'pointer',
                          border: '1px solid transparent',
                          borderRadius: '4px',
                        }}
                        onMouseEnter={(e) => {
                          e.currentTarget.style.borderColor = '#1890ff';
                        }}
                        onMouseLeave={(e) => {
                          e.currentTarget.style.borderColor = 'transparent';
                        }}
                      />
                    ))
                  ) : (
                    <div style={{ padding: '20px', textAlign: 'center', color: '#999' }}>
                      暂无图标
                    </div>
                  )}
                </div>
              </div>
            )}
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

const IconSelector = connect(Internal);
export default IconSelector;
