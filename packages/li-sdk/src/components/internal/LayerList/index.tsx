import React, { memo, useMemo } from 'react';
import { useLayerConfigList } from '../../../hooks/internal';
import WrapperLayer from '../WrapperLayer';

type LayerListProps = {};

// 瓦片类型的图层应优先添加到场景中，确保它们位于底层
const TILE_LAYER_TYPES = new Set(['TileLayer', 'RasterTileLayer', 'RasterLayer', 'MVTLayer']);

/** 图层渲染组件 */
const LayerList: React.FC<LayerListProps> = (props) => {
  const [layerConfigList] = useLayerConfigList();

  // 排序：瓦片/栅格图层优先添加（zIndex 低），然后按 zIndex 排序
  const sortedLayerList = useMemo(() => {
    return [...layerConfigList].sort((a, b) => {
      const aTile = TILE_LAYER_TYPES.has(a.type);
      const bTile = TILE_LAYER_TYPES.has(b.type);
      // 瓦片图层排前面
      if (aTile && !bTile) return -1;
      if (!aTile && bTile) return 1;
      // 同类型按 zIndex 排序
      const aZ = a.visConfig?.zIndex ?? 0;
      const bZ = b.visConfig?.zIndex ?? 0;
      return aZ - bZ;
    });
  }, [layerConfigList]);

  return (
    <React.Fragment>
      {sortedLayerList.map((layerSchema) => {
        return <WrapperLayer key={layerSchema.id} layer={layerSchema} />;
      })}
    </React.Fragment>
  );
};

export default memo(LayerList);
