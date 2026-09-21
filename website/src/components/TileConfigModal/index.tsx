import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  Button, Form, Input, InputNumber, message, Modal, Popconfirm,
  Select, Switch, List, Typography, Empty, Spin,
} from 'antd';
import { useEffect, useState } from 'react';
import {
  listTileConfigs, createTileConfig, updateTileConfig, deleteTileConfig,
} from '@/services/tile-config';
import type { TileConfig } from '@/types/tile-config';

const { Text } = Typography;

interface TileConfigModalProps {
  visible: boolean;
  onVisibleChange: (visible: boolean) => void;
}

const TileConfigModal: React.FC<TileConfigModalProps> = ({ visible, onVisibleChange }) => {
  const [form] = Form.useForm();
  const [tiles, setTiles] = useState<TileConfig[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const loadTiles = async () => {
    setLoading(true);
    const list = await listTileConfigs();
    setTiles(list);
    if (list.length > 0 && !selectedId) {
      setSelectedId(list[0].id!);
    }
    setLoading(false);
  };

  useEffect(() => {
    if (visible) {
      loadTiles();
    }
  }, [visible]);

  useEffect(() => {
    if (selectedId) {
      const tile = tiles.find((t) => t.id === selectedId);
      if (tile) {
        form.setFieldsValue({
          tileName: tile.tileName,
          tileUrl: tile.tileUrl,
          minZoom: tile.minZoom ?? 0,
          maxZoom: tile.maxZoom ?? 18,
          isDefault: tile.isDefault === '1',
          defaultNum: tile.defaultNum || 1,
          crs: tile.crs || 'EPSG:3857',
          tileScheme: tile.tileScheme || 'XYZ',
          originLng: tile.origin ? parseFloat(tile.origin.split(',')[0]) : -180,
          originLat: tile.origin ? parseFloat(tile.origin.split(',')[1]) : 90,
        });
      }
    }
  }, [selectedId, tiles, form]);

  const selectedTile = tiles.find((t) => t.id === selectedId);

  const handleAdd = async () => {
    try {
      const newTile = await createTileConfig({
        tileName: '新瓦片',
        tileUrl: '',
        minZoom: 0,
        maxZoom: 18,
        isDefault: tiles.length === 0 ? '1' : undefined,
      });
      message.success('已新增瓦片');
      await loadTiles();
      setSelectedId(newTile.id!);
    } catch {
      message.error('新增瓦片失败');
    }
  };

  const handleSave = async () => {
    if (!selectedId) return;
    try {
      const values = await form.validateFields();
      await updateTileConfig(selectedId, {
        tileName: values.tileName,
        tileUrl: values.tileUrl,
        minZoom: values.minZoom,
        maxZoom: values.maxZoom,
        isDefault: values.isDefault ? '1' : undefined,
        defaultNum: values.isDefault ? (values.defaultNum || 1) : undefined,
        crs: values.crs || 'EPSG:3857',
        tileScheme: values.tileScheme || 'XYZ',
        origin: `${values.originLng ?? -180},${values.originLat ?? 90}`,
      });
      message.success('瓦片已保存');
      loadTiles();
    } catch (err: any) {
      if (err?.errorFields) return;
      message.error('保存失败');
    }
  };

  const handleDelete = async () => {
    if (!selectedId) return;
    try {
      await deleteTileConfig(selectedId);
      message.success('已删除瓦片');
      setSelectedId(null);
      loadTiles();
    } catch {
      message.error('删除失败');
    }
  };

  return (
    <Modal
      title="常用瓦片管理"
      open={visible}
      onCancel={() => onVisibleChange(false)}
      footer={null}
      destroyOnClose
      width={750}
      bodyStyle={{ padding: 0 }}
    >
      <div style={{ display: 'flex', height: 420 }}>
        {/* 左侧瓦片列表 */}
        <div style={{
          width: 220, borderRight: '1px solid #b3b3b3',
          display: 'flex', flexDirection: 'column', background: 'transparent',
        }}>
          <div style={{ padding: '12px', borderBottom: '1px solid #a8a8a8' }}>
            <Button type="primary" icon={<PlusOutlined />} block size="small" onClick={handleAdd}>
              新增瓦片
            </Button>
          </div>
          <div style={{ flex: 1, overflowY: 'auto' }}>
            <Spin spinning={loading}>
              {tiles.length === 0 ? (
                <Empty description="暂无瓦片" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ marginTop: 40 }} />
              ) : (
                <List
                  dataSource={tiles}
                  renderItem={(item) => (
                    <div
                      onClick={() => setSelectedId(item.id!)}
                      style={{
                        padding: '10px 12px', cursor: 'pointer',
                        background: selectedId === item.id ? '#a5a5a5' : 'transparent',
                        borderLeft: selectedId === item.id ? '5px solid #1890ff' : '5px solid transparent',
                      }}
                    >
                      <Text strong style={{ fontSize: 13, color: '#ffffff' }}>{item.tileName} 
                        {item.isDefault === '1' && (
                          <span style={{ fontSize: 10, color: '#1890ff', background: '#e6f7ff', padding: '0 4px', borderRadius: 2 }}>
                            默认
                          </span>
                      )}
                      </Text>
                      <div>
                        <Text type="secondary" style={{ fontSize: 11, color: '#595959' }} ellipsis>
                          {item.tileUrl?.substring(0, 35)}{(item.tileUrl?.length || 0) > 35 ? '...' : ''}
                        </Text>
                      </div>
                      {/* {item.isDefault === '1' && (
                        <span style={{ fontSize: 10, color: '#1890ff', background: '#e6f7ff', padding: '0 4px', borderRadius: 2 }}>
                          默认
                        </span>
                      )} */}
                    </div>
                  )}
                />
              )}
            </Spin>
          </div>
        </div>

        {/* 右侧详情 */}
        <div style={{ flex: 1, padding: '16px 20px', overflowY: 'auto' }}>
          {selectedTile ? (
            <Form form={form} layout="vertical">
              <Form.Item
                name="tileName"
                label="底图名称"
                rules={[{ required: true, message: '请输入底图名称' }]}
              >
                <Input placeholder="如：高清卫星影像" />
              </Form.Item>
              <Form.Item
                name="tileUrl"
                label="瓦片URL"
                rules={[{ required: true, message: '请输入瓦片URL' }]}
                extra="URL 需包含 {x} {y} {z} 占位符"
              >
                <Input placeholder="https://example.com/{z}/{x}/{y}" />
              </Form.Item>
              <Form.Item
                name="minZoom"
                label="最小缩放级别"
                initialValue={0}
              >
                <InputNumber min={0} max={24} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="maxZoom"
                label="最大缩放级别"
                initialValue={18}
              >
                <InputNumber min={1} max={24} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="crs"
                label="坐标系 (CRS)"
                initialValue="EPSG:3857"
                extra="瓦片的坐标参考系"
              >
                <Select
                  options={[
                    { value: 'EPSG:3857', label: 'EPSG:3857 (Web墨卡托)' },
                    { value: 'EPSG:4326', label: 'EPSG:4326 (地理坐标)' },
                  ]}
                />
              </Form.Item>
              <Form.Item
                name="tileScheme"
                label="编号方式"
                initialValue="XYZ"
                extra="XYZ: Y轴从上往下; TMS: Y轴从下往上"
              >
                <Select
                  options={[
                    { value: 'XYZ', label: 'XYZ' },
                    { value: 'TMS', label: 'TMS' },
                  ]}
                />
              </Form.Item>
              <Form.Item label="瓦片原点" extra="默认 (-180, 90)，瓦片服务原点坐标">
                <div style={{ display: 'flex', gap: 8 }}>
                  <Form.Item name="originLng" noStyle initialValue={-180}>
                    <InputNumber style={{ flex: 1 }} placeholder="经度" />
                  </Form.Item>
                  <Form.Item name="originLat" noStyle initialValue={90}>
                    <InputNumber style={{ flex: 1 }} placeholder="纬度" />
                  </Form.Item>
                </div>
              </Form.Item>
              <Form.Item
                name="isDefault"
                label="设为默认瓦片"
                valuePropName="checked"
                extra="新建项目时默认使用该瓦片"
              >
                <Switch />
              </Form.Item>
              <Form.Item noStyle shouldUpdate={(prev, cur) => prev.isDefault !== cur.isDefault}>
                {({ getFieldValue }) =>
                  getFieldValue('isDefault') ? (
                    <Form.Item
                      name="defaultNum"
                      label="默认层级"
                      initialValue={1}
                      tooltip="小号图层在下层"
                    >
                      <InputNumber min={1} max={99} style={{ width: '100%' }} placeholder="数字越小越在下层" />
                    </Form.Item>
                  ) : null
                }
              </Form.Item>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <Popconfirm title="确定删除该瓦片？" onConfirm={handleDelete}>
                  <Button danger icon={<DeleteOutlined />} size="small">删除</Button>
                </Popconfirm>
                <Button type="primary" onClick={handleSave}>保存修改</Button>
              </div>
            </Form>
          ) : (
            <Empty description="请选择左侧瓦片或新增" image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ marginTop: 80 }} />
          )}
        </div>
      </div>
    </Modal>
  );
};

export default TileConfigModal;
