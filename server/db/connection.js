const dmdb = require('dmdb');
const dbConfig = require('../config/database.config');

let pool;

async function initDatabase() {
  try {
    // 创建连接池
    pool = await dmdb.createPool({
      connectString: `dm://${dbConfig.user}:${dbConfig.password}@${dbConfig.host}:${dbConfig.port}/${dbConfig.schema}`,
      poolMax: 10,
      poolMin: 1,
    });
    
    console.log('达梦数据库连接池创建成功');
    
    // 创建Schema（如果不存在）
    const connection = await pool.getConnection();
    try {
      await connection.execute(`CREATE SCHEMA IF NOT EXISTS ${dbConfig.schema}`);
      console.log(`Schema ${dbConfig.schema} 创建成功`);
    } catch (error) {
      console.log(`Schema ${dbConfig.schema} 已存在或创建失败: ${error.message}`);
    }
    
    // 创建表
    await createTables(connection);
    
    await connection.close();
    console.log('数据库初始化完成');
    
    return pool;
  } catch (error) {
    console.error('数据库初始化失败:', error);
    throw error;
  }
}

async function createTables(connection) {
  const createTableSQL = `
    CREATE TABLE IF NOT EXISTS ${dbConfig.schema}.PROJECTS (
      PROJECT_ID VARCHAR(50) PRIMARY KEY,
      PROJECT_NAME VARCHAR(200),
      DESCRIPTION VARCHAR(500),
      CREATE_TIME VARCHAR(50),
      APPLICATION_CONFIG CLOB,
      ASSET_PACKAGE_IDS VARCHAR(500)
    )
  `;
  
  try {
    await connection.execute(createTableSQL);
    console.log('项目表创建成功');
  } catch (error) {
    console.log('项目表已存在或创建失败:', error.message);
  }
}

async function getConnection() {
  if (!pool) {
    await initDatabase();
  }
  return await pool.getConnection();
}

async function closePool() {
  if (pool) {
    await pool.close();
    console.log('数据库连接池已关闭');
  }
}

module.exports = {
  initDatabase,
  getConnection,
  closePool,
};