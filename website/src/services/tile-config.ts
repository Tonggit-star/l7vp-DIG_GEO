import type { TileConfig } from '@/types/tile-config';

const API_BASE_URL = '/api';

/** 获取所有瓦片配置 */
export const listTileConfigs = async (): Promise<TileConfig[]> => {
  try {
    const response = await fetch(`${API_BASE_URL}/tile-configs`);
    if (!response.ok) return [];
    return await response.json();
  } catch (error) {
    console.error('获取瓦片列表失败:', error);
    return [];
  }
};

/** 获取默认瓦片（兼容旧 API） */
export const getTileConfig = async (): Promise<TileConfig | null> => {
  try {
    const response = await fetch(`${API_BASE_URL}/tile-config`);
    if (!response.ok) {
      if (response.status === 204) return null;
      return null;
    }
    return await response.json();
  } catch (error) {
    console.error('获取瓦片配置失败:', error);
    return null;
  }
};

/** 新增瓦片 */
export const createTileConfig = async (config: TileConfig): Promise<TileConfig> => {
  const response = await fetch(`${API_BASE_URL}/tile-configs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!response.ok) throw new Error('新增瓦片失败');
  return await response.json();
};

/** 更新瓦片 */
export const updateTileConfig = async (id: string, config: TileConfig): Promise<TileConfig> => {
  const response = await fetch(`${API_BASE_URL}/tile-configs/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!response.ok) throw new Error('更新瓦片失败');
  return await response.json();
};

/** 删除瓦片 */
export const deleteTileConfig = async (id: string): Promise<void> => {
  const response = await fetch(`${API_BASE_URL}/tile-configs/${id}`, {
    method: 'DELETE',
  });
  if (!response.ok) throw new Error('删除瓦片失败');
};

/** 保存瓦片（兼容旧 API） */
export const saveTileConfig = async (config: TileConfig): Promise<TileConfig> => {
  const response = await fetch(`${API_BASE_URL}/tile-config`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!response.ok) {
    throw new Error('保存瓦片配置失败');
  }
  return await response.json();
};
