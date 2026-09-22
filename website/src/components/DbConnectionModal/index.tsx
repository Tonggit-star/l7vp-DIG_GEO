import { DatabaseOutlined, InfoCircleOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  List,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Tooltip,
} from 'antd';
import { useEffect, useMemo, useState } from 'react';

interface Props {
  visible: boolean;
  onVisibleChange: (v: boolean) => void;
}

interface DbTypeMeta {
  value: string;
  label: string;
  defaultPort: number;
  schemaLabel: string;
  relational: boolean;
  hint: string;
}

/**
 * 兜底类型表。正常情况下类型/默认端口/提示都从后端
 * `GET /api/db-connections/supported-types` 取（单一来源，避免前后端各写一份漂移），
 * 这份只在接口不可用时顶上，保证弹窗仍然可用。
 */
const FALLBACK_TYPES: DbTypeMeta[] = [
  {
    value: 'Dameng',
    label: '达梦 DM8',
    defaultPort: 5236,
    schemaLabel: '模式名 (Schema)',
    relational: true,
    hint: '模式名必填（如 DIG_GEO / TEST）',
  },
  {
    value: 'Doris',
    label: 'Doris / StarRocks',
    defaultPort: 9030,
    schemaLabel: '数据库名 (Database)',
    relational: true,
    hint: '走 MySQL 协议，FE 查询端口默认 9030',
  },
  {
    value: 'MySQL',
    label: 'MySQL / MariaDB',
    defaultPort: 3306,
    schemaLabel: '数据库名 (Database)',
    relational: true,
    hint: '库名必填',
  },
  {
    value: 'PostgreSQL',
    label: 'PostgreSQL',
    defaultPort: 5432,
    schemaLabel: '数据库名 (Database)',
    relational: true,
    hint: '库名必填；表列表默认取 public',
  },
  {
    value: 'Redis',
    label: 'Redis',
    defaultPort: 6379,
    schemaLabel: '库序号 (db index)',
    relational: false,
    hint: '非关系库，没有库/表/字段，建数据集时选的是「键模式」',
  },
];

export default function DbConnectionModal({ visible, onVisibleChange }: Props) {
  const [connections, setConnections] = useState<any[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [dbTypes, setDbTypes] = useState<DbTypeMeta[]>(FALLBACK_TYPES);
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  // 用 useWatch 让「类型相关的标签/提示/必填项」随选择实时变化
  const watchDbType = Form.useWatch('dbType', form);
  const currentType = useMemo(
    () => dbTypes.find((t) => t.value === watchDbType) || dbTypes[0],
    [dbTypes, watchDbType],
  );
  const typeLabelOf = (value: string) => dbTypes.find((t) => t.value === value)?.label || value;

  const loadConnections = () => {
    fetch('/api/db-connections')
      .then((r) => r.json())
      .then((data) => setConnections(Array.isArray(data) ? data : []))
      .catch(() => {});
  };

  const loadDbTypes = () => {
    fetch('/api/db-connections/supported-types')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error('bad status'))))
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) setDbTypes(data);
      })
      .catch(() => {
        // 后端不可用就继续用兜底表，不打断用户
      });
  };

  useEffect(() => {
    if (visible) {
      loadConnections();
      loadDbTypes();
    }
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
    form.setFieldsValue({ dbType: 'MySQL', port: 3306 });
  };

  const handleTypeChange = (value: string) => {
    const meta = dbTypes.find((t) => t.value === value);
    if (meta) {
      // 换类型时把端口带成该类型的默认值——各库默认端口差别很大，
      // 留着上一个类型的值最容易连错（Doris 9030 vs MySQL 3306 尤其像）。
      form.setFieldsValue({ port: meta.defaultPort });
    }
  };

  const handleDelete = async (id: string) => {
    await fetch(`/api/db-connections/${id}`, { method: 'DELETE' });
    if (selectedId === id) handleNew();
    loadConnections();
  };

  /** 与后端 sanitize() 的必填规则保持一致，避免「保存时才报错」 */
  const validateForSubmit = (values: any): string | null => {
    const meta = dbTypes.find((t) => t.value === values.dbType);
    if (!values.connName) return '请填写连接名称';
    if (!values.host) return '请填写主机 IP';
    if (!values.port) return '请填写端口';
    if (meta?.relational) {
      if (!values.username) return '请填写用户名';
      if (!values.schemaName) return `请填写${meta.schemaLabel}`;
    } else if (values.schemaName && !/^\d+$/.test(String(values.schemaName).trim())) {
      // Redis 的这一栏是库序号，必须是数字
      return 'Redis 的「库序号」只能填数字（0-15，留空按 0）';
    }
    return null;
  };

  const handleSave = async () => {
    try {
      await form.validateFields();
    } catch {
      return;
    }
    const values = form.getFieldsValue();
    const invalid = validateForSubmit(values);
    if (invalid) {
      message.warning(invalid);
      return;
    }
    setSaving(true);
    try {
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
        const data = await res.json().catch(() => ({}));
        message.error(data.message || data.error || '保存失败');
      }
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    const values = form.getFieldsValue();
    const invalid = validateForSubmit(values);
    if (invalid) {
      message.warning(invalid);
      return;
    }
    setTesting(true);
    try {
      const res = await fetch('/api/db-connections/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      const data = await res.json().catch(() => ({}));
      if (data.success) {
        message.success(
          currentType?.relational ? '连接成功（已执行 SELECT 1）' : '连接成功（已执行 PING）',
        );
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
        <div style={{ width: 300, borderRight: '1px solid #aaadaf', paddingRight: 16 }}>
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
                  <Popconfirm
                    key="del"
                    title="确定删除此连接?"
                    onConfirm={() => handleDelete(item.connId)}
                  >
                    <Button type="link" size="small" danger>
                      删除
                    </Button>
                  </Popconfirm>,
                ]}
              >
                <List.Item.Meta
                  avatar={<DatabaseOutlined />}
                  title={
                    <Space size={4}>
                      <span>{item.connName}</span>
                      <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                        {typeLabelOf(item.dbType)}
                      </Tag>
                    </Space>
                  }
                  description={`${item.host}:${item.port}${
                    item.schemaName ? ` / ${item.schemaName}` : ''
                  }`}
                />
              </List.Item>
            )}
          />
        </div>

        {/* 右侧表单 */}
        <div style={{ flex: 1 }}>
          <Form form={form} layout="vertical" initialValues={{ dbType: 'MySQL', port: 3306 }}>
            <Form.Item name="connName" label="连接名称" rules={[{ required: true }]}>
              <Input placeholder="如：中台Doris / 本机MySQL" />
            </Form.Item>

            <Form.Item
              name="dbType"
              label="数据源类型"
              rules={[{ required: true }]}
              tooltip="切换类型会自动带出该类型的默认端口；提示文案随类型变化"
            >
              <Select
                onChange={handleTypeChange}
                options={dbTypes.map((t) => ({ value: t.value, label: t.label }))}
              />
            </Form.Item>

            {/* 操作提示：告诉用户这一类型该怎么填、有哪些坑 */}
            {currentType && (
              <Alert
                type="info"
                showIcon
                icon={<InfoCircleOutlined />}
                style={{ marginBottom: 16 }}
                message={`${currentType.label} · 填写提示`}
                description={currentType.hint}
              />
            )}

            <Space style={{ display: 'flex' }}>
              <Form.Item name="host" label="主机 IP" rules={[{ required: true }]}>
                <Input placeholder="10.16.1.6" style={{ width: 220 }} />
              </Form.Item>
              <Form.Item name="port" label="端口" rules={[{ required: true }]}>
                <InputNumber
                  min={1}
                  max={65535}
                  placeholder={String(currentType?.defaultPort ?? '')}
                />
              </Form.Item>
            </Space>

            <Space style={{ display: 'flex' }}>
              <Form.Item
                name="username"
                label={currentType?.relational ? '用户名' : '用户名（可选）'}
                rules={currentType?.relational ? [{ required: true }] : []}
              >
                <Input
                  placeholder={currentType?.relational ? 'root' : '无鉴权时留空'}
                  style={{ width: 220 }}
                />
              </Form.Item>
              <Form.Item
                name="password"
                label={currentType?.relational ? '密码' : '密码（可选）'}
              >
                <Input.Password
                  placeholder={selectedId ? '留空不修改' : '无鉴权时留空'}
                  style={{ width: 220 }}
                />
              </Form.Item>
            </Space>

            <Form.Item
              name="schemaName"
              label={currentType?.schemaLabel || '模式名 (Schema/Database)'}
              rules={currentType?.relational ? [{ required: true }] : []}
              tooltip={
                currentType?.relational
                  ? '关系库的库名/模式名；PostgreSQL 这一栏填数据库名'
                  : 'Redis 的 db index（0-15），留空按 0'
              }
            >
              <Input
                placeholder={
                  currentType?.relational
                    ? currentType?.value === 'Dameng'
                      ? 'TEST'
                      : 'mil_base'
                    : '0'
                }
              />
            </Form.Item>

            <Tooltip title="内网部署，口令以明文存入 DB_CONNECTIONS 表；请确保数据库访问权限可控">
              <div style={{ color: '#faad14', fontSize: 12, marginBottom: 12 }}>
                ⚠ 密码为明文存储（内网方案），编辑时留空表示不修改
              </div>
            </Tooltip>

            <Space>
              <Button onClick={handleTest} loading={testing}>
                测试连接
              </Button>
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
