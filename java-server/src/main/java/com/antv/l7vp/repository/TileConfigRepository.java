package com.antv.l7vp.repository;

import com.antv.l7vp.model.TileConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class TileConfigRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String SELECT_COLS = "ID, TILE_URL, TILE_NAME, MIN_ZOOM, MAX_ZOOM, UPDATE_TIME, IS_DEFAULT, DEFAULT_NUM, CRS, TILE_SCHEME, ORIGIN";

    public List<TileConfig> findAll() {
        String sql = "SELECT " + SELECT_COLS + " FROM TILE_CONFIG ORDER BY DEFAULT_NUM ASC NULLS LAST, UPDATE_TIME DESC";
        return jdbcTemplate.query(sql, new TileConfigRowMapper());
    }

    public TileConfig findById(String id) {
        String sql = "SELECT " + SELECT_COLS + " FROM TILE_CONFIG WHERE ID = ?";
        List<TileConfig> configs = jdbcTemplate.query(sql, new TileConfigRowMapper(), id);
        return configs.isEmpty() ? null : configs.get(0);
    }

    /** 返回默认瓦片列表（按defaultNum排序） */
    public List<TileConfig> findDefaults() {
        String sql = "SELECT " + SELECT_COLS + " FROM TILE_CONFIG WHERE IS_DEFAULT = '1' ORDER BY DEFAULT_NUM ASC";
        return jdbcTemplate.query(sql, new TileConfigRowMapper());
    }

    /** 返回第一个默认瓦片（兼容旧API） */
    public TileConfig findDefault() {
        List<TileConfig> defaults = findDefaults();
        return defaults.isEmpty() ? null : defaults.get(0);
    }

    public TileConfig insert(TileConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO TILE_CONFIG (ID, TILE_URL, TILE_NAME, MIN_ZOOM, MAX_ZOOM, UPDATE_TIME, IS_DEFAULT, DEFAULT_NUM, CRS, TILE_SCHEME, ORIGIN) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            config.getId(), config.getTileUrl(), config.getTileName(),
            config.getMinZoom(), config.getMaxZoom(), config.getUpdateTime(),
            config.getIsDefault(), config.getDefaultNum(),
            config.getCrs(), config.getTileScheme(),
            config.getOrigin()
        );
        return config;
    }

    public TileConfig update(TileConfig config) {
        String sql = "UPDATE TILE_CONFIG SET TILE_URL = ?, TILE_NAME = ?, MIN_ZOOM = ?, MAX_ZOOM = ?, UPDATE_TIME = ?, IS_DEFAULT = ?, DEFAULT_NUM = ?, CRS = ?, TILE_SCHEME = ?, ORIGIN = ? WHERE ID = ?";
        jdbcTemplate.update(sql,
            config.getTileUrl(), config.getTileName(),
            config.getMinZoom(), config.getMaxZoom(), config.getUpdateTime(),
            config.getIsDefault(), config.getDefaultNum(),
            config.getCrs(), config.getTileScheme(),
            config.getOrigin(),
            config.getId()
        );
        return config;
    }

    public void deleteById(String id) {
        String sql = "DELETE FROM TILE_CONFIG WHERE ID = ?";
        jdbcTemplate.update(sql, id);
    }

    static class TileConfigRowMapper implements RowMapper<TileConfig> {
        @Override
        public TileConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            TileConfig config = new TileConfig();
            config.setId(rs.getString("ID"));
            config.setTileUrl(rs.getString("TILE_URL"));
            config.setTileName(rs.getString("TILE_NAME"));
            config.setMinZoom(rs.getInt("MIN_ZOOM"));
            config.setMaxZoom(rs.getInt("MAX_ZOOM"));
            config.setUpdateTime(rs.getString("UPDATE_TIME"));
            config.setIsDefault(rs.getString("IS_DEFAULT"));
            config.setDefaultNum(rs.getInt("DEFAULT_NUM"));
            if (rs.wasNull()) config.setDefaultNum(null);
            config.setCrs(rs.getString("CRS"));
            config.setTileScheme(rs.getString("TILE_SCHEME"));
            config.setOrigin(rs.getString("ORIGIN"));
            return config;
        }
    }
}
