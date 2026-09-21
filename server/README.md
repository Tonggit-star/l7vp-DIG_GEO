# L7VP 达梦数据库部署指南

## 一、数据库准备

### 1. 执行建表SQL
请在达梦数据库中执行以下SQL语句（已提供在 `server/sql/init.sql` 文件中）：

```sql
-- 创建Schema
CREATE SCHEMA IF NOT EXISTS L7VP_PROJECTS;

-- 创建项目表
CREATE TABLE IF NOT EXISTS L7VP_PROJECTS.PROJECTS (
  PROJECT_ID VARCHAR(50) PRIMARY KEY,
  PROJECT_NAME VARCHAR(200),
  DESCRIPTION VARCHAR(500),
  CREATE_TIME VARCHAR(50),
  APPLICATION_CONFIG CLOB,
  ASSET_PACKAGE_IDS VARCHAR(500)
);

-- 创建索引（可选）
CREATE INDEX IF NOT EXISTS IDX_PROJECT_NAME ON L7VP_PROJECTS.PROJECTS(PROJECT_NAME);
CREATE INDEX IF NOT EXISTS IDX_CREATE_TIME ON L7VP_PROJECTS.PROJECTS(CREATE_TIME);
```

### 2. 数据库配置
数据库配置信息已保存在 `server/config/database.config.js` 文件中：

```javascript
{
  host: 'localhost',
  port: 5236,
  user: 'SYSDBA',
  password: 'SYSDBA',
  schema: 'L7VP_PROJECTS'
}
```

如需修改，请直接编辑该配置文件。

## 二、后端服务部署

### 1. 安装依赖
```bash
cd server
npm install
```

**注意：** 达梦数据库Node.js驱动（dmdb）需要单独安装，请从达梦官方获取驱动包。

### 2. 启动后端服务
```bash
npm start
```

后端服务将在 `http://localhost:3001` 启动，提供以下API：
- `GET /api/projects` - 获取项目列表
- `POST /api/projects` - 创建项目
- `GET /api/projects/:id` - 获取单个项目
- `PUT /api/projects/:id` - 更新项目
- `DELETE /api/projects/:id` - 删除项目

## 三、前端服务部署

### 1. 启动前端服务
```bash
cd f:\code\Java\L7VP\L7VP
yarn run start:website
```

前端服务将在 `http://localhost:8090` 启动。

## 四、使用说明

### 1. 所有用户共享数据
现在所有用户访问 `http://localhost:8090` 都能看到相同的项目列表，因为数据存储在达梦数据库中。

### 2. 项目操作
- 创建项目：点击"创建项目"按钮
- 编辑项目：点击项目卡片进入编辑页面
- 预览项目：点击项目卡片上的预览按钮
- 删除项目：点击项目卡片上的删除按钮

## 五、注意事项

### 1. 达梦数据库驱动
达梦数据库Node.js驱动（dmdb）需要从达梦官方获取，可能需要：
- 下载驱动包
- 配置环境变量
- 安装依赖

### 2. 网络访问
确保前端服务（8090端口）和后端服务（3001端口）都能正常访问。

### 3. 数据持久化
所有项目数据都存储在达梦数据库中，重启服务不会丢失数据。

## 六、故障排查

### 1. 后端服务无法启动
- 检查达梦数据库是否正常运行
- 检查数据库配置是否正确
- 检查达梦驱动是否正确安装

### 2. 前端无法获取数据
- 检查后端服务是否正常运行
- 检查API地址是否正确（默认：http://localhost:3001）
- 检查网络连接是否正常

### 3. 项目无法保存
- 检查数据库表是否正确创建
- 检查数据库连接是否正常
- 查看后端服务日志排查错误