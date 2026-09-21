package com.antv.l7vp.repository;

import com.antv.l7vp.model.DbConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class DbConnectionRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static class DbConnectionRowMapper implements RowMapper<DbConnection> {
        @Override
        public DbConnection mapRow(ResultSet rs, int rowNum) throws SQLException {
            DbConnection conn = new DbConnection();
            conn.setConnId(rs.getString("CONN_ID"));
            conn.setConnName(rs.getString("CONN_NAME"));
            conn.setDbType(rs.getString("DB_TYPE"));
            conn.setHost(rs.getString("HOST"));
            conn.setPort(rs.getInt("PORT"));
            conn.setUsername(rs.getString("USERNAME"));
            conn.setPassword(rs.getString("PASSWORD"));
            conn.setSchemaName(rs.getString("SCHEMA_NAME"));
            conn.setCreateTime(rs.getString("CREATE_TIME"));
            conn.setUpdateTime(rs.getString("UPDATE_TIME"));
            return conn;
        }
    }

    public List<DbConnection> findAll() {
        String sql = "SELECT * FROM DB_CONNECTIONS ORDER BY CREATE_TIME";
        return jdbcTemplate.query(sql, new DbConnectionRowMapper());
    }

    public DbConnection findById(String connId) {
        String sql = "SELECT * FROM DB_CONNECTIONS WHERE CONN_ID = ?";
        List<DbConnection> list = jdbcTemplate.query(sql, new Object[]{connId}, new DbConnectionRowMapper());
        return list.isEmpty() ? null : list.get(0);
    }

    public DbConnection insert(DbConnection conn) {
        if (conn.getConnId() == null) {
            conn.setConnId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO DB_CONNECTIONS (CONN_ID, CONN_NAME, DB_TYPE, HOST, PORT, USERNAME, PASSWORD, SCHEMA_NAME, CREATE_TIME, UPDATE_TIME) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            conn.getConnId(), conn.getConnName(), conn.getDbType(), conn.getHost(),
            conn.getPort(), conn.getUsername(), conn.getPassword(), conn.getSchemaName(),
            conn.getCreateTime(), conn.getUpdateTime());
        return conn;
    }

    public DbConnection update(DbConnection conn) {
        String sql = "UPDATE DB_CONNECTIONS SET CONN_NAME=?, DB_TYPE=?, HOST=?, PORT=?, USERNAME=?, PASSWORD=?, SCHEMA_NAME=?, UPDATE_TIME=? WHERE CONN_ID=?";
        jdbcTemplate.update(sql,
            conn.getConnName(), conn.getDbType(), conn.getHost(), conn.getPort(),
            conn.getUsername(), conn.getPassword(), conn.getSchemaName(), conn.getUpdateTime(),
            conn.getConnId());
        return conn;
    }

    public void deleteById(String connId) {
        jdbcTemplate.update("DELETE FROM DB_CONNECTIONS WHERE CONN_ID = ?", connId);
    }
}
