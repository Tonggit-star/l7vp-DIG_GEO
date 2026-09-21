import { PlusOutlined } from '@ant-design/icons';
import { usePrefixCls } from '@formily/antd-v5/esm/__builtins__';
import { connect } from '@formily/react';
import { Button, Select, Spin } from 'antd';
import cls from 'classnames';
import React, { useEffect, useMemo, useState } from 'react';
import CustomItem from './CustomItem';
import { getCustomMappingData, getDefaultValue, getScaleByCustomMappingData } from './helper';
import useStyle from './style';
import type { CustomMappingDataItem, IconList, IconScaleSelectorValue } from './type';

type IconScaleSelectorProps = {
  /**
   * 自定义参数值
   */
  domain: string[];
  value: IconScaleSelectorValue;
  onChange: (val: IconScaleSelectorValue) => void;
};

const DEfAULT_UNKONW_ICON = '/icons/default-icon.svg';

// 从API获取图标列表
const fetchIconList = async (): Promise<IconList> => {
  try {
    const response = await fetch('/api/icons');
    if (!response.ok) {
      console.warn('获取图标列表失败，使用默认图标');
      return ([] as IconList);
    }
    const data = await response.json();
    if (!Array.isArray(data) || data.length === 0) {
      return ([] as IconList);
    }
    // 转换为 IconList 格式
    return data.map((category: any) => ({
      type: category.type || '默认',
      icons: (category.icons || []).map((icon: any) => ({
        id: icon.id,
        name: icon.name,
        url: icon.url,
      })),
    }));
  } catch (error) {
    console.error('获取图标列表失败:', error);
    return ([] as IconList);
  }
};

const Internal = (props: IconScaleSelectorProps) => {
  const prefixCls = usePrefixCls('formily-icon-scale-selector');
  const [wrapSSR, hashId] = useStyle(prefixCls);
  const { domain = [], value: defaultValue, onChange } = props;
  const [open, setOpen] = useState(false);
  const [unknownIcon, setUnknownIcon] = useState<string>(DEfAULT_UNKONW_ICON);
  const [iconList, setIconList] = useState<IconList>(([] as IconList));
  const [loading, setLoading] = useState(false);

  // 组件初始化时获取图标列表
  useEffect(() => {
    setLoading(true);
    fetchIconList()
      .then((list) => {
        setIconList(list);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const defaultCustomMappingData = useMemo(() => {
    if (defaultValue && defaultValue.range) {
      return getCustomMappingData(defaultValue);
    }
    return [];
  }, [defaultValue]);

  const [customMappingIcons, setCustomMappingIcons] = useState(defaultCustomMappingData);

  // 仅在已有有效 range 值时还原（不再自动填充空图标，避免误选高基数字段时大量空白占位）

  const onAddItem = () => {
    const selectedIcon = customMappingIcons.map((item) => item.id);
    // 从当前图标列表中选择
    const flatIcons = iconList.map((item) => item.icons).flat();
    const unselectedIcon = flatIcons.filter((item) => !selectedIcon.includes(item.id));
    const defaultIcon = unselectedIcon[0] || flatIcons[0];

    if (defaultIcon) {
      setCustomMappingIcons((pre) =>
        pre.concat({
          id: defaultIcon.id,
          url: defaultIcon.url,
          value: '',
          name: defaultIcon.id,
        }),
      );
    }
  };

  const onItemChange = (val: CustomMappingDataItem, index: number) => {
    const _scaleList = customMappingIcons.map((item, _index) => (_index === index ? val : item));
    setCustomMappingIcons(_scaleList);
  };

  const onItemDelete = (index: number) => {
    const _scaleList = customMappingIcons.filter((_, _index) => _index !== index);
    setCustomMappingIcons(_scaleList);
  };

  const onSubmit = () => {
    const scaleValue = getScaleByCustomMappingData(customMappingIcons, unknownIcon);
    onChange(scaleValue);
    setOpen(false);
  };

  const fieldList = useMemo(() => {
    if (!domain.length) {
      return [];
    }

    return domain.map((item) => ({ label: item, value: item }));
  }, [domain]);

  const selectedIconList = useMemo(() => {
    if (!customMappingIcons) {
      return [];
    }
    return [{ value: 'selectedIcon', label: customMappingIcons.map((item) => item.url) }];
  }, [customMappingIcons]);

  return wrapSSR(
    <Select
      className={cls(`${prefixCls}`, hashId)}
      open={open}
      onDropdownVisibleChange={(visible) => setOpen(visible)}
      dropdownRender={() => {
        return (
          <>
            {loading && (
              <div style={{ textAlign: 'center', padding: '20px' }}>
                <Spin size="small" />
              </div>
            )}
            {!loading &&
              customMappingIcons?.map((item: CustomMappingDataItem, index: number) => {
                const selected = customMappingIcons.map((icon) => {
                  if (icon.value !== item.value) {
                    return icon.value;
                  }
                });
                const _options = fieldList.filter((_item) => !selected.includes(_item.value));

                return (
                  <div className={cls(`${prefixCls}__customItem`, hashId)} key={item.id}>
                    <CustomItem
                      key={item.id}
                      size="small"
                      value={item}
                      disabled={customMappingIcons.length <= 1}
                      iconList={iconList}
                      fieldList={_options}
                      onChange={(val: CustomMappingDataItem) => onItemChange(val, index)}
                      onDelete={() => onItemDelete(index)}
                    />
                  </div>
                );
              })}

            {/* 由于 unknow 变化更新不及时，其他暂时隐藏 */}
            {/* <UnknownIconItem
              size="small"
              value={unknownIcon}
              iconList={DEFAULTICONOPTIONS}
              onChange={({ title, icon }) => setUnknownIcon({ title, icon })}
            /> */}

            <Button className={cls(`${prefixCls}__add-item`, hashId)} size="small" type="link" onClick={onAddItem}>
              <PlusOutlined /> 添加
            </Button>

            <div className={cls(`${prefixCls}__btn`, hashId)}>
              <span onClick={onSubmit}>应用</span>
            </div>
          </>
        );
      }}
      value={selectedIconList[0]?.value}
    >
      {selectedIconList.map((item) => {
        return (
          <Select.Option key={item.value} value={item.value}>
            {item.label.map((icon, index) => (
              <img src={icon} key={index} />
            ))}
          </Select.Option>
        );
      })}
    </Select>,
  );
};

const IconScaleSelector = connect(Internal);

export default IconScaleSelector;
