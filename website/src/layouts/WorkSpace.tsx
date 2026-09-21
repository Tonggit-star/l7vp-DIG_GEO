import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { useTitle } from 'ahooks';
import { Button, Dropdown, Layout, theme } from 'antd';
import { Content, Header } from 'antd/lib/layout/layout';
import { Outlet } from 'umi';
import styles from './WorkSpace.less';
import { useUser } from '@/hooks/useUser';
import { useInternalUserPrompt } from '@/hooks';

const { useToken } = theme;

export default function WorkSpaceLayout() {
  const internalUserPrompt = useInternalUserPrompt();
  const { token } = useToken();
  const { user, authMode, logout } = useUser();
  useTitle('地理可视化');

  const userMenuItems = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: logout,
    },
  ];

  return (
    <Layout className={styles.layout}>
      <Header
        style={{
          background: token.colorBgContainer,
          display: 'flex',
          justifyContent: 'flex-end',
          alignItems: 'center',
          padding: '0 24px',
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
        }}
      >
        {user && authMode !== 'local' && (
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <Button type="text" icon={<UserOutlined />}>
              {user.displayName || user.username}
            </Button>
          </Dropdown>
        )}
      </Header>
      <Content style={{ color: token.colorText, padding: '24px' }}>
        {internalUserPrompt}
        <Outlet />
      </Content>
    </Layout>
  );
}
