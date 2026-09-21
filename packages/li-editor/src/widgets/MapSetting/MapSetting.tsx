import Icon from '@ant-design/icons';
import { Button, ConfigProvider, Form, InputNumber, message, Modal, Tooltip } from 'antd';
import React, { useState } from 'react';
import classNames from 'classnames';
import { AMAP_KEY as AMAP__KEY, MAPBOX_TOKEN as MAPBOX__TOKEN } from '../../constants';
import { useEditorService, useEditorState, usePrefixCls } from '../../hooks';
import type { ImplementEditorWidgetProps } from '../../types';
import { BaseMapSvg } from './constant';
import { MapCenterModal } from './MapCenterModal';
import useStyle from './MapSerringStyle';

type MapSettingProps = ImplementEditorWidgetProps & {
  AMAP_KEY?: string;
  MAPBOX_TOKEN?: string;
};

const MapSetting: React.FC<MapSettingProps> = (props) => {
  const [isModalOpen, setIsModalOpen] = useState(false);

  const showModal = () => {
    setIsModalOpen(true);
  };
  const onClose = () => {
    setIsModalOpen(false);
  };

  return (
    <>
      <Tooltip placement="right" title="初始视野配置">
        <Button
          onClick={showModal}
          type="text"
          size="middle"
          shape="circle"
          icon={<Icon component={BaseMapSvg} style={{ fontSize: '18px' }} />}
        />
      </Tooltip>
      <MapSettingModal {...props} open={isModalOpen} onClose={onClose} />
    </>
  );
};

function MapSettingModal(props: { AMAP_KEY?: string; MAPBOX_TOKEN?: string; open: boolean; onClose: () => void }) {
  const { open, onClose } = props;
  const prefixCls = usePrefixCls('map-setting');
  const styles = useStyle();
  const context = useEditorState();
  const appService = useEditorService().appService;
  const [mapCenterModalOpen, setMapCenterModalOpen] = useState(false);
  const [zoomValue, setZoomValue] = useState(context.state.map.config.zoom);
  const [mapCenterValue, setMapCenterValue] = useState(context.state.map.config.center);
  const [messageApi, messageContextHolder] = message.useMessage();

  const setSyncMapViewState = () => {
    const viewState = appService.getMapViewState();
    if (!viewState) return;

    const { zoom, center } = viewState;
    setZoomValue(zoom);
    setMapCenterValue([center.lng, center.lat]);
    messageApi.success('拾取成功');
  };

  const handleOk = () => {
    context.updateState((draft) => {
      draft.map.config = {
        ...draft.map.config,
        zoom: zoomValue,
        center: mapCenterValue,
        dragRotate: false,
        pitchWithRotate: false,
      };
    });
    onClose();
  };

  const handleCancel = () => {
    onClose();
  };

  const mapCenterModalOk = (mapCenter?: [number, number]) => {
    if (mapCenter) {
      setMapCenterModalOpen(false);
      setMapCenterValue(mapCenter);
    }
  };

  const drawModalCancel = () => {
    setMapCenterModalOpen(false);
  };

  const onZoomChange = (zoom: number | null) => {
    if (zoom) {
      setZoomValue(zoom);
    }
  };

  return (
    <>
      <Modal okText="保存" cancelText="取消" title="地图初始位置配置" open={open} onOk={handleOk} onCancel={handleCancel}>
        <ConfigProvider componentSize="small">
          <p className={classNames(`${prefixCls}__desc`, styles.settingDesc)}>
            设置本地图进入时的初始中心点、缩放等级
          </p>

          <Form layout="vertical" className={classNames(`${prefixCls}`, styles.mapSetting)}>
            <Form.Item label="地图中心点">
              <div className={classNames(`${prefixCls}__map-content`, styles.mapContent)}>
                <div className={`${prefixCls}__map-content-text`}>{mapCenterValue?.toString()}</div>
              </div>
            </Form.Item>
            <Form.Item label="缩放等级">
              <InputNumber value={zoomValue} onChange={onZoomChange} precision={0} min={1} max={17} />
            </Form.Item>
          </Form>
          <div className={classNames(`${prefixCls}__select-map-center`, styles.selectMapCenter)}>
            <Button type="link" onClick={setSyncMapViewState}>
              拾取当前中心点
            </Button>
          </div>
        </ConfigProvider>
      </Modal>
      {messageContextHolder}
      <MapCenterModal
        title="选择中心点"
        open={mapCenterModalOpen}
        onCancel={drawModalCancel}
        onSubmit={mapCenterModalOk}
        currentMapCenter={mapCenterValue as [number, number]}
        zoomValue={zoomValue as number}
        setZoomValue={setZoomValue}
        mapCenterModalOpen={mapCenterModalOpen}
      />
    </>
  );
}

export default MapSetting;
