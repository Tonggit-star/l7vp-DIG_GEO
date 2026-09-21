# L7VP Java后端服务 - 达梦数据库存储

## 一、项目架构

本项目使用 **Java Spring Boot + JDBC** 访问达梦数据库，替代之前的 Node.js 方案。

### 技术栈
- **后端**: Spring Boot 2.7.14
- **数据库**: 达梦数据库 8.1.1.128
- **驱动**: 达梦 JDBC Driver (DmJdbcDriver18.jar)
- **端口**: 3001

---

## 二、数据库准备

### 1. 执行建表SQL
请在达梦数据库中执行以下SQL语句：

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
```

### 2. 数据库配置
数据库配置信息已保存在 `src/main/resources/application.properties` 文件中：

```properties
spring.datasource.url=jdbc:dm://localhost:5236/L7VP_PROJECTS
spring.datasource.username=SYSDBA
spring.datasource.password=SYSDBA
```

如需修改，请直接编辑该配置文件。

---

## 三、项目配置

### 1. 达梦JDBC驱动配置
在 `pom.xml` 中已配置达梦JDBC驱动路径：

```xml
<dependency>
    <groupId>com.dameng</groupId>
    <artifactId>DmJdbcDriver18</artifactId>
    <version>8.1.1.128</version>
    <scope>system</scope>
    <systemPath>F:\dmdbms\drivers\jdbc\DmJdbcDriver18.jar</systemPath>
</dependency>
```

**注意**: 如果你的驱动路径不同，请修改 `systemPath` 为实际路径。

---

## 四、启动服务

### 方式1: 使用Maven启动
```bash
cd f:\code\Java\L7VP\L7VP\java-server
mvn spring-boot:run
```

### 方式2: 打包后运行
```bash
# 1. 打包项目
mvn clean package

# 2. 运行jar包
java -jar target/l7vp-server-1.0.0.jar
```

后端服务将在 `http://localhost:3001` 启动。

---

## 五、API接口

### 1. 获取项目列表
```
GET http://localhost:3001/api/projects
```

### 2. 创建项目
```
POST http://localhost:3001/api/projects
Content-Type: application/json

{
  "projectName": "测试项目",
  "description": "项目描述",
  "applicationConfig": {...},
  "assetPackageIds": [...]
}
```

### 3. 获取单个项目
```
GET http://localhost:3001/api/projects/{id}
```

### 4. 更新项目
```
PUT http://localhost:3001/api/projects/{id}
Content-Type: application/json

{
  "projectName": "更新后的项目名",
  "description": "更新后的描述",
  "applicationConfig": {...},
  "assetPackageIds": [...]
}
```

### 5. 删除项目
```
DELETE http://localhost:3001/api/projects/{id}
```

### 6. 健康检查
```
GET http://localhost:3001/api/health
```

---

## 六、前端配置

前端代码已修改为调用Java后端API，API地址为 `http://localhost:3001/api`。

启动前端服务：
```bash
cd f:\code\Java\L7VP\L7VP
yarn run start:website
```

前端服务将在 `http://localhost:8090` 启动。

---

## 七、常见问题

### 1. JDBC驱动找不到
**错误**: `ClassNotFoundException: dm.jdbc.driver.DmDriver`

**解决**: 
- 检查 `pom.xml` 中 `systemPath` 是否正确
- 确认达梦驱动文件存在于 `F:\dmdbms\drivers\jdbc\DmJdbcDriver18.jar`

### 2. 数据库连接失败
**错误**: `Connection refused` 或 `无法连接数据库`

**解决**:
- 检查达梦数据库是否正常运行
- 检查端口5236是否开放
- 检查用户名密码是否正确

### 3. Schema不存在
**错误**: `Schema L7VP_PROJECTS does not exist`

**解决**: 执行建表SQL创建Schema

---

## 八、项目结构

```
java-server/
├── pom.xml                          # Maven配置文件
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/antv/l7vp/
│   │   │       ├── L7vpServerApplication.java    # 主类
│   │   │       ├── model/
│   │   │       │   └── Project.java              # 实体类
│   │   │       ├── repository/
│   │   │       │   └── ProjectRepository.java    # 数据访问层
│   │   │       ├── service/
│   │   │       │   └── ProjectService.java       # 服务层
│   │   │       ├── controller/
│   │   │       │   └── ProjectController.java    # 控制器
│   │   │       └── config/
│   │   │           └── WebConfig.java            # 配置类
│   │   └── resources/
│   │       ├── application.properties            # 配置文件
│   │       └── schema.sql                        # 建表SQL
│   └── test/
└── README.md                        # 说明文档
```

---

## 九、优势对比

### Java方案 vs Node.js方案

| 特性 | Java方案 | Node.js方案 |
|------|---------|------------|
| 驱动支持 | ✅ JDBC驱动官方支持 | ❌ Node.js驱动不常见 |
| 稳定性 | ✅ 企业级稳定性 | ⚠️ 需要第三方驱动 |
| 性能 | ✅ 高性能 | ⚠️ OpenSSL兼容性问题 |
| 维护性 | ✅ Spring Boot生态完善 | ⚠️ 需要手动处理兼容性 |

---

## 十、下一步

1. ✅ 执行建表SQL
2. ✅ 启动Java后端服务
3. ✅ 启动前端服务
4. ✅ 测试项目创建、编辑、删除功能
5. ✅ 验证所有用户看到相同的项目数据

现在所有用户访问 `http://localhost:8090` 都能看到相同的项目列表！