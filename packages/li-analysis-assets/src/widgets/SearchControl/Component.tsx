import { CustomControl } from '@antv/larkmap';
import type { ImplementWidgetProps } from '@antv/li-sdk';
import { dmsToDecimal, useDataset, useLayerList, useScene } from '@antv/li-sdk';
import { AutoComplete, Input } from 'antd';
import React, { useCallback, useMemo, useState } from 'react';
import type { Properties } from './registerForm';
import useStyle from './ComponenStyle';

export interface SearchControlProps extends Properties, ImplementWidgetProps {}

/** 将坐标值转为十进制数字。useDms 为 true 时始终走 dmsToDecimal（它内部兼容纯数值和 DDD.MM 编码） */
const toDecimalCoord = (val: any, useDms: boolean): number => {
  if (val == null) return NaN;
  return useDms ? dmsToDecimal(val) : parseFloat(val);
};

const SearchControl: React.FC<SearchControlProps> = (props) => {
  const { layerId, datasetId, searchField, zoomLevel = 11, position = 'lefttop', label: widgetLabel = '搜索定位' } = props;
  const styles = useStyle();
  const [scene] = useScene();
  const layerList = useLayerList();
  const [searchText, setSearchText] = useState('');
  const [options, setOptions] = useState<{ value: string; label: string; record: any }[]>([]);

  // 通过 datasetId 直接获取数据（useDataset 对 remote 类型会触发异步查询）
  const [ds] = useDataset(datasetId || '');
  const dsData: any[] = (ds as any)?.data || [];
  console.log('[SearchControl] useDataset: datasetId=', datasetId, 'dataLen=', dsData.length);

  // 解析图层的坐标字段名
  const coordInfo = useMemo(() => {
    if (!layerId) return null;
    const layer = (layerList as any[]).find((l: any) => l.id === layerId || String(l.id) === layerId);
    if (!layer) return null;
    const opts = layer.options || {};
    const parser = opts.source?.parser || {};
    const lng = parser.x || parser.longitude;
    const lat = parser.y || parser.latitude;
    const isDms = opts.coordinateType === 'dms';
    return { lngField: lng, latField: lat, isDms };
  }, [layerList, layerId]);

  const handleSearch = useCallback((value: string) => {
    setSearchText(value);
    if (!value || !searchField || !dsData.length) {
      setOptions([]);
      return;
    }
    const keyword = value.toLowerCase();
    const matches = dsData
      .filter((row: any) => {
        const fv = row[searchField!];
        return fv != null && String(fv).toLowerCase().includes(keyword);
      })
      .slice(0, 20)
      .map((row: any, idx: number) => ({
        value: String(row[searchField!] ?? ''),
        label: String(row[searchField!] ?? ''),
        record: row,
        key: idx,
      }));
    setOptions(matches);
  }, [searchField, dsData]);

  const handleSelect = useCallback((_value: string, option: any) => {
    const record = option.record;
    if (!record || !scene || !coordInfo?.lngField || !coordInfo?.latField) return;
    const lng = toDecimalCoord(record[coordInfo.lngField], !!coordInfo.isDms);
    const lat = toDecimalCoord(record[coordInfo.latField], !!coordInfo.isDms);
    console.log('[SearchControl] handleSelect: rawLng=', record[coordInfo.lngField], 'rawLat=', record[coordInfo.latField], '→ decimal:', lng, lat);
    if (isNaN(lng) || isNaN(lat)) return;
    (scene as any).setZoomAndCenter(zoomLevel, [lng, lat]);
  }, [scene, zoomLevel, coordInfo]);

  // 只要有目标图层和搜索字段，就显示搜索框（参考 FilterControl 逻辑）
  console.log('[SearchControl] gate check: layerId=', layerId, 'searchField=', searchField, '→ willRender=', !!(layerId && searchField));
  if (!layerId || !searchField) return null;

  console.log('[SearchControl] rendering search box at position=', position, 'label=', widgetLabel);

  return (
    <CustomControl position={position}>
      <div className={styles.searchControl}>
        <div style={{ padding: '0 0 4px 4px', fontSize: 12, color: '#888', fontWeight: 500 }}>
          {widgetLabel}
        </div>
        <AutoComplete
          value={searchText}
          options={options}
          onSearch={handleSearch}
          onSelect={handleSelect}
          style={{ width: '100%' }}
          popupMatchSelectWidth
          className={styles.searchInput}
        >
          <Input.Search
            placeholder="请输入搜索关键词..."
            allowClear
            onSearch={(val: string) => handleSearch(val)}
          />
        </AutoComplete>
      </div>
    </CustomControl>
  );
};

export default SearchControl;
