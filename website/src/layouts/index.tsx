import LoginForm from '@/components/LoginForm';
import { WEBSITE_THEME } from '@/constants';
import { useUser } from '@/hooks/useUser';
import { Global } from '@emotion/react';
import { ConfigProvider, Spin, theme } from 'antd';
import zhCN from 'antd/es/locale/zh_CN';
import 'dayjs/locale/zh-cn';
import { Outlet } from 'umi';

const { useToken } = theme;

const GlobalStyle = () => {
  const { token } = useToken();

  return (
    <Global
      styles={{
        body: {
          color: token.colorText,
          fontSize: token.fontSize,
          fontFamily: token.fontFamily,
          lineHeight: token.lineHeight,
          background: token.colorBgLayout,
        },
      }}
    />
  );
};

export default function Layout() {
  const { loading, authenticated, loginMode, switchToSSOLogin, onDirectLoginSuccess } = useUser();

  // 检查登录态中
  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Spin size="large" tip="检查登录状态..." />
      </div>
    );
  }

  // 未登录 + SSO 跳转中
  if (!authenticated && loginMode === 'sso') {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Spin size="large" tip="正在跳转到 SSO 登录页..." />
      </div>
    );
  }

  // 未登录 + 直接登录模式
  if (!authenticated && loginMode === 'direct') {
    return <LoginForm onSuccess={onDirectLoginSuccess} onSwitchToSSO={switchToSSOLogin} />;
  }

  return (
    <ConfigProvider locale={zhCN} theme={WEBSITE_THEME}>
      <Outlet />
      <GlobalStyle />
    </ConfigProvider>
  );
}
