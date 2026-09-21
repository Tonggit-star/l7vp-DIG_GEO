/**
 * SSO 认证 API 服务
 */
const API_BASE = '/api/auth';

export interface UserInfo {
  userId: string;
  username: string;
  displayName: string;
  orgId?: string;
}

export interface AuthStatus {
  authenticated: boolean;
  username?: string;
  displayName?: string;
  userId?: string;
  orgId?: string;
  /** 后端登录模式：sso=中台SSO集成；local=本地开放访问(免登录) */
  authMode?: 'sso' | 'local';
}

/** 获取 SSO 登录跳转 URL */
export const getLoginUrl = async (): Promise<string> => {
  const res = await fetch(`${API_BASE}/login`);
  if (!res.ok) throw new Error('获取登录地址失败');
  const data = await res.json();
  return data.redirectUrl;
};

/** 检查当前登录状态 */
export const checkAuthStatus = async (): Promise<AuthStatus> => {
  const res = await fetch(`${API_BASE}/status`);
  if (!res.ok) return { authenticated: false };
  return res.json();
};

/** 获取当前用户信息（需登录） */
export const getUserInfo = async (): Promise<UserInfo> => {
  const res = await fetch(`${API_BASE}/userinfo`);
  if (res.status === 401) throw new Error('未登录');
  if (!res.ok) throw new Error('获取用户信息失败');
  return res.json();
};

/** 登出 */
export const logout = async (): Promise<string> => {
  const res = await fetch(`${API_BASE}/logout`, { method: 'POST' });
  if (!res.ok) throw new Error('登出失败');
  const data = await res.json();
  return data.logoutUrl;
};

/** 刷新 token */
export const refreshToken = async (): Promise<boolean> => {
  const res = await fetch(`${API_BASE}/refresh`, { method: 'POST' });
  return res.ok;
};
