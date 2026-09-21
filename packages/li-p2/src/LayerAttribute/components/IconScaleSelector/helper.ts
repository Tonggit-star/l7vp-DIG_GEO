import type { CustomMappingDataItem, IconScaleSelectorValue } from './type';

// 获取默认展示自定义数据（不再依赖内置图标列表）
export const getDefaultValue = (defaultDomain: string[]) => {
  const _options = defaultDomain.length > 5 ? defaultDomain.slice(0, 5) : defaultDomain;
  const defaultValue = _options.map((item) => {
    return {
      id: '',
      value: item,
      name: item,
      url: '',
    };
  });

  return defaultValue;
};

// 通过自定义图标映射转换为 Scale 的数据格式
// range 存储 url（与 IconSelector 一致），方便构建 iconAtlas
export const getScaleByCustomMappingData = (scaleList: CustomMappingDataItem[] = [], unknown: string) => {
  const scaleValue: IconScaleSelectorValue = {
    domain: scaleList.map((item: CustomMappingDataItem) => item.value),
    range: scaleList.map((item: CustomMappingDataItem) => item.url),
    unknown,
  };

  return scaleValue;
};

// 通过 Scale 的数据格式转换为自定义图标映射
// range 中存储的是 url，需要同时作为 id 和 url 还原
export const getCustomMappingData = (val: IconScaleSelectorValue) => {
  const { domain = [], range = [] } = val;

  const customMappingData: CustomMappingDataItem[] = range.map((_item: string, index: number) => {
    return {
      id: _item,
      url: _item,
      name: _item,
      value: domain?.[index],
    };
  });

  return customMappingData;
};
