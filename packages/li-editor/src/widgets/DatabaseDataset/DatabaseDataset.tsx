import { getUniqueId } from '@antv/li-sdk';
import { Button, Form, Input, message, Select, Space, Table } from 'antd';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useEditorService } from '../../hooks';
import type { ImplementEditorAddDatasetWidgetProps } from '../../types';

type Props = ImplementEditorAddDatasetWidgetProps;

export default function DatabaseDataset(props: Props) {
  const { onSubmit, onCancel } = props;
  const { appService } = useEditorService();
  const [form] = Form.useForm();
  const [connections, setConnections] = useState<any[]>([]);
  const [tables, setTables] = useState<{ name: string; comment?: string }[]>([]);
  const [loadingTables, setLoadingTables] = useState(false);
  const [previewData, setPreviewData] = useState<any[] | null>(null);
  const [previewColumns, setPreviewColumns] = useState<any[]>([]);
  const [rowCount, setRowCount] = useState(0);
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
  }, []);

  const handleConnChange = useCallback((connId: string) => {
    selectedConnRef.current = connId;
    setTables([]);
    setPreviewData(null);
    setPreviewColumns([]);
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
      message.warning('请先选择数据库连接和数据表');
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
      setPreviewData(rows);
      setPreviewColumns(data.columns || []);
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

    const values = form.getFieldsValue();
    setSubmitting(true);
    try {
      const datasetId = getUniqueId();
      // 将后端返回的列元数据（含注释）转为 DatasetField[] 格式
      const columns = previewColumns.map((col: any) => ({
        name: col.name,
        type: col.type || 'string',
        displayName: col.comment || '',
      }));
      const dataset = {
        id: datasetId,
        type: 'remote' as const,
        metadata: { name: values.name, refreshInterval: 0 },
        serviceType: implementDatasetService.metadata.name,
        properties: {
          connectionId: values.connectionId,
          tableName: values.tableName,
        },
        columns,
      };
      onSubmit([dataset]);
    } finally {
      setSubmitting(false);
    }
  }, [implementDatasetService, form, rowCount, onSubmit]);

  // 用 Form.useWatch 监听表单值：form.getFieldValue() 在 render 时只取一次、表单变化不触发重渲染，
  // 会导致「添加」按钮一直保持初始 disabled。useWatch 在值变化时触发重渲染（antd v5 支持）。
  const watchName = Form.useWatch('name', form);
  const watchConnId = Form.useWatch('connectionId', form);
  const watchTableName = Form.useWatch('tableName', form);
  const canSubmit = !!(watchName && watchConnId && watchTableName);
  const columns =
    previewData && previewData.length > 0
      ? Object.keys(previewData[0]).map((key) => ({ title: key, dataIndex: key, key, ellipsis: true }))
      : [];

  return (
    <>
      <div style={{ width: 800 }}>
        <Form form={form} labelCol={{ span: 4 }}>
          <Form.Item name="name" label="数据集名称" rules={[{ required: true }]}>
            <Input placeholder="请输入数据集名称" />
          </Form.Item>
          <Form.Item name="connectionId" label="数据库连接" rules={[{ required: true }]}>
            <Select
              placeholder="请选择数据库连接"
              options={connections.map((c: any) => ({
                value: c.connId,
                label: `${c.connName} (${c.dbType === 'MySQL' ? 'Doris' : c.dbType})`,
              }))}
              onChange={handleConnChange}
            />
          </Form.Item>
          <Form.Item name="tableName" label="数据表" rules={[{ required: true }]}>
            <Select
              placeholder="请选择数据表"
              loading={loadingTables}
              showSearch
              filterOption={(input, option) =>
                ((option?.label as string) || '').toLowerCase().includes(input.toLowerCase())
              }
              options={tables.map((t) => ({
                value: t.name,
                label: t.comment ? `${t.name}（${t.comment}）` : t.name,
              }))}
              onChange={(val) => {
                selectedTableRef.current = val;
              }}
            />
          </Form.Item>
          <div style={{ marginBottom: 16 }}>
            <Button onClick={handlePreview} loading={previewLoading} type="default">
              预览数据
            </Button>
            {rowCount > 0 && (
              <span style={{ marginLeft: 12, color: '#52c41a' }}>共 {rowCount.toLocaleString()} 行</span>
            )}
          </div>
        </Form>
        {previewData && previewData.length > 0 && (
          <Table
            columns={columns}
            dataSource={previewData}
            rowKey={(_, i) => String(i)}
            size="small"
            scroll={{ x: true, y: 200 }}
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
