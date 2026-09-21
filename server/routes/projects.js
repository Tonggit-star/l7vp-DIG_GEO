const express = require('express');
const router = express.Router();
const { getConnection } = require('../db/connection');
const dbConfig = require('../config/database.config');

// 获取项目列表
router.get('/projects', async (req, res) => {
  try {
    const connection = await getConnection();
    const result = await connection.execute(
      `SELECT PROJECT_ID, PROJECT_NAME, DESCRIPTION, CREATE_TIME, APPLICATION_CONFIG, ASSET_PACKAGE_IDS 
       FROM ${dbConfig.schema}.PROJECTS`
    );
    
    const projects = result.rows.map(row => ({
      projectId: row[0],
      projectName: row[1],
      description: row[2],
      creatTime: row[3],
      applicationConfig: JSON.parse(row[4] || '{}'),
      assetPackageIds: row[5] ? JSON.parse(row[5]) : [],
    }));
    
    await connection.close();
    res.json(projects);
  } catch (error) {
    console.error('获取项目列表失败:', error);
    res.status(500).json({ error: '获取项目列表失败' });
  }
});

// 创建项目
router.post('/projects', async (req, res) => {
  try {
    const { projectName, description, applicationConfig, assetPackageIds } = req.body;
    const projectId = generateUUID();
    const createTime = new Date().toISOString().replace('T', ' ').substring(0, 19);
    
    const connection = await getConnection();
    await connection.execute(
      `INSERT INTO ${dbConfig.schema}.PROJECTS 
       (PROJECT_ID, PROJECT_NAME, DESCRIPTION, CREATE_TIME, APPLICATION_CONFIG, ASSET_PACKAGE_IDS) 
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        projectId,
        projectName,
        description || '',
        createTime,
        JSON.stringify(applicationConfig),
        JSON.stringify(assetPackageIds || [])
      ]
    );
    
    await connection.close();
    res.json({
      projectId,
      projectName,
      description,
      creatTime: createTime,
      applicationConfig,
      assetPackageIds
    });
  } catch (error) {
    console.error('创建项目失败:', error);
    res.status(500).json({ error: '创建项目失败' });
  }
});

// 获取单个项目
router.get('/projects/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const connection = await getConnection();
    const result = await connection.execute(
      `SELECT PROJECT_ID, PROJECT_NAME, DESCRIPTION, CREATE_TIME, APPLICATION_CONFIG, ASSET_PACKAGE_IDS 
       FROM ${dbConfig.schema}.PROJECTS 
       WHERE PROJECT_ID = ?`,
      [id]
    );
    
    if (result.rows.length === 0) {
      await connection.close();
      return res.status(404).json({ error: '项目不存在' });
    }
    
    const row = result.rows[0];
    const project = {
      projectId: row[0],
      projectName: row[1],
      description: row[2],
      creatTime: row[3],
      applicationConfig: JSON.parse(row[4] || '{}'),
      assetPackageIds: row[5] ? JSON.parse(row[5]) : [],
    };
    
    await connection.close();
    res.json(project);
  } catch (error) {
    console.error('获取项目失败:', error);
    res.status(500).json({ error: '获取项目失败' });
  }
});

// 更新项目
router.put('/projects/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const { projectName, description, applicationConfig, assetPackageIds } = req.body;
    
    const connection = await getConnection();
    await connection.execute(
      `UPDATE ${dbConfig.schema}.PROJECTS 
       SET PROJECT_NAME = ?, DESCRIPTION = ?, APPLICATION_CONFIG = ?, ASSET_PACKAGE_IDS = ? 
       WHERE PROJECT_ID = ?`,
      [
        projectName,
        description || '',
        JSON.stringify(applicationConfig),
        JSON.stringify(assetPackageIds || []),
        id
      ]
    );
    
    // 返回更新后的项目
    const result = await connection.execute(
      `SELECT PROJECT_ID, PROJECT_NAME, DESCRIPTION, CREATE_TIME, APPLICATION_CONFIG, ASSET_PACKAGE_IDS 
       FROM ${dbConfig.schema}.PROJECTS 
       WHERE PROJECT_ID = ?`,
      [id]
    );
    
    await connection.close();
    
    if (result.rows.length === 0) {
      return res.status(404).json({ error: '项目不存在' });
    }
    
    const row = result.rows[0];
    res.json({
      projectId: row[0],
      projectName: row[1],
      description: row[2],
      creatTime: row[3],
      applicationConfig: JSON.parse(row[4] || '{}'),
      assetPackageIds: row[5] ? JSON.parse(row[5]) : [],
    });
  } catch (error) {
    console.error('更新项目失败:', error);
    res.status(500).json({ error: '更新项目失败' });
  }
});

// 删除项目
router.delete('/projects/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const connection = await getConnection();
    
    // 先获取项目信息
    const result = await connection.execute(
      `SELECT PROJECT_ID, PROJECT_NAME, DESCRIPTION, CREATE_TIME, APPLICATION_CONFIG, ASSET_PACKAGE_IDS 
       FROM ${dbConfig.schema}.PROJECTS 
       WHERE PROJECT_ID = ?`,
      [id]
    );
    
    if (result.rows.length === 0) {
      await connection.close();
      return res.status(404).json({ error: '项目不存在' });
    }
    
    // 删除项目
    await connection.execute(
      `DELETE FROM ${dbConfig.schema}.PROJECTS WHERE PROJECT_ID = ?`,
      [id]
    );
    
    await connection.close();
    
    const row = result.rows[0];
    res.json({
      projectId: row[0],
      projectName: row[1],
      description: row[2],
      creatTime: row[3],
      applicationConfig: JSON.parse(row[4] || '{}'),
      assetPackageIds: row[5] ? JSON.parse(row[5]) : [],
    });
  } catch (error) {
    console.error('删除项目失败:', error);
    res.status(500).json({ error: '删除项目失败' });
  }
});

// 生成UUID
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

module.exports = router;