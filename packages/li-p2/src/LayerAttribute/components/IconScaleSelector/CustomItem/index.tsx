import { DeleteOutlined } from '@ant-design/icons';
import { usePrefixCls } from '@formily/antd-v5/esm/__builtins__';
import { Button, Popover, Select } from 'antd';
import cls from 'classnames';
import React, { useState, useEffect, useMemo } from 'react';
import IconPanel from '../IconPanel';
import type { CustomMappingDataItem, IconItem, IconList } from '../type';
import useStyle from './style';

type CustomItemProps = {
  value: CustomMappingDataItem;
  size?: 'small' | 'middle' | 'large';
  disabled: boolean;
  iconList: IconList;
  fieldList: { label: string; value: string }[];
  onChange: (val: CustomMappingDataItem) => void;
  onDelete: () => void;
};

const CustomItem = (props: CustomItemProps) => {
  const prefixCls = usePrefixCls('formily-icon-scale-selector-icon-item');
  const [wrapSSR, hashId] = useStyle(prefixCls);
  const {
    value: defaultValue,
    disabled = false,
    iconList = [],
    fieldList = [],
    size = 'middle',
    onChange,
    onDelete,
  } = props;
  const [open, setOpen] = useState(false);
  const [selectedCategory, setSelectedCategory] = useState<string>('');

  // 初始化选中第一个分类
  useEffect(() => {
    if (iconList.length > 0 && !selectedCategory) {
      setSelectedCategory(iconList[0].type);
    }
  }, [iconList, selectedCategory]);

  // 根据选中分类过滤图标
  const filteredIconList = useMemo(() => {
    if (!selectedCategory) return iconList;
    return iconList.filter((category) => category.type === selectedCategory);
  }, [iconList, selectedCategory]);

  // 分类选项
  const categoryOptions = useMemo(() => {
    return iconList.map((category) => ({
      label: category.type,
      value: category.type,
    }));
  }, [iconList]);

  const onIconChange = (icon: IconItem) => {
    const _itemValue = { ...defaultValue, ...icon };
    onChange(_itemValue);
    setOpen(false);
  };

  const onFieldChange = (field: string) => {
    const _itemValue = { ...defaultValue, value: field };
    onChange(_itemValue);
  };

  const onCategoryChange = (category: string) => {
    setSelectedCategory(category);
  };

  const content = () => {
    return (
      <div
        className={cls(`${prefixCls}__icon-popover`, hashId)}
        onMouseDown={(e) => {
          e.preventDefault();
          e.stopPropagation();
        }}
      >
        {/* 分类选择下拉框 */}
        <div className={cls(`${prefixCls}__category-selector`, hashId)}>
          <Select
            placeholder="选择分类"
            value={selectedCategory}
            options={categoryOptions}
            onChange={onCategoryChange}
            style={{ width: '100%' }}
            onMouseDown={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
          />
        </div>
        <IconPanel iconList={filteredIconList} onChange={onIconChange} />
      </div>
    );
  };

  return wrapSSR(
    <div className={cls(prefixCls, hashId)}>
      <div className={cls(`${prefixCls}__icon`, hashId)}>
        <Popover
          open={open}
          arrow={false}
          content={content}
          trigger="click"
          overlayClassName={cls(`${prefixCls}__icon`, hashId)}
          onOpenChange={(_open) => {
            setOpen(_open);
          }}
        >
          <img className={cls(`${prefixCls}__icon__img`, hashId)} src={defaultValue.url} />
        </Popover>
      </div>
      <div className={cls(`${prefixCls}__value`, hashId)}>
        <Select
          className={cls(`${prefixCls}__select`, hashId)}
          placeholder="请选择类型"
          size={size}
          value={defaultValue.value}
          popupMatchSelectWidth={false}
          options={fieldList}
          onMouseDown={(e) => {
            e.preventDefault();
            e.stopPropagation();
          }}
          onChange={(field: string) => onFieldChange(field)}
        />
      </div>

      <Button type="link" disabled={disabled} className={cls(`${prefixCls}__delete`, hashId)}>
        <DeleteOutlined onClick={onDelete} />
      </Button>
    </div>,
  );
};

export default CustomItem;
