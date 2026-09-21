import type { IconCategory, IconItem } from '@/types/icon';

const API_BASE_URL = '/api';

// ==================== 分类管理 ====================

export const getCategories = async (): Promise<IconCategory[]> => {
  const res = await fetch(`${API_BASE_URL}/icons/categories`);
  if (!res.ok) throw new Error('获取分类列表失败');
  return res.json();
};

export const createCategory = async (name: string): Promise<IconCategory> => {
  const res = await fetch(`${API_BASE_URL}/icons/categories`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error('创建分类失败');
  return res.json();
};

export const updateCategory = async (id: string, name: string): Promise<IconCategory> => {
  const res = await fetch(`${API_BASE_URL}/icons/categories/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error('更新分类失败');
  return res.json();
};

export const deleteCategory = async (id: string): Promise<void> => {
  const res = await fetch(`${API_BASE_URL}/icons/categories/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error('删除分类失败');
};

export const reorderCategories = async (orders: { categoryId: string; sortOrder: number }[]): Promise<void> => {
  await fetch(`${API_BASE_URL}/icons/categories/reorder`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(orders),
  });
};

// ==================== 图标管理 ====================

export const getIcons = async (categoryId: string): Promise<IconItem[]> => {
  const res = await fetch(`${API_BASE_URL}/icons?categoryId=${categoryId}`);
  if (!res.ok) throw new Error('获取图标列表失败');
  return res.json();
};

export const uploadIcons = async (categoryId: string, files: File[]): Promise<IconItem[]> => {
  const formData = new FormData();
  formData.append('categoryId', categoryId);
  files.forEach((f) => formData.append('files', f));
  const res = await fetch(`${API_BASE_URL}/icons/upload`, { method: 'POST', body: formData });
  if (!res.ok) throw new Error('上传图标失败');
  return res.json();
};

export const updateIconMeta = async (iconId: string, libraryCode: string, codeName: string): Promise<IconItem> => {
  const res = await fetch(`${API_BASE_URL}/icons/${iconId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ libraryCode, codeName }),
  });
  if (res.status === 409) {
    const errorData = await res.json();
    throw { code: 409, message: errorData.error || '库号+代号重复', ...errorData };
  }
  if (!res.ok) throw new Error('更新图标信息失败');
  return res.json();
};

export const deleteIcon = async (iconId: string): Promise<void> => {
  const res = await fetch(`${API_BASE_URL}/icons/${iconId}`, { method: 'DELETE' });
  if (!res.ok) throw new Error('删除图标失败');
};

export const reorderIcons = async (orders: { iconId: string; sortOrder: number }[]): Promise<void> => {
  await fetch(`${API_BASE_URL}/icons/reorder`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(orders),
  });
};

export const moveIcon = async (iconId: string, targetCategoryId: string): Promise<IconItem> => {
  const res = await fetch(`${API_BASE_URL}/icons/${iconId}/move`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ targetCategoryId }),
  });
  if (!res.ok) throw new Error('移动图标失败');
  return res.json();
};

export const lookupIcon = async (libraryCode: string, codeName: string): Promise<{ url: string } | null> => {
  const res = await fetch(
    `${API_BASE_URL}/icons/lookup?lib=${encodeURIComponent(libraryCode)}&code=${encodeURIComponent(codeName)}`,
  );
  if (!res.ok) return null;
  return res.json();
};

// ==================== 兼容旧接口 ====================

export const getIconList = async () => {
  const res = await fetch(`${API_BASE_URL}/icons`);
  if (!res.ok) return [];
  const data = await res.json();
  if (!Array.isArray(data) || data.length === 0) return [];
  return data;
};

export const getFlatIconList = async (): Promise<{ id: string; url: string; name: string }[]> => {
  const iconList = await getIconList();
  return iconList.map((item: any) => item.icons || []).flat();
};
