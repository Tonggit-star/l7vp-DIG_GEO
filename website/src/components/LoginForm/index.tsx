import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, message, Space, Typography } from 'antd';
import { useState } from 'react';

const { Title } = Typography;

interface Props {
  onSuccess: (user: { username: string; displayName: string }) => void;
  onSwitchToSSO: () => void;
}

export default function LoginForm({ onSuccess, onSwitchToSSO }: Props) {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const handleSubmit = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({ error: '登录失败' }));
        message.error(data.error || '登录失败');
        return;
      }
      const data = await res.json();
      message.success(`欢迎，${data.displayName || data.username}`);
      onSuccess(data);
    } catch {
      message.error('网络错误，请检查后端服务');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        height: '100vh',
        background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      }}
    >
      <Card style={{ width: 400, boxShadow: '0 8px 24px rgba(0,0,0,0.15)' }}>
        <Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          地理可视化平台
        </Title>
        <Form form={form} onFinish={handleSubmit} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登 录
            </Button>
          </Form.Item>
        </Form>
        <Space style={{ width: '100%', justifyContent: 'center' }}>
          <Button type="link" onClick={onSwitchToSSO}>
            使用 SSO 单点登录
          </Button>
        </Space>
      </Card>
    </div>
  );
}
