import { getUniqueId } from '@antv/li-sdk';
import { Button, Form, Input, Menu, message, Space, Table, Typography } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import { useEditorService } from '../../hooks';
import type { ImplementEditorAddDatasetWidgetProps } from '../../types';
import { getZhongtaiSpaceId } from '../zhongtai';

type Props = ImplementEditorAddDatasetWidgetProps;

interface DatabaseItem {
  instanceId: string;
  instanceName?: string;
  instanceDisplayName?: string;
  instanceCode?: string;
  storageLayer?: string;
  storageLayerName?: string;
  dbname?: string;
}

interface ResourceItem {
  resourceId: string;
  resourceName: string;
  resourceCode: string;
  resourceType: string;
  datasourceId: string;
  datasourceName: string;
  datasourceCode: string;
  typeCode: string;
  tableName?: string;
  catalog?: string;
  storageLayer?: string;
  storageLayerName?: string;
  resourceDesp?: string;
  columnsTotal?: number;
  lastUpdateTime?: string;
}

export default function ZhongtaiTableDataset(props: Props) {
  const { onSubmit, onCancel } = props;
  const { appService } = useEditorService();
  const [form] = Form.useForm();

  // 库列表（左侧）
  const [databases, setDatabases] = useState<DatabaseItem[]>([]);
  const [loadingDatabases, setLoadingDatabases] = useState(false);
  const [selectedDbId, setSelectedDbId] = useState<string | null>(null);

  // 表列表（右侧）
  const [resources, setResources] = useState<ResourceItem[]>([]);
  const [loadingResources, setLoadingResources] = useState(false);
  const [searchInput, setSearchInput] = useState('');
  const [searchText, setSearchText] = useState('');
  const [pageIndex, setPageIndex] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);

  // 选中的表 + 预览
  const [selectedResource, setSelectedResource] = useState<ResourceItem | null>(null);
  const [previewData, setPreviewData] = useState<any[] | null>(null);
  const [previewColumns, setPreviewColumns] = useState<any[]>([]);
  const [rowCount, setRowCount] = useState(0);
  const [previewLoading, setPreviewLoading] = useState(false);

  const [datasetName, setDatasetName] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const implementDatasetService = appService.getImplementService('GET_ZHONGTAI_TABLE_DATA_LIST');
  const serviceAvailable = !!implementDatasetService?.metadata?.name;

  const dbLabel = useCallback((db: DatabaseItem) => {
    return db.instanceDisplayName || db.instanceName || db.dbname || db.instanceCode || db.instanceId;
  }, []);

  // 加载库列表（左侧）
  const loadDatabases = useCallback(async () => {
    setLoadingDatabases(true);
    try {
      const res = await fetch('/api/zhongtai/databases/list', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scopeType: 'Space', spaceId: getZhongtaiSpaceId() }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error || `请求失败 (HTTP ${res.status})`);
      }
      const dbs: DatabaseItem[] = data.databases || [];
      setDatabases(dbs);
      // 默认选中第一个库（有权限的库中台已过滤）
      if (dbs.length > 0) {
        setSelectedDbId(dbs[0].instanceId);
      }
    } catch (err: any) {
      message.error('获取库列表失败: ' + (err.message || '网络错误'));
      setDatabases([]);
    } finally {
      setLoadingDatabases(false);
    }
  }, []);

  // 加载表列表（右侧），按库 + 搜索 + 分页
  const loadResources = useCallback(async () => {
    if (!selectedDbId) return;
    setLoadingResources(true);
    try {
      const res = await fetch('/api/zhongtai/resources/list', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          scopeType: 'Space',
          spaceId: getZhongtaiSpaceId(),
          dataSourceId: selectedDbId,
          searchText: searchText || undefined,
          pageIndex,
          pageSize,
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error || `请求失败 (HTTP ${res.status})`);
      }
      const list: ResourceItem[] = data.resources || [];
      const tables = list.filter((r: ResourceItem) => r.resourceType === 'table' || !r.resourceType);
      setResources(tables);
      setTotal(typeof data.total === 'number' ? data.total : tables.length);
    } catch (err: any) {
      message.error('获取数据表列表失败: ' + (err.message || '网络错误'));
      setResources([]);
      setTotal(0);
    } finally {
      setLoadingResources(false);
    }
  }, [selectedDbId, searchText, pageIndex, pageSize]);

  useEffect(() => {
    loadDatabases();
  }, [loadDatabases]);

  useEffect(() => {
    if (selectedDbId) {
      loadResources();
    }
  }, [selectedDbId, searchText, pageIndex, pageSize, loadResources]);

  // 搜索防抖：300ms
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearchText(searchInput.trim());
      setPageIndex(1);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  const handleDatabaseSelect = useCallback((dbId: string) => {
    setSelectedDbId(dbId);
    setSearchInput('');
    setSearchText('');
    setPageIndex(1);
    setSelectedResource(null);
    setPreviewData(null);
    setPreviewColumns([]);
    setRowCount(0);
  }, []);

  const handleResourceSelect = useCallback(
    (resource: ResourceItem | null) => {
      setSelectedResource(resource);
      setPreviewData(null);
      setPreviewColumns([]);
      setRowCount(0);
      // 选中表时若数据集名称为空则自动带出表名（可改），否则添加按钮因名称空一直 disabled
      // 与中台API数据集组件(ZhongtaiApiDataset)行为保持一致；不覆盖用户已手动输入的名称
      if (resource) {
        const currentName = form.getFieldValue('name');
        if (!currentName) {
          form.setFieldsValue({ name: resource.resourceName });
          setDatasetName(resource.resourceName);
        }
      }
    },
    [form],
  );

  const handlePreview = useCallback(async () => {
    if (!selectedResource) {
      message.warning('请先选择数据表');
      return;
    }
    setPreviewLoading(true);
    try {
      const res = await fetch('/api/zhongtai/resources/data', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          datasourceId: selectedResource.datasourceId,
          resourceName: selectedResource.tableName || selectedResource.resourceName,
          limit: 20,
        }),
      });
      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        message.error(errData.error || '预览失败');
        return;
      }
      const data = await res.json();
      setPreviewData(data.rows || []);
      setPreviewColumns(data.columns || []);
      setRowCount(data.rowCount || 0);
      if ((data.rows || []).length > 0) {
        message.success('预览成功，共 ' + (data.rowCount || 0).toLocaleString() + ' 行（显示前20行）');
      } else {
        message.info('返回数据为空');
      }
    } catch (err: any) {
      message.error('预览请求失败: ' + (err.message || '网络错误'));
    } finally {
      setPreviewLoading(false);
    }
  }, [selectedResource]);

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
    if (!selectedResource) {
      message.error('请先选择数据表');
      return;
    }
    const values = form.getFieldsValue();
    setSubmitting(true);
    try {
      const datasetId = getUniqueId();
      const columns = previewColumns.map((col: any) => ({
        name: col.name,
        type: col.type || 'string',
        displayName: col.comment || '',
      }));
      const dataset = {
        id: datasetId,
        type: 'remote' as const,
        metadata: { name: values.name || selectedResource.resourceName, refreshInterval: 0 },
        serviceType: implementDatasetService.metadata.name,
        properties: {
          datasourceId: selectedResource.datasourceId,
          resourceName: selectedResource.tableName || selectedResource.resourceName,
          resourceId: selectedResource.resourceId,
        },
        columns,
      };
      onSubmit([dataset]);
    } finally {
      setSubmitting(false);
    }
  }, [implementDatasetService, form, selectedResource, previewColumns, onSubmit]);

  const canSubmit = !!datasetName && !!selectedResource;

  const resourceColumns = [
    { title: '表名', dataIndex: 'resourceName', key: 'resourceName', ellipsis: true },
    { title: '物理表名', dataIndex: 'tableName', key: 'tableName', ellipsis: true },
    { title: '注释', dataIndex: 'resourceDesp', key: 'resourceDesp', ellipsis: true },
    { title: '列数', dataIndex: 'columnsTotal', key: 'columnsTotal', width: 70 },
  ];

  const previewTableColumns =
    previewData && previewData.length > 0
      ? Object.keys(previewData[0]).map((key) => ({ title: key, dataIndex: key, key, ellipsis: true }))
      : [];

  return (
    <>
      <div style={{ width: 900 }}>
        <Form form={form} labelCol={{ span: 4 }}>
          <Form.Item name="name" label="数据集名称" rules={[{ required: true }]}>
            <Input placeholder="请输入数据集名称" onChange={(e) => setDatasetName(e.target.value)} />
          </Form.Item>
        </Form>

        {/* 两级选择：左库列表 + 右表列表 */}
        <div
          style={{
            display: 'flex',
            border: '1px solid #f0f0f0',
            borderRadius: 4,
            height: 320,
            overflow: 'hidden',
          }}
        >
          {/* 左：库列表 */}
          <div
            style={{
              width: 220,
              flexShrink: 0,
              borderRight: '1px solid #f0f0f0',
              overflow: 'auto',
              padding: '4px 0',
            }}
          >
            <div
              style={{
                padding: '8px 16px',
                fontSize: 12,
                color: '#999',
                borderBottom: '1px solid #f0f0f0',
              }}
            >
              库列表
            </div>
            {loadingDatabases ? (
              <div style={{ textAlign: 'center', color: '#999', padding: 16 }}>加载中...</div>
            ) : databases.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#999', padding: 16 }}>暂无可用库</div>
            ) : (
              <Menu
                mode="inline"
                selectedKeys={selectedDbId ? [selectedDbId] : []}
                onClick={({ key }) => handleDatabaseSelect(key)}
                items={databases.map((db) => ({
                  key: db.instanceId,
                  label: dbLabel(db),
                }))}
                style={{ border: 'none' }}
              />
            )}
          </div>

          {/* 右：搜索 + 表列表 */}
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            <div style={{ padding: 8 }}>
              <Input.Search
                placeholder="搜索表名 / 注释"
                allowClear
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
              />
            </div>
            <Table
              rowKey="resourceId"
              size="small"
              loading={loadingResources}
              columns={resourceColumns}
              dataSource={resources}
              rowSelection={{
                type: 'radio',
                selectedRowKeys: selectedResource ? [selectedResource.resourceId] : [],
                onChange: (keys) => {
                  const rid = keys[0] as string;
                  const resource = resources.find((r) => r.resourceId === rid) || null;
                  handleResourceSelect(resource);
                },
              }}
              pagination={{
                current: pageIndex,
                pageSize,
                total,
                showSizeChanger: true,
                pageSizeOptions: ['10', '20', '50'],
                showTotal: (t) => `共 ${t} 条`,
                onChange: (p, ps) => {
                  setPageIndex(p);
                  setPageSize(ps);
                },
                onShowSizeChange: (p, ps) => {
                  setPageIndex(p);
                  setPageSize(ps);
                },
              }}
              scroll={{ y: 220 }}
              locale={{ emptyText: '未选择库或该库无数据表' }}
            />
          </div>
        </div>

        {/* 选中资源信息 */}
        {selectedResource && (
          <div style={{ marginTop: 12, color: '#888', fontSize: 12 }}>
            <Typography.Text type="secondary">
              数据源: {selectedResource.datasourceName} ({selectedResource.typeCode})
              {selectedResource.catalog && ' | 库: ' + selectedResource.catalog}
              {selectedResource.storageLayerName && ' | 分层: ' + selectedResource.storageLayerName}
              {selectedResource.columnsTotal != null && ' | ' + selectedResource.columnsTotal + ' 列'}
              {selectedResource.lastUpdateTime && ' | 更新: ' + selectedResource.lastUpdateTime}
            </Typography.Text>
          </div>
        )}

        <div style={{ marginTop: 12 }}>
          <Button onClick={handlePreview} loading={previewLoading} type="default">
            预览数据
          </Button>
          {rowCount > 0 && <span style={{ marginLeft: 12, color: '#52c41a' }}>共 {rowCount.toLocaleString()} 行</span>}
        </div>

        {previewData && previewData.length > 0 && (
          <Table
            columns={previewTableColumns}
            dataSource={previewData}
            rowKey={(_, i) => String(i)}
            size="small"
            scroll={{ x: true, y: 200 }}
            pagination={false}
            style={{ marginTop: 12 }}
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
