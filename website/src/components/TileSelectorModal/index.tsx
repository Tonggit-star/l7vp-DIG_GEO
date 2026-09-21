import { Modal, Checkbox, List, Typography, Empty, Spin, message } from 'antd';
import { useEffect, useState } from 'react';
import { listTileConfigs } from '@/services/tile-config';
import type { TileConfig } from '@/types/tile-config';

const { Text } = Typography;

export interface SelectedTile extends TileConfig {
  checked: boolean;
}

interface TileSelectorModalProps {
  visible: boolean;
  onCancel: () => void;
  onConfirm: (selectedTiles: TileConfig[]) => void;
}

const TileSelectorModal: React.FC<TileSelectorModalProps> = ({ visible, onCancel, onConfirm }) => {
  const [tiles, setTiles] = useState<SelectedTile[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (visible) {
      setLoading(true);
      listTileConfigs().then((list) => {
        setTiles(list.map((t) => ({
          ...t,
          checked: t.isDefault === '1', // 默认瓦片预选中
        })));
        setLoading(false);
      });
    }
  }, [visible]);

  const handleOk = () => {
    const selected = tiles.filter((t) => t.checked);
    if (selected.length === 0) {
      message.warning('请至少选择一个瓦片');
      return;
    }
    onConfirm(selected);
  };

  return (
    <Modal
      title="选择底图瓦片"
      open={visible}
      onOk={handleOk}
      onCancel={onCancel}
      okText="确认选择"
      cancelText="跳过"
      width={500}
      destroyOnClose
    >
      <Spin spinning={loading}>
        {tiles.length === 0 && !loading ? (
          <Empty description="暂无可用瓦片，请先在常用瓦片中配置" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <List
            dataSource={tiles}
            renderItem={(item, index) => (
              <List.Item>
                <Checkbox
                  checked={item.checked}
                  onChange={(e) => {
                    const newTiles = [...tiles];
                    newTiles[index] = { ...newTiles[index], checked: e.target.checked };
                    setTiles(newTiles);
                  }}
                >
                  <Text strong>{item.tileName}</Text>
                  <br />
                  <Text type="secondary" style={{ fontSize: 12 }}>Z {item.minZoom ?? 0}-{item.maxZoom ?? 18}</Text>
                </Checkbox>
              </List.Item>
            )}
          />
        )}
      </Spin>
    </Modal>
  );
};

export default TileSelectorModal;
