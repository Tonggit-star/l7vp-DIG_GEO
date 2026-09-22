import { PlusOutlined } from '@ant-design/icons';
import type { DatasetField, DatasetSchema, Metadata } from '@antv/li-sdk';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Typography, message } from 'antd';
import React, { useEffect, useMemo, useState } from 'react';
import { useEditorState } from '../../../hooks';

/**
 * 编辑数据集（就地改配置，不重选数据源）
 *
 * 入口：数据集卡片（DatasetItem）的「编辑数据集」菜单项 / 悬停编辑图标。
 * 可改：名称、描述（所有数据集）；列定义（流式数据集与 remote 数据集）；
 *       流式参数（滑动窗口 maxWindow、目标标识字段 streamKey、Kafka topic/地址）；
 *       刷新间隔 refreshInterval（remote 数据集）。
 *
 * 提交时按 id **原地替换** state.datasets 中的该项（不是新增），因此
 * 绑定该数据集的图层（sourceConfig.datasetId / 顶层 dataset）引用不受影响。
 *
 * 说明：本地方非流式数据集（文件上传等）的列定义由数据行推导，故只读展示、不提供编辑。
 * 若要「换表 / 换数据源」，请用卡片 ⋯ 菜单里的「替换数据集」。
 */

type EditDatasetProps = {
  datasetId: string;
  visible: boolean;
  onClose: () => void;
};

/** 刷新间隔预设档位（秒），0 = 不自动刷新 */
const REFRESH_PRESETS: { value: number; label: string }[] = [
  { value: 0, label: '不自动刷新' },
  { value: 1, label: '每 1 秒' },
  { value: 5, label: '每 5 秒' },
  { value: 10, label: '每 10 秒' },
  { value: 30, label: '每 30 秒' },
  { value: 60, label: '每 1 分钟' },
  { value: 300, label: '每 5 分钟' },
];
const REFRESH_PRESET_VALUES = REFRESH_PRESETS.map((p) => p.value);
/** 「自定义…」的哨兵值，负数不会与真实秒数撞上 */
const REFRESH_CUSTOM = -1;

/**
 * DatasetSchema 是 local / remote / 矢量瓦片 / 栅格瓦片 的联合类型，后两者没有 columns；
 * 本弹窗只处理「有列定义」的前两类，故显式交叉一个可选 columns，避免联合类型取属性报错。
 */
type EditableDataset = DatasetSchema & {
  _stream?: boolean;
  columns?: DatasetField[];
};

interface ColumnItem {
  key: string;
  name: string;
  type: string;
  comment?: string;
}

const COLUMN_TYPES = [
  { label: '字符串 string', value: 'string' },
  { label: '数值 number', value: 'number' },
  { label: '布尔 boolean', value: 'boolean' },
  { label: '日期 date', value: 'date' },
  { label: '地理 geo', value: 'geo' },
];

const DEFAULT_MAX_WINDOW = 1000;

const EditDataset = ({ datasetId, visible, onClose }: EditDatasetProps) => {
  const { state, updateState } = useEditorState();
  const [messageApi, messageContextHolder] = message.useMessage();

  const dataset = useMemo(
    () => state.datasets.find((item) => item.id === datasetId) as EditableDataset | undefined,
    [state.datasets, datasetId],
  );

  // 流式数据集：编辑器新建的带 metadata.stream，服务端 assemble 回读的带 _stream
  const isStream = dataset?.metadata?.stream === true || dataset?._stream === true;
  const isRemote = dataset?.type === 'remote';
  // remote 的列来自中台/数据库，本地非流式的列由数据行推导 —— 只有前两类允许改列
  const canEditColumns = Boolean(isStream || isRemote);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [columns, setColumns] = useState<ColumnItem[]>([]);
  const [maxWindow, setMaxWindow] = useState<number>(DEFAULT_MAX_WINDOW);
  const [streamKey, setStreamKey] = useState<string | undefined>(undefined);
  const [kafkaTopic, setKafkaTopic] = useState('');
  const [kafkaBootstrap, setKafkaBootstrap] = useState('');
  const [refreshInterval, setRefreshInterval] = useState<number | undefined>(0);

  // 打开 / 切换到别的数据集时，用当前 schema 回填表单
  useEffect(() => {
    if (!visible || !dataset) return;
    const meta = (dataset.metadata || {}) as Record<string, any>;
    setName(meta.name || '');
    setDescription(meta.description || '');
    setColumns(
      (dataset.columns || []).map((col: DatasetField, i: number) => ({
        key: `col_${i}`,
        name: col.name,
        type: col.type || 'string',
        comment: col.displayName || '',
      })),
    );
    setMaxWindow(Number(meta.maxWindow) || DEFAULT_MAX_WINDOW);
    setStreamKey(meta.streamKey || undefined);
    setKafkaTopic(meta.kafka?.topic || '');
    setKafkaBootstrap(meta.kafka?.bootstrapServers || '');
    setRefreshInterval(meta.refreshInterval ?? 0);
  }, [visible, dataset?.id]);

  const addColumn = () => {
    setColumns((cols) => [...cols, { key: `col_${Date.now()}`, name: '', type: 'string' }]);
  };

  const removeColumn = (key: string) => {
    setColumns((cols) => cols.filter((c) => c.key !== key));
  };

  const updateColumn = (key: string, field: keyof ColumnItem, value: string) => {
    setColumns((cols) => cols.map((c) => (c.key === key ? { ...c, [field]: value } : c)));
  };

  const namedCols = columns.filter((c) => c.name && c.name.trim());

  const onConfirm = () => {
    if (!dataset) {
      onClose();
      return;
    }
    const trimmedName = name.trim();
    if (!trimmedName) {
      messageApi.error('数据集名称不能为空');
      return;
    }
    const validColumns = columns.filter((c) => c.name && c.name.trim());
    if (canEditColumns && validColumns.length === 0) {
      messageApi.error('至少保留一个字段');
      return;
    }

    // 以原 metadata 为基底，避免丢掉未暴露在表单里的键（如 _autoCreateLayers）
    // Metadata 带 [key: string]: any 索引签名，故可直接挂/删 kafka、streamKey 等自定义键
    const nextMetadata: Metadata = { ...dataset.metadata, name: trimmedName };
    if (description.trim()) {
      nextMetadata.description = description.trim();
    } else {
      delete nextMetadata.description;
    }

    if (isStream) {
      nextMetadata.stream = true;
      nextMetadata.maxWindow = maxWindow || DEFAULT_MAX_WINDOW;
      if (streamKey && streamKey.trim()) {
        nextMetadata.streamKey = streamKey.trim();
      } else {
        delete nextMetadata.streamKey;
      }
      if (kafkaTopic.trim()) {
        nextMetadata.kafka = {
          ...(nextMetadata.kafka || {}),
          topic: kafkaTopic.trim(),
          bootstrapServers: kafkaBootstrap.trim() || undefined,
          enabled: true,
        };
      } else {
        // 清空 topic = 关闭 Flink 接入，同时移除遗留的 kafka 配置
        delete nextMetadata.kafka;
      }
    }
    if (isRemote) {
      nextMetadata.refreshInterval = refreshInterval || 0;
    }

    updateState((draft) => {
      const index = draft.datasets.findIndex((item) => item.id === datasetId);
      if (index === -1) return;
      const prev = draft.datasets[index];
      draft.datasets[index] = {
        ...prev,
        metadata: nextMetadata,
        ...(canEditColumns
          ? {
              // index 不在 DatasetField 类型里，但后端 DatasetService 会优先用它做列序（缺省才退回数组下标），故保留
              columns: validColumns.map((c, i) => ({
                name: c.name.trim(),
                type: c.type as DatasetField['type'],
                index: i,
                ...(c.comment && c.comment.trim() ? { displayName: c.comment.trim() } : {}),
              })) as unknown as DatasetField[],
            }
          : {}),
      } as DatasetSchema;
    });

    messageApi.success('数据集已更新');
    onClose();
  };

  const columnTableColumns = [
    {
      title: '字段名',
      dataIndex: 'name',
      width: 180,
      render: (_: any, record: ColumnItem) =>
        canEditColumns ? (
          <Input
            size="small"
            placeholder="字段名"
            value={record.name}
            onChange={(e) => updateColumn(record.key, 'name', e.target.value)}
          />
        ) : (
          record.name
        ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 160,
      render: (_: any, record: ColumnItem) =>
        canEditColumns ? (
          <Select
            size="small"
            style={{ width: '100%' }}
            value={record.type}
            options={COLUMN_TYPES}
            onChange={(v) => updateColumn(record.key, 'type', v)}
          />
        ) : (
          record.type
        ),
    },
    {
      title: '注释',
      dataIndex: 'comment',
      render: (_: any, record: ColumnItem) =>
        canEditColumns ? (
          <Input
            size="small"
            placeholder="可选"
            value={record.comment}
            onChange={(e) => updateColumn(record.key, 'comment', e.target.value)}
          />
        ) : (
          record.comment
        ),
    },
    ...(canEditColumns
      ? [
          {
            title: '操作',
            width: 70,
            render: (_: any, record: ColumnItem) => (
              <Popconfirm title="删除该字段？" onConfirm={() => removeColumn(record.key)}>
                <Button size="small" type="link" danger>
                  删除
                </Button>
              </Popconfirm>
            ),
          },
        ]
      : []),
  ];

  return (
    <Modal
      title="编辑数据集"
      width={760}
      open={visible}
      destroyOnClose
      footer={null}
      onCancel={onClose}
      style={{ minWidth: 720 }}
    >
      {messageContextHolder}
      {!dataset ? (
        <Typography.Text type="secondary">未找到该数据集，可能已被删除。</Typography.Text>
      ) : (
        <>
          <Form labelCol={{ span: 5 }}>
            <Form.Item label="数据集名称" required>
              <Input placeholder="请输入数据集名称" value={name} onChange={(e) => setName(e.target.value)} />
            </Form.Item>
            <Form.Item label="描述" style={{ marginBottom: isStream || isRemote ? 16 : 0 }}>
              <Input placeholder="可选" value={description} onChange={(e) => setDescription(e.target.value)} />
            </Form.Item>

            {isStream && (
              <div
                style={{
                  borderTop: '1px solid #f0f0f0',
                  borderBottom: '1px solid #f0f0f0',
                  padding: '8px 0 2px',
                  marginBottom: 12,
                }}
              >
                <Typography.Text strong>流式参数</Typography.Text>
                <Form.Item
                  label="滑动窗口"
                  tooltip="默认保留最近 N 条原始报文（超出裁剪最旧）。若下方配置了目标标识字段，则此上限 = 最多同时跟踪的目标数量"
                  style={{ marginTop: 8, marginBottom: 8 }}
                >
                  <InputNumber
                    min={1}
                    max={100000}
                    step={100}
                    value={maxWindow}
                    onChange={(v) => setMaxWindow(Number(v) || DEFAULT_MAX_WINDOW)}
                    style={{ width: 200 }}
                  />
                  <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                    行
                  </Typography.Text>
                </Form.Item>
                <Form.Item
                  label="Kafka topic"
                  tooltip="消费的主题；保存项目时后端据此提交 Flink 作业。清空即关闭 Flink 接入（仍可外部用 stream/push 推数）"
                  style={{ marginBottom: 8 }}
                >
                  <Input
                    placeholder="如 vehicle-track（清空则不做 Flink 接入）"
                    value={kafkaTopic}
                    onChange={(e) => setKafkaTopic(e.target.value)}
                  />
                </Form.Item>
                <Form.Item
                  label="Kafka 地址"
                  tooltip="bootstrap servers，多个用逗号分隔；留空取后端 flink.kafka.bootstrap-servers"
                  style={{ marginBottom: 8 }}
                >
                  <Input
                    placeholder="host1:9092,host2:9092（可选，默认取后端配置）"
                    value={kafkaBootstrap}
                    onChange={(e) => setKafkaBootstrap(e.target.value)}
                  />
                </Form.Item>
                <Form.Item
                  label="目标标识字段"
                  tooltip="可选：把实时流按键去重为「每目标最新一行」（船=mmsi、飞机=flight/hex）。需填写 Kafka topic 才生效"
                  style={{ marginBottom: 8 }}
                >
                  <Select
                    allowClear
                    placeholder="留空则不做去重（原始最近 N 条）"
                    style={{ width: 320 }}
                    value={streamKey}
                    options={namedCols.map((c) => ({ label: c.name, value: c.name }))}
                    onChange={(v) => setStreamKey(v ?? undefined)}
                  />
                </Form.Item>
                {/* 保存只改数据集配置：后端按作业名幂等复用已在运行的 Flink 作业，改 topic/标识字段
                    不会自动重启作业，必须走卡片上的「重新加载流式数据」（停旧作业→清缓冲→重新提交）。 */}
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '4px 0 8px' }}>
                  改动 Kafka topic / 目标标识字段后，需点数据集卡片上的「重新加载流式数据」才会生效
                  （后端按作业名幂等复用已在运行的作业，不会自动重启）。
                </Typography.Paragraph>
              </div>
            )}

            {isRemote && (
              <Form.Item
                label="刷新间隔"
                tooltip="每到该间隔就重新从数据源拉取最新数据并重绘图层（0 表示不自动刷新）。设置随项目保存，预览页与嵌入页同样生效"
                style={{ marginBottom: 16 }}
              >
                <Space>
                  <Select
                    style={{ width: 150 }}
                    value={
                      REFRESH_PRESET_VALUES.includes(refreshInterval ?? 0)
                        ? refreshInterval ?? 0
                        : REFRESH_CUSTOM
                    }
                    onChange={(v) => {
                      if (v === REFRESH_CUSTOM) {
                        // 切到自定义时给一个非预设初值，否则 Select 会立刻弹回预设档位
                        setRefreshInterval(
                          REFRESH_PRESET_VALUES.includes(refreshInterval ?? 0)
                            ? 15
                            : refreshInterval,
                        );
                      } else {
                        setRefreshInterval(Number(v) || 0);
                      }
                    }}
                    options={[
                      ...REFRESH_PRESETS,
                      { value: REFRESH_CUSTOM, label: '自定义…' },
                    ]}
                  />
                  {!REFRESH_PRESET_VALUES.includes(refreshInterval ?? 0) && (
                    <InputNumber
                      min={1}
                      max={86400}
                      value={refreshInterval}
                      onChange={(v) => setRefreshInterval(Number(v) || 0)}
                      addonAfter="秒"
                    />
                  )}
                </Space>
              </Form.Item>
            )}
          </Form>

          <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between' }}>
            <Typography.Text strong>字段定义</Typography.Text>
            {canEditColumns && (
              <Button size="small" icon={<PlusOutlined />} onClick={addColumn}>
                添加字段
              </Button>
            )}
          </div>
          <Table
            rowKey="key"
            size="small"
            columns={columnTableColumns as any}
            dataSource={columns}
            pagination={false}
            scroll={{ y: 240 }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, fontSize: 12 }}>
            {isStream
              ? '字段名须与 Kafka 消息 JSON 的键一致；改动列定义后请重新加载流式数据。'
              : canEditColumns
              ? '列定义应与数据源实际返回的字段一致，否则图层取不到对应字段。'
              : '该类数据集的列由数据行推导，此处仅供查看。'}
            {' '}
            需要更换数据源请使用「替换数据集」。
          </Typography.Paragraph>

          <div style={{ textAlign: 'right', marginTop: 8 }}>
            <Space>
              <Button onClick={onClose}>取消</Button>
              <Button type="primary" onClick={onConfirm}>
                保存
              </Button>
            </Space>
          </div>
        </>
      )}
    </Modal>
  );
};

export default EditDataset;
