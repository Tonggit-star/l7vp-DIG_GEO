import { getUniqueId } from '@antv/li-sdk';
import { Alert, Button, Form, Input, message, Select, Space, Table, Tag } from 'antd';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useEditorService } from '../../hooks';
import type { ImplementEditorAddDatasetWidgetProps } from '../../types';

type Props = ImplementEditorAddDatasetWidgetProps;

/** 超过这个预估体积的字段会被标成「大字段」并置顶提示 */
const BIG_FIELD_BYTES = 1024 * 1024;
/** 预估总体积超过这个值就升级为警告语气 */
const WARN_PAYLOAD_BYTES = 2 * 1024 * 1024;

const formatBytes = (n: number) => {
  if (!n || n < 1024) return `${Math.round(n || 0)} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1048576).toFixed(2)} MB`;
};

export default function DatabaseDataset(props: Props) {
  const { onSubmit, onCancel } = props;
  const { appService } = useEditorService();
  const [form] = Form.useForm();
  const [connections, setConnections] = useState<any[]>([]);
  // 数据源类型元信息（显示名/提示）由后端提供，取不到就退回直接显示类型名
  const [dbTypes, setDbTypes] = useState<
    { value: string; label: string; hint?: string; relational?: boolean }[]
  >([]);
  const [tables, setTables] = useState<{ name: string; comment?: string }[]>([]);
  const [loadingTables, setLoadingTables] = useState(false);
  const [previewData, setPreviewData] = useState<any[] | null>(null);
  const [previewColumns, setPreviewColumns] = useState<any[]>([]);
  const [rowCount, setRowCount] = useState(0);
  const [selectedCols, setSelectedCols] = useState<string[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const implementDatasetService = appService.getImplementService('GET_DATABASE_DATA_LIST');
  // 检查服务是否真实存在（NOOP_SERVICE 的 metadata.name 为 undefined）
  const serviceAvailable = !!implementDatasetService?.metadata?.name;

  // 用 ref 跟踪当前值，避免闭包问题
  const selectedConnRef = useRef<string>();
  const selectedTableRef = useRef<string>();

  useEffect(() => {
    fetch('/api/db-connections')
      .then((r) => r.json())
      .then((data) => setConnections(Array.isArray(data) ? data : []))
      .catch(() => setConnections([]));
    fetch('/api/db-connections/supported-types')
      .then((r) => (r.ok ? r.json() : []))
      .then((data) => setDbTypes(Array.isArray(data) ? data : []))
      .catch(() => setDbTypes([]));
  }, []);

  const handleConnChange = useCallback((connId: string) => {
    selectedConnRef.current = connId;
    setTables([]);
    setPreviewData(null);
    setPreviewColumns([]);
    setSelectedCols([]);
    setRowCount(0);
    if (!connId) return;
    setLoadingTables(true);
    fetch(`/api/db-connections/${connId}/tables`)
      .then((r) => r.json())
      .then((data) => {
        // 兼容旧格式 (string[]) 和新格式 ([{name, comment}])
        let tableList: { name: string; comment?: string }[] = [];
        if (Array.isArray(data)) {
          tableList = data.map((item: any) =>
            typeof item === 'string' ? { name: item, comment: '' } : { name: item.name, comment: item.comment || '' },
          );
        }
        setTables(tableList);
      })
      .catch(() => setTables([]))
      .finally(() => setLoadingTables(false));
  }, []);

  const handlePreview = useCallback(async () => {
    // 从 ref 读取最新值
    const connId = selectedConnRef.current;
    const tableName = selectedTableRef.current;
    if (!connId || !tableName) {
      message.warning('请先选择数据源连接与数据表/键模式');
      return;
    }
    setPreviewLoading(true);
    try {
      const res = await fetch(`/api/db-connections/${connId}/tables/${encodeURIComponent(tableName)}/preview?limit=20`);
      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        message.error(errData.error || '预览失败');
        return;
      }
      const data = await res.json();
      const rows = data.rows || [];
      const cols = data.columns || [];
      setPreviewData(rows);
      setPreviewColumns(cols);
      // 默认全选；用户按需取消大字段
      setSelectedCols(cols.map((c: any) => c.name));
      setRowCount(data.rowCount || 0);
      if (rows.length === 0) {
        message.info('返回数据为空');
      }
    } catch (err: any) {
      message.error('预览请求失败: ' + (err.message || '网络错误'));
    } finally {
      setPreviewLoading(false);
    }
  }, []);

  /**
   * 按预览样本估算每列在**全表**下的体积。
   *
   * 这是这个弹窗存在的意义：后端是整表查询，选错一列就能让加载量差两个数量级
   * ——实测 4000 行的一张表，去掉一个 4.5KB 的 jsonb 原文列后，响应从 23MB 掉到 2.6MB。
   * 所以把「这一列大概多重」直接摆到用户面前，让他取消勾选，而不是等加载失败再猜。
   *
   * 注意要把**键名开销**算进去：JSON 行对象里每个值都要重复一遍列名，
   * 27 列 × 约 20 字节 × 4000 行 ≈ 2MB —— 窄列多的时候它比值本身还大，
   * 不计的话会把「排掉大字段之后」的体积低估好几倍。
   */
  const colStats = useMemo(() => {
    if (!previewData?.length || !previewColumns.length) return [];
    return previewColumns.map((c: any) => {
      let bytes = 0;
      for (const row of previewData) {
        const v = row[c.name];
        const valueBytes = v === null || v === undefined ? 4 : JSON.stringify(v).length;
        bytes += valueBytes + c.name.length + 4; // key 名 + 引号/冒号/逗号
      }
      const avg = bytes / previewData.length;
      return {
        name: c.name,
        type: c.type || 'string',
        comment: c.comment || '',
        estTotal: avg * rowCount,
      };
    });
  }, [previewData, previewColumns, rowCount]);

  const estBytes = useMemo(
    () => colStats.filter((c) => selectedCols.includes(c.name)).reduce((a, c) => a + c.estTotal, 0),
    [colStats, selectedCols],
  );

  const handleSubmit = useCallback(async () => {
    if (!serviceAvailable) {
      message.error('数据集服务未注册，无法创建。请刷新页面后重试。');
      return;
    }
    try {
      await form.validateFields();
    } catch {
      return;
    }
    if (previewColumns.length > 0 && selectedCols.length === 0) {
      message.warning('至少要勾选一个字段');
      return;
    }

    const values = form.getFieldsValue();
    setSubmitting(true);
    try {
      const datasetId = getUniqueId();
      const picked = previewColumns.filter((col: any) =>
        selectedCols.length === 0 ? true : selectedCols.includes(col.name),
      );
      const columns = picked.map((col: any) => ({
        name: col.name,
        type: col.type || 'string',
        displayName: col.comment || '',
      }));
      const allSelected = selectedCols.length === previewColumns.length;
      const dataset = {
        id: datasetId,
        type: 'remote' as const,
        metadata: { name: values.name, refreshInterval: 0 },
        serviceType: implementDatasetService.metadata.name,
        properties: {
          connectionId: values.connectionId,
          tableName: values.tableName,
          // 全选时不写这个字段：保持与历史数据集完全一致，也免得往项目里塞一长串列名
          ...(allSelected ? {} : { columns: selectedCols }),
        },
        columns,
      };
      onSubmit([dataset]);
    } finally {
      setSubmitting(false);
    }
  }, [implementDatasetService, form, selectedCols, previewColumns, onSubmit, serviceAvailable]);

  // 用 Form.useWatch 监听表单值：form.getFieldValue() 在 render 时只取一次、表单变化不触发重渲染，
  // 会导致「添加」按钮一直保持 disabled。useWatch 在值变化时触发重渲染（antd v5 支持）。
  const watchName = Form.useWatch('name', form);
  const watchConnId = Form.useWatch('connectionId', form);
  const watchTableName = Form.useWatch('tableName', form);
  const canSubmit = !!(watchName && watchConnId && watchTableName);

  // 连接的数据源类型决定这一屏的措辞：Redis 没有「表」，选的是「键模式」
  const selectedConn = connections.find((c: any) => c.connId === watchConnId);
  const connType: string | undefined = selectedConn?.dbType;
  const isRedis = connType === 'Redis';
  const typeMeta = dbTypes.find((t) => t.value === connType);

  // 预览表只显示勾选中的列，避免「取消了却还在预览里」的困惑
  const previewTableColumns =
    previewData && previewData.length > 0
      ? Object.keys(previewData[0])
          .filter((k) => selectedCols.length === 0 || selectedCols.includes(k))
          .map((key) => ({ title: key, dataIndex: key, key, ellipsis: true }))
      : [];

  return (
    <>
      <div style={{ width: 800 }}>
        <Form form={form} labelCol={{ span: 4 }}>
          <Form.Item name="name" label="数据集名称" rules={[{ required: true }]}>
            <Input placeholder="请输入数据集名称" />
          </Form.Item>
          <Form.Item name="connectionId" label="数据源连接" rules={[{ required: true }]}>
            <Select
              placeholder="请选择数据源连接"
              options={connections.map((c: any) => ({
                value: c.connId,
                // 类型名直接取连接上存的 dbType，不再把 MySQL 一律写死显示成 Doris
                label: `${c.connName} (${
                  dbTypes.find((t) => t.value === c.dbType)?.label || c.dbType
                })`,
              }))}
              onChange={handleConnChange}
            />
          </Form.Item>

          {connType && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message={isRedis ? 'Redis 填写提示' : `${typeMeta?.label || connType} 填写提示`}
              description={
                isRedis
                  ? 'Redis 没有库/表/字段：这里选的「键模式」决定取哪些键。下拉里是按抽样自动聚出的键族（如 aircraft:*），也支持直接输入自定义模式（如 aircraft:sim:*）。哈希键的每个字段、JSON 字符串的每个键都会成为一列，另附 redis_key 列记录键名。'
                  : typeMeta?.hint || '选好库表后点「预览数据」确认字段与数据，再添加为数据集'
              }
            />
          )}

          <Form.Item
            name="tableName"
            label={isRedis ? '键模式' : '数据表'}
            rules={[{ required: true }]}
            tooltip={isRedis ? 'Redis glob 模式，例如 aircraft:*' : undefined}
            // Redis 允许手输模式：tags 模式支持自由输入，normalize 把数组收敛成单个字符串
            getValueProps={isRedis ? (v) => ({ value: v ? [v] : [] }) : undefined}
            normalize={isRedis ? (v) => (Array.isArray(v) ? v[0] : v) : undefined}
          >
            <Select
              placeholder={isRedis ? '选择键族，或直接输入键模式' : '请选择数据表'}
              loading={loadingTables}
              showSearch
              mode={isRedis ? 'tags' : undefined}
              maxCount={isRedis ? 1 : undefined}
              filterOption={(input, option) =>
                ((option?.label as string) || '').toLowerCase().includes(input.toLowerCase())
              }
              options={tables.map((t) => ({
                value: t.name,
                label: t.comment ? `${t.name}（${t.comment}）` : t.name,
              }))}
              onChange={(val) => {
                selectedTableRef.current = Array.isArray(val) ? val[0] : val;
              }}
            />
          </Form.Item>
          <div style={{ marginBottom: 16 }}>
            <Button onClick={handlePreview} loading={previewLoading} type="default">
              预览数据
            </Button>
            {rowCount > 0 && (
              <span style={{ marginLeft: 12, color: '#52c41a' }}>
                共 {rowCount.toLocaleString()} {isRedis ? '个键' : '行'}
              </span>
            )}
          </div>
        </Form>

        {/* ===== 字段勾选：宽表必看 ===== */}
        {!isRedis && colStats.length > 0 && (
          <div style={{ marginBottom: 16 }}>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 8,
              }}
            >
              <span>
                选择字段（已选 {selectedCols.length} / {colStats.length}）
              </span>
              <Space size={4}>
                <Button size="small" onClick={() => setSelectedCols(colStats.map((c) => c.name))}>
                  全选
                </Button>
                <Button size="small" onClick={() => setSelectedCols([])}>
                  全不选
                </Button>
                <Button
                  size="small"
                  onClick={() =>
                    setSelectedCols(
                      colStats.filter((c) => !selectedCols.includes(c.name)).map((c) => c.name),
                    )
                  }
                >
                  反选
                </Button>
              </Space>
            </div>

            <Alert
              type={
                estBytes > WARN_PAYLOAD_BYTES ? 'warning' : estBytes > 0 ? 'success' : 'info'
              }
              showIcon
              style={{ marginBottom: 8 }}
              message={`按当前勾选，加载约 ${formatBytes(estBytes)}（${rowCount.toLocaleString()} 行）× ${selectedCols.length} 列`}
              description={
                estBytes > WARN_PAYLOAD_BYTES
                  ? '体积偏大：加载会明显变慢，而且请求容易被中断（中断后数据集会一直显示空）。建议取消勾选「原文 / JSON / 备注」这类大字段——它们通常只在详情弹窗里偶尔看一眼，不值得让每次上图和刷新都拖着走。'
                  : undefined
              }
            />

            <Table
              size="small"
              rowKey="name"
              dataSource={colStats}
              pagination={false}
              scroll={{ y: 200 }}
              rowSelection={{
                selectedRowKeys: selectedCols,
                preserveSelectedRowKeys: true,
                onChange: (keys) => setSelectedCols(keys as string[]),
              }}
              columns={[
                { title: '字段', dataIndex: 'name', ellipsis: true },
                { title: '类型', dataIndex: 'type', width: 76 },
                { title: '注释', dataIndex: 'comment', ellipsis: true },
                {
                  title: '预估体积',
                  dataIndex: 'estTotal',
                  width: 150,
                  render: (v: number) => (
                    <Space size={4}>
                      <span>{formatBytes(v)}</span>
                      {v > BIG_FIELD_BYTES && <Tag color="orange">大字段</Tag>}
                    </Space>
                  ),
                },
              ]}
            />
          </div>
        )}

        {previewData && previewData.length > 0 && (
          <Table
            columns={previewTableColumns}
            dataSource={previewData.slice(0, 5)}
            rowKey={(_, i) => String(i)}
            size="small"
            scroll={{ x: true, y: 160 }}
            pagination={false}
            style={{ marginBottom: 16 }}
          />
        )}
      </div>
      <div className="li-fetch-dataset__footer ant-modal-footer">
        <Space>
          <Button onClick={onCancel}>返回</Button>
          <Button disabled={!canSubmit} type="primary" onClick={handleSubmit} loading={submitting}>
            添加
          </Button>
        </Space>
      </div>
    </>
  );
}
