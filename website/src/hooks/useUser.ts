import { useCallback, useEffect, useState } from 'react';
import { checkAuthStatus, getLoginUrl, type AuthStatus } from '@/services/auth';

export type LoginMode = 'sso' | 'direct' | 'checking';

/**
 * 用户认证 Hook
 * 支持两种登录模式:
 * 1. SSO 跳转: 重定向到中台登录页
 * 2. 直接登录: 前端表单提交用户名密码
 */
export function useUser() {
  const [authStatus, setAuthStatus] = useState<AuthStatus>({ authenticated: false });
  const [loading, setLoading] = useState(true);
  const [loginMode, setLoginMode] = useState<LoginMode>('checking');
  // 后端登录模式：sso=集成登录(跳转中台)；local=本地开放访问(免登录)
  const [authMode, setAuthMode] = useState<AuthStatus['authMode']>(undefined);

  const checkStatus = useCallback(async () => {
    try {
      const status = await checkAuthStatus();
      setAuthStatus(status);
      setAuthMode(status.authMode);
      return status;
    } catch {
      setAuthStatus({ authenticated: false });
      setAuthMode(undefined);
      return { authenticated: false } as AuthStatus;
    }
  }, []);

  // SSO 跳转模式
  const startSSOLogin = useCallback(async () => {
    try {
      const url = await getLoginUrl();
      window.location.href = url;
    } catch {
      console.error('获取 SSO 登录 URL 失败');
    }
  }, []);

  // 切换到直接登录模式
  const switchToDirectLogin = useCallback(() => {
    setLoginMode('direct');
  }, []);

  // 切换到 SSO 模式
  const switchToSSOLogin = useCallback(() => {
    setLoginMode('sso');
    startSSOLogin();
  }, [startSSOLogin]);

  // 直接登录成功回调
  const onDirectLoginSuccess = useCallback((user: { username: string; displayName: string }) => {
    setAuthStatus({
      authenticated: true,
      username: user.username,
      displayName: user.displayName,
    });
    setLoading(false);
  }, []);

  // 登出
  const handleLogout = useCallback(async () => {
    try {
      const res = await fetch('/api/auth/logout', { method: 'POST' });
      const result = await res.json();
      const logoutUrl = result?.logoutUrl;
      // local 模式后端返回空 logoutUrl：仅刷新（过滤器会自动重建本地会话）
      if (logoutUrl) {
        window.location.href = logoutUrl;
      } else {
        window.location.reload();
      }
    } catch {
      console.error('登出失败');
    }
  }, []);

  useEffect(() => {
    // 分享/嵌入页（/share/:id）免登录：不校验登录态、不跳 SSO，直接渲染地图
    const route = window.location.hash.replace(/^#/, '') || window.location.pathname;
    if (route.startsWith('/share')) {
      setLoading(false);
      return;
    }

    // 处理回调参数 (hash 路由: login_success 在 ? 之后、# 之前)
    const search = window.location.search;
    const params = new URLSearchParams(search);

    if (params.get('login_error') === '1') {
      const msg = params.get('message') || '登录失败';
      console.error('SSO 登录失败:', msg);
      setLoginMode('direct');
    }

    // 清除 URL 中的回调参数
    if (params.has('login_success') || params.has('login_error')) {
      const newUrl = window.location.pathname + window.location.hash;
      window.history.replaceState({}, '', newUrl);
    }

    // 检查登录态
    checkStatus().then((status) => {
      if (status.authenticated) {
        setLoading(false);
      } else if (status.authMode === 'local') {
        // local 开放访问：无登录墙，直接渲染（loginMode 保持 'checking'，布局不拦截）
        setLoading(false);
      } else {
        // SSO 模式：先尝试 SSO 跳转，不走登录表单
        // 如果 SSO 有会话 → 直接跳回（走通）；无会话 → 显示 SSO 登录页
        setLoginMode('sso');
        startSSOLogin();
      }
    });
  }, [checkStatus, startSSOLogin]);

  return {
    user: authStatus.authenticated ? authStatus : null,
    loading,
    authenticated: authStatus.authenticated,
    loginMode,
    authMode,
    logout: handleLogout,
    switchToDirectLogin,
    switchToSSOLogin,
    onDirectLoginSuccess,
  };
}
