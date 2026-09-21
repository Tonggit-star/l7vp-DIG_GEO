package com.antv.l7vp.model;

/**
 * 外部数据库连接配置
 */
public class DbConnection {
    private String connId;
    private String connName;
    private String dbType;       // MySQL / Dameng
    private String host;
    private int port;
    private String username;
    private String password;     // 明文（内网）
    private String schemaName;
    private String createTime;
    private String updateTime;

    public String getConnId() { return connId; }
    public void setConnId(String connId) { this.connId = connId; }
    public String getConnName() { return connName; }
    public void setConnName(String connName) { this.connName = connName; }
    public String getDbType() { return dbType; }
    public void setDbType(String dbType) { this.dbType = dbType; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
