import { getUniqueId } from '@antv/li-sdk';
import { Button, Form, Input, InputNumber, Popconfirm, Select, Space, Table, Typography } from 'antd';
import React, { useCallback, useState } from 'react';

/**
 * 流式数据集创建组件
 *
 * 用户配置：数据集名称、列定义、滑动窗口最大行数，以及（可选）Kafka topic 以启用
 * Flink 实时接入。
 * 提交后产出一个 type='local' + metadata.stream=true 的数据集（数据行不入库），
 * 由后端在 assemble 时标记 _stream=true；前端运行时通过 WebSocket 实时注入数据。
 * 若配置了 metadata.kafka.topic，后端(flink.auto-start=true)保存项目时会自动把内嵌 Flink
 * 作业 jar 提交到集群，从 Kafka 消费并批量回推本服务的 /stream/push。
 *
 * L7_INTEGRATION: 流式数据接入
 */

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
];

interface Props {
  onSubmit: (datasets: any[]) => void;
  onCancel: () => void;
}

const StreamDataset: React.FC<Props> = (props) => {
  const { onSubmit, onCancel } = props;
  const [form] = Form.useForm();
  const [datasetName, setDatasetName] = useState('');
  const [maxWindow, setMaxWindow] = useState<number>(1000);
  const [kafkaTopic, setKafkaTopic] = useState('');
  const [kafkaBootstrap, setKafkaBootstrap] = useState('');
  // L7_INTEGRATION: 目标标识字段（可选）。填了就在 Flink 作业里 keyBy 聚合为「每目标最新一行」
  // （metadata.streamKey），全量船表 replace 回推 → 每艘船/每架飞机一个实时点。
  const [streamKey, setStreamKey] = useState<string | undefined>(undefined);
  const [columns, setColumns] = useState<ColumnItem[]>([
    { key: 'col_0', name: 'lng', type: 'number' },
    { key: 'col_1', name: 'lat', type: 'number' },
  ]);

  const addColumn = useCallback(() => {
    setColumns((cols) => [
      ...cols,
      { key: `col_${Date.now()}`, name: '', type: 'string' },
    ]);
  }, []);

  const removeColumn = useCallback((key: string) => {
    setColumns((cols) => cols.filter((c) => c.key !== key));
  }, []);

  const updateColumn = useCallback((key: string, field: keyof ColumnItem, value: string) => {
    setColumns((cols) =>
      cols.map((c) => (c.key === key ? { ...c, [field]: value } : c)),
    );
  }, []);

  const handleSubmit = useCallback(async () => {
    try {
      await form.validateFields();
    } catch {
      return;
    }
    const validCols = columns.filter((c) => c.name && c.name.trim());
    if (validCols.length === 0) {
      return;
    }
    const datasetId = getUniqueId();
    const metadata: Record<string, any> = {
      name: datasetName || '流式数据集',
      stream: true,
      maxWindow,
    };
    // 目标标识字段：配置后 Flink 作业 keyBy 聚合，每船/每目标最新一行（需配合 Kafka topic）
    if (streamKey && streamKey.trim()) {
      metadata.streamKey = streamKey.trim();
    }
    // 填写了 Kafka topic 才启用 Flink 实时接入（保存项目时自动提交作业）
    if (kafkaTopic && kafkaTopic.trim()) {
      metadata.kafka = {
        topic: kafkaTopic.trim(),
        bootstrapServers: kafkaBootstrap.trim() || undefined,
        enabled: true,
      };
    }
    const dataset = {
      id: datasetId,
      type: 'local' as const,
      metadata,
      columns: validCols.map((c, i) => ({
        name: c.name.trim(),
        type: c.type || 'string',
        index: i,
        displayName: c.comment || '',
      })),
      data: [],
    };
    onSubmit([dataset]);
  }, [form, columns, datasetName, maxWindow, kafkaTopic, kafkaBootstrap, streamKey, onSubmit]);

  const canSubmit = !!datasetName && columns.some((c) => c.name && c.name.trim());

  // 已命名的列 → 「目标标识字段」下拉选项
  const namedCols = columns.filter((c) => c.name && c.name.trim());

  const tableColumns = [
    {
      title: '字段名',
      dataIndex: 'name',
      width: 160,
      render: (_: any, record: ColumnItem) => (
        <Input
          size="small"
          placeholder="字段名"
          value={record.name}
          onChange={(e) => updateColumn(record.key, 'name', e.target.value)}
        />
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 160,
      render: (_: any, record: ColumnItem) => (
        <Select
          size="small"
          style={{ width: '100%' }}
          value={record.type}
          options={COLUMN_TYPES}
          onChange={(v) => updateColumn(record.key, 'type', v)}
        />
      ),
    },
    {
      title: '注释',
      dataIndex: 'comment',
      render: (_: any, record: ColumnItem) => (
        <Input
          size="small"
          placeholder="可选"
          value={record.comment}
          onChange={(e) => updateColumn(record.key, 'comment', e.target.value)}
        />
      ),
    },
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
  ];

  return (
    <>
      <div style={{ width: 720 }}>
        <Form form={form} labelCol={{ span: 5 }}>
          <Form.Item name="name" label="数据集名称" rules={[{ required: true }]}>
            <Input placeholder="请输入数据集名称" onChange={(e) => setDatasetName(e.target.value)} />
          </Form.Item>
          <Form.Item
            label="滑动窗口"
            tooltip="默认保留最近 N 条原始报文（超出裁剪最旧）。若下方配置了目标标识字段，则此上限 = 最多同时跟踪的目标数量（每船最新一行，超出挤出最久未更新者）"
          >
            <InputNumber
              min={1}
              max={100000}
              step={100}
              value={maxWindow}
              onChange={(v) => setMaxWindow(Number(v) || 1000)}
              style={{ width: 200 }}
            />
            <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
              行
            </Typography.Text>
          </Form.Item>

          <div
            style={{
              borderTop: '1px solid #f0f0f0',
              borderBottom: '1px solid #f0f0f0',
              padding: '8px 0 2px',
              marginBottom: 12,
            }}
          >
            <Typography.Text strong>Flink 实时接入（可选）</Typography.Text>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '4px 0 8px' }}>
              填写 Kafka topic 后，保存项目时后端会把 Flink 作业提交到集群，从 Kafka 消费并实时推送本数据集
              （列名须与 Kafka 消息 JSON 的键一致）。留空则为纯 WebSocket 接入（外部程序用 stream/push 推数）。
            </Typography.Paragraph>
            <Form.Item
              label="Kafka topic"
              tooltip="消费的 Kafka 主题；需与后端集群可连通"
              style={{ marginBottom: 8 }}
            >
              <Input
                placeholder="如 vehicle-track（留空则不做 Flink 接入）"
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
              tooltip="可选：把实时流按键去重为「每目标最新一行」——地图上每艘船/每架飞机一个实时点，位置随最新报文跳变。需同时填写 Kafka topic 以启用 Flink keyBy 聚合；留空则保持原始最近 N 条。选项来自下方字段定义列名（船类如 mmsi、飞机如 flight/hex）"
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
          </div>
        </Form>

        <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between' }}>
          <Typography.Text strong>字段定义</Typography.Text>
          <Button size="small" onClick={addColumn}>
            添加字段
          </Button>
        </div>
        <Table
          rowKey="key"
          size="small"
          columns={tableColumns}
          dataSource={columns}
          pagination={false}
          scroll={{ y: 240 }}
        />

        <Typography.Paragraph type="secondary" style={{ marginTop: 12, fontSize: 12 }}>
          数据行不入库。Flink 作业把 Kafka 数据批量回推
          <Typography.Text code>POST /api/projects/&#123;projectId&#125;/datasets/&#123;datasetId&#125;/stream/push</Typography.Text>
          ，前端通过 WebSocket
          <Typography.Text code>ws://&lt;host&gt;/ws/datasets/&#123;datasetId&#125;</Typography.Text>
          实时接收并刷新图层。作业随项目保存自动提交（后端需设
          <Typography.Text code>FLINK_AUTO_START=true</Typography.Text>
          ），删除数据集/项目自动取消；手动控制：
          <Typography.Text code>stream/job/start</Typography.Text>
          、
          <Typography.Text code>stream/job/stop</Typography.Text>
          、
          <Typography.Text code>stream/job/status</Typography.Text>
          。
        </Typography.Paragraph>
      </div>
      <div className="li-fetch-dataset__footer ant-modal-footer">
        <Space>
          <Button onClick={onCancel}>返回</Button>
          <Button type="primary" disabled={!canSubmit} onClick={handleSubmit}>
            添加
          </Button>
        </Space>
      </div>
    </>
  );
};

export default StreamDataset;
