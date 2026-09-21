import type { Scene } from '@antv/l7';
import { LarkMap } from '@antv/larkmap';
import type { LayerManager } from '@antv/larkmap/es/utils';
import classNames from 'classnames';
import React, { memo, useCallback, useMemo } from 'react';
import { useMapConfig } from '../../hooks/internal';
import LayerList from '../internal/LayerList';
import { CLS_PREFIX } from './constant';
import './index.less';
import type { MapContainerProps } from './types';
import { useLatestKey } from './useLatestKey';

/**
 * 地图容器组件
 * 复杂渲染地图画布区域
 */
const MapContainer: React.FC<MapContainerProps> = (props) => {
  const { className, style, onSceneLoaded, slotsElements, children } = props;
  const [mapConfig, { setScene, setLayerManager }] = useMapConfig();

  const { basemap: mapType, config: mapOptions, ...larkmapProps } = mapConfig;

  const larkMapKey = useLatestKey(mapConfig.basemap);

  // 始终启用 preserveDrawingBuffer，确保截图功能可用
  const safeMapOptions = useMemo(() => {
    const isGaode = mapType === 'Gaode';
    if (isGaode) {
      return {
        ...mapOptions,
        WebGLParams: {
          ...(mapOptions?.WebGLParams || {}),
          preserveDrawingBuffer: true,
        },
      };
    }
    return {
      ...mapOptions,
      preserveDrawingBuffer: true,
    };
  }, [mapOptions, mapType]);

  const sceneLoaded = useCallback((scene: Scene) => {
    setScene(scene);
    // 暴露到 window 供截取封面等功能使用
    (window as any).__l7_scene__ = scene;
    // 禁用右键拖拽旋转（2D 地图场景）
    try {
      const map = (scene as any).getMap?.();
      if (map) {
        // Mapbox 底图
        if (map.dragRotate) {
          map.dragRotate.disable();
        }
        // 高德/AMap 底图：多种方式尝试禁用旋转
        if (typeof map.setStatus === 'function') {
          map.setStatus({ dragRotate: false, touchZoomRotate: false });
        }
        // 通用：直接设置 dragRotate 选项
        if (map.setDragRotate) {
          map.setDragRotate(false);
        }
        // 尝试移除拖拽旋转监听
        if (map._dragRotate) {
          map._dragRotate = false;
        }
      }
    } catch (e) {
      // 忽略因底图类型差异导致的方法调用失败
    }
    onSceneLoaded?.(scene);
  }, []);

  const onLayerManagerCreated = useCallback((layerManager: LayerManager) => {
    setLayerManager(layerManager);
  }, []);

  return (
    <LarkMap
      {...larkmapProps}
      key={larkMapKey}
      className={classNames(CLS_PREFIX, className)}
      style={style}
      mapType={mapType}
      mapOptions={safeMapOptions}
      onSceneLoaded={sceneLoaded}
      onLayerManagerCreated={onLayerManagerCreated}
      {...({ dragRotate: false } as any)}
    >
      <LayerList />
      {slotsElements.content ? slotsElements.content({}) : null}
      {slotsElements.controls ? slotsElements.controls({}) : null}
      {children}
    </LarkMap>
  );
};

export default memo(MapContainer);
