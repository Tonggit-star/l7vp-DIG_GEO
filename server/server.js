const express = require('express');
const cors = require('cors');
const bodyParser = require('body-parser');
const { initDatabase, closePool } = require('./db/connection');

const app = express();
const PORT = 3001;

// 中间件
app.use(cors());
app.use(bodyParser.json({ limit: '10mb' }));
app.use(bodyParser.urlencoded({ extended: true, limit: '10mb' }));

// 路由
const projectsRouter = require('./routes/projects');
app.use('/api', projectsRouter);

// 健康检查
app.get('/health', (req, res) => {
  res.json({ status: 'ok', message: 'L7VP后端服务运行正常' });
});

// 错误处理
app.use((err, req, res, next) => {
  console.error('服务器错误:', err);
  res.status(500).json({ error: '服务器内部错误' });
});

// 启动服务器
async function startServer() {
  try {
    // 初始化数据库
    await initDatabase();
    
    // 启动Express服务器
    app.listen(PORT, () => {
      console.log(`L7VP后端服务已启动: http://localhost:${PORT}`);
      console.log(`API地址: http://localhost:${PORT}/api/projects`);
    });
  } catch (error) {
    console.error('服务器启动失败:', error);
    process.exit(1);
  }
}

// 优雅关闭
process.on('SIGINT', async () => {
  console.log('正在关闭服务器...');
  await closePool();
  process.exit(0);
});

process.on('SIGTERM', async () => {
  console.log('正在关闭服务器...');
  await closePool();
  process.exit(0);
});

startServer();