import type { Application } from '@antv/li-sdk';
import dayjs from 'dayjs';
import { pick } from 'lodash-es';
import { adaptationProjectMapToken } from '@/utils';

// 后端API地址
const API_BASE_URL = '/api';

export type Project = {
  projectName: string;
  description: string;
  projectId: string;
  creatTime: string;
  applicationConfig: Application;
  assetPackageIds?: string[];
  thumbnail?: string;
};

export type ProjectParams = Pick<Project, 'description' | 'projectName' | 'applicationConfig' | 'assetPackageIds'>;

/**
 * 获取项目列表
 */
export const getProjectList = async () => {
  try {
    const response = await fetch(`${API_BASE_URL}/projects`);
    if (!response.ok) {
      throw new Error('获取项目列表失败');
    }
    const projects = await response.json();
    return projects || [];
  } catch (error) {
    console.error('获取项目列表失败:', error);
    return [];
  }
};

/**
 * 创建项目列表
 */
export const createProject = async (params: ProjectParams) => {
  const creatTime = dayjs().format('YYYY-MM-DD HH:mm:ss');

  // 同步更新 AppConfig 元数据信息
  const appMetadata = pick(params, ['description', 'assetPackageIds']);
  const applicationConfig = {
    ...params.applicationConfig,
    metadata: { name: params.projectName, creatTime, ...appMetadata },
  };

  try {
    const response = await fetch(`${API_BASE_URL}/projects`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        projectName: params.projectName,
        description: params.description,
        applicationConfig,
        assetPackageIds: params.assetPackageIds,
      }),
    });

    if (!response.ok) {
      throw new Error('创建项目失败');
    }

    const data = await response.json();

    // Java 后端返回的是 assemble() 组装的应用 JSON { metadata:{projectId,...}, datasets, spec }
    // Express 后端返回的是 Project 对象 { projectId, projectName, ..., applicationConfig }
    if (data.metadata?.projectId) {
      // Java 后端格式：整个 data 就是 applicationConfig，projectId 在 metadata 中
      return {
        ...params,
        projectId: data.metadata.projectId,
        creatTime: data.metadata.createTime || creatTime,
        applicationConfig: data,
        assetPackageIds: data.metadata.assetPackageIds || params.assetPackageIds,
      } as Project;
    }

    // Express 后端格式：直接返回 Project 对象
    return data;
  } catch (error) {
    console.error('创建项目失败:', error);
    throw error;
  }
};

/**
 * 通过 ID 获取项目
 */
export const getProject = async (id: string) => {
  try {
    const response = await fetch(`${API_BASE_URL}/projects/${id}`);
    if (!response.ok) {
      if (response.status === 404) {
        return Promise.reject('项目不存在');
      }
      throw new Error('获取项目失败');
    }

    const data = await response.json();

    // Java 后端返回的是 assemble() 组装的应用 JSON { metadata:{projectId,...}, datasets, spec }
    // Express 后端返回的是 Project 对象 { projectId, projectName, ..., applicationConfig }
    let project: any;
    if (data.metadata && !data.applicationConfig) {
      // Java 后端格式：整个 data 就是 applicationConfig
      project = {
        projectId: data.metadata.projectId || id,
        projectName: data.metadata.name,
        description: data.metadata.description,
        creatTime: data.metadata.createTime,
        thumbnail: data.metadata.thumbnail,
        assetPackageIds: data.metadata.assetPackageIds || [],
        applicationConfig: data,
      };
    } else {
      // Express 后端格式
      project = data;
    }

    const _project = adaptationProjectMapToken(project);

    return _project;
  } catch (error) {
    console.error('获取项目失败:', error);
    return Promise.reject('项目不存在');
  }
};

/**
 * 通过 ID 更新项目
 */
export const updateProject = async (id: string, params: ProjectParams) => {
  try {
    // 同步更新 AppConfig 元数据信息
    const appMetadata = pick(params, ['description', 'assetPackageIds']);
    const applicationConfig = {
      ...params.applicationConfig,
      metadata: { name: params.projectName, ...appMetadata },
    };

    const response = await fetch(`${API_BASE_URL}/projects/${id}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        projectName: params.projectName,
        description: params.description,
        applicationConfig,
        assetPackageIds: params.assetPackageIds,
      }),
    });

    if (!response.ok) {
      if (response.status === 404) {
        return Promise.reject('项目 ID 不存在');
      }
      throw new Error('更新项目失败');
    }

    const updatedProject = await response.json();
    return updatedProject;
  } catch (error) {
    console.error('更新项目失败:', error);
    return Promise.reject('更新项目失败');
  }
};

/**
 * 通过 ID 删除项目
 */
export const deleteProject = async (id: string) => {
  try {
    const response = await fetch(`${API_BASE_URL}/projects/${id}`, {
      method: 'DELETE',
    });

    if (!response.ok) {
      if (response.status === 404) {
        return Promise.reject('项目 ID 不存在');
      }
      throw new Error('删除项目失败');
    }

    // 后端 DELETE 返回 200/204 但 body 可能为空
    const text = await response.text();
    return text ? JSON.parse(text) : { success: true };
  } catch (error) {
    console.error('删除项目失败:', error);
    return Promise.reject('删除项目失败');
  }
};

/**
 * 更新项目缩略图
 */
export const updateProjectThumbnail = async (id: string, thumbnailUrl: string) => {
  try {
    const response = await fetch(`${API_BASE_URL}/projects/${id}/thumbnail`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        thumbnailUrl,
      }),
    });

    if (!response.ok) {
      if (response.status === 404) {
        return Promise.reject('项目 ID 不存在');
      }
      throw new Error('更新缩略图失败');
    }

    const updatedProject = await response.json();
    return updatedProject;
  } catch (error) {
    console.error('更新缩略图失败:', error);
    return Promise.reject('更新缩略图失败');
  }
};
