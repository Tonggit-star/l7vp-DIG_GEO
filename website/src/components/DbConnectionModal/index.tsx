import { DatabaseOutlined } from '@ant-design/icons';
import { Button, Form, Input, InputNumber, List, message, Modal, Select, Space, Popconfirm } from 'antd';
import { useEffect, useState } from 'react';

interface Props {
  visible: boolean;
  onVisibleChange: (v: boolean) => void;
}

export default function DbConnectionModal({ visible, onVisibleChange }: Props) {
  const [connections, setConnections] = useState<any[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  const loadConnections = () => {
    fetch('/api/db-connections')
      .then(r => r.json())
      .then(data => setConnections(Array.isArray(data) ? data : []))
      .catch(() => {});
  };

  useEffect(() => {
    if (visible) loadConnections();
  }, [visible]);

  const handleSelect = (conn: any) => {
    setSelectedId(conn.connId);
    form.setFieldsValue({
      connName: conn.connName,
      dbType: conn.dbType,
      host: conn.host,
      port: conn.port,
      username: conn.username,
      password: '',
      schemaName: conn.schemaName,
    });
  };

  const handleNew = () => {
    setSelectedId(null);
    form.resetFields();
    form.setFieldsValue({ dbType: 'MySQL', port: 9030 });
  };

  const handleDelete = async (id: string) => {
    await fetch(`/api/db-connections/${id}`, { method: 'DELETE' });
    if (selectedId === id) handleNew();
    loadConnections();
  };

  const handleSave = async () => {
    try {
      await form.validateFields();
    } catch { return; }
    setSaving(true);
    try {
      const values = form.getFieldsValue();
      const method = selectedId ? 'PUT' : 'POST';
      const url = selectedId ? `/api/db-connections/${selectedId}` : '/api/db-connections';
      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      if (res.ok) {
        message.success(selectedId ? '连接已更新' : '连接已创建');
        loadConnections();
        handleNew();
      } else {
        message.error('保存失败');
      }
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    const values = form.getFieldsValue();
    if (!values.host || !values.port || !values.username) {
      message.warning('请填写完整的连接信息');
      return;
    }
    setTesting(true);
    try {
      const res = await fetch('/api/db-connections/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      const data = await res.json();
      if (data.success) {
        message.success('连接成功');
      } else {
        message.error(data.message || '连接失败');
      }
    } catch {
      message.error('测试连接失败');
    } finally {
      setTesting(false);
    }
  };

  return (
    <Modal
      title="数据源连接配置"
      open={visible}
      onCancel={() => onVisibleChange(false)}
      footer={null}
      width={1000}
    >
      <div style={{ display: 'flex', gap: 24 }}>
        {/* 左侧连接列表 */}
        <div style={{ width: 280, borderRight: '1px solid #aaadaf', paddingRight: 16 }}>
          <Button type="primary" block onClick={handleNew} style={{ marginBottom: 12 }}>
            新增连接
          </Button>
          <List
            size="small"
            dataSource={connections}
            renderItem={(item: any) => (
              <List.Item
                onClick={() => handleSelect(item)}
                style={{
                  cursor: 'pointer',
                  background: selectedId === item.connId ? '#7b7b7c' : 'transparent',
                  color: selectedId === item.connId ? '#000' : undefined,
                  padding: '4px 4px',
                  borderRadius: 4,
                }}
                actions={[
                  <Popconfirm key="del" title="确定删除此连接?" onConfirm={() => handleDelete(item.connId)}>
                    <Button type="link" size="small" danger>删除</Button>
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  avatar={<DatabaseOutlined />}
                  title={`${item.connName} ( ${item.dbType === 'MySQL' ? 'Doris' : item.dbType} )`}
                  // description={`${item.dbType === 'MySQL' ? 'Doris' : item.dbType} | ${item.host}:${item.port}`}
                  description={`${item.host}:${item.port}`}
                />
              </List.Item>
            )}
          />
        </div>

        {/* 右侧表单 */}
        <div style={{ flex: 1 }}>
          <Form form={form} layout="vertical" initialValues={{ dbType: 'MySQL', port: 9030 }}>
            <Form.Item name="connName" label="连接名称" rules={[{ required: true }]}>
              <Input placeholder="如：中台Doris" />
            </Form.Item>
            <Form.Item name="dbType" label="数据库类型" rules={[{ required: true }]}>
              <Select options={[
                { value: 'MySQL', label: 'Doris' },
                { value: 'Dameng', label: '达梦' },
              ]} />
            </Form.Item>
            <Space style={{ display: 'flex' }}>
              <Form.Item name="host" label="主机IP" rules={[{ required: true }]}>
                <Input placeholder="10.16.1.6" />
              </Form.Item>
              <Form.Item name="port" label="端口" rules={[{ required: true }]}>
                <InputNumber min={1} max={65535} />
              </Form.Item>
            </Space>
            <Space style={{ display: 'flex' }}>
              <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
                <Input placeholder="root" />
              </Form.Item>
              <Form.Item name="password" label="密码">
                <Input.Password placeholder={selectedId ? '留空不修改' : '请输入密码'} />
              </Form.Item>
            </Space>
            <Form.Item name="schemaName" label="模式名 (Schema/Database)">
              <Input placeholder="test_db" />
            </Form.Item>
            <Space>
              <Button onClick={handleTest} loading={testing}>测试连接</Button>
              <Button type="primary" onClick={handleSave} loading={saving}>
                {selectedId ? '更新连接' : '保存连接'}
              </Button>
            </Space>
          </Form>
        </div>
      </div>
    </Modal>
  );
}
