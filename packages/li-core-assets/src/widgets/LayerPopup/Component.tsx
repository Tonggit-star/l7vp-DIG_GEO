import { LayerPopup } from '@antv/larkmap';
import type { ILayerField, ILayerPopupConfigItem } from '@antv/larkmap/es/components/LayerPopup/types';
import type { ImplementWidgetProps } from '@antv/li-sdk';
import { Image } from 'antd';
import cls from 'classnames';
import React, { useMemo } from 'react';
import useStyle from './ComponenStyle';
import { isImageUrl } from './helper';
import type { Properties } from './registerForm';

const CLS_PREFIX = 'li-layer-popup';
export interface LILayerPopupProps extends Properties, ImplementWidgetProps {}

const LILayerPopup: React.FC<LILayerPopupProps> = (props) => {
  const { isOpen = true, trigger, items = [] } = props;
  const styles = useStyle();
  const isClick = trigger === 'click';

  const popupItems: ILayerPopupConfigItem[] = useMemo(() => {
    const list = items
      .map((item) => {
        const fieldsMap = new Map<string, ILayerField>();
        const { layerId, fields } = item;
        fields.forEach((fieldItem) => {
          if (!fieldsMap.has(fieldItem.field)) {
            fieldsMap.set(fieldItem.field, fieldItem);
          }
        });
        const newFields = Array.from(fieldsMap.values());
        return {
          layer: layerId,
          fields: newFields,
          customContent: (feature: any) => {
            // 聚合图层(L7 cluster)节点：只有 cluster/point_count 合成字段、无原始行字段，只展示目标数量。
            // 对非聚合图层无副作用（普通 feature 无 cluster===true）。
            if (feature && feature.cluster === true) {
              const count = feature.point_count ?? feature.point_count_abbreviated ?? '';
              return (
                <div className={cls(`${CLS_PREFIX}__row_item`, styles.popupRow)}>
                  <div className={cls(`${CLS_PREFIX}__row_key`, styles.rowItem, styles.rowKey)}>目标数量</div>
                  <div className={cls(`${CLS_PREFIX}__row_value`, styles.rowItem, styles.rowValue)}>{count}</div>
                </div>
              );
            }
            return (
              <>
                {newFields.map((_item: ILayerField, index) => {
                  // formatField 已在 registerForm 的 fromValues 中自动填入数据库字段注释
                  const field = _item.formatField ? `${_item.formatField}:` : `${_item.field}:`;
                  const rawValue = feature[_item.field];
                  const value =
                    rawValue === null || rawValue === undefined
                      ? ''
                      : typeof rawValue === 'object'
                      ? JSON.stringify(rawValue)
                      : rawValue;

                  if (isImageUrl(value)) {
                    return (
                      <div className={cls(`${CLS_PREFIX}__row_image`, styles.popupRowImage)} key={index}>
                        <div className={cls(`${CLS_PREFIX}__row_key`, styles.rowItem, styles.rowKey)}>{field}</div>
                        <div className={cls(`${CLS_PREFIX}__row_value`, styles.rowItem, styles.rowValue)}>
                          <Image
                            rootClassName={styles.imagePreview}
                            referrerPolicy="no-referrer"
                            height={40}
                            src={value}
                          />
                        </div>
                      </div>
                    );
                  }

                  return (
                    <div className={cls(`${CLS_PREFIX}__row_item`, styles.popupRow)} key={index}>
                      <div className={cls(`${CLS_PREFIX}__row_key`, styles.rowItem, styles.rowKey)}>{field}</div>
                      <div className={cls(`${CLS_PREFIX}__row_value`, styles.rowItem, styles.rowValue)}>{value}</div>
                    </div>
                  );
                })}
              </>
            );
          },
        };
      })
      .filter((item) => item && item.fields.length);
    return list;
  }, [items]);

  return (
    <>
      {isOpen && (
        <LayerPopup
          className={cls(CLS_PREFIX, styles.layerPopup)}
          closeButton={isClick}
          anchor="top-left"
          offsets={[10, -10]}
          items={popupItems}
          trigger={trigger}
        />
      )}
    </>
  );
};

export default LILayerPopup;
