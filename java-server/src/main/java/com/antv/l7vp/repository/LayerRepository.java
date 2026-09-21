package com.antv.l7vp.repository;

import com.antv.l7vp.model.Layer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class LayerRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Layer> findByProjectId(String projectId) {
        String sql = "SELECT LAYER_ID, PROJECT_ID, DATASET_ID, LAYER_NAME, TYPE, VIS_CONFIG, LAYER_ORDER, CREATE_TIME FROM LAYERS WHERE PROJECT_ID = ? ORDER BY LAYER_ORDER";
        return jdbcTemplate.query(sql, new Object[]{projectId}, new LayerRowMapper());
    }

    public List<Layer> findByDatasetId(String datasetId) {
        String sql = "SELECT LAYER_ID, PROJECT_ID, DATASET_ID, LAYER_NAME, TYPE, VIS_CONFIG, LAYER_ORDER, CREATE_TIME FROM LAYERS WHERE DATASET_ID = ?";
        return jdbcTemplate.query(sql, new Object[]{datasetId}, new LayerRowMapper());
    }

    public Layer findById(String layerId) {
        String sql = "SELECT LAYER_ID, PROJECT_ID, DATASET_ID, LAYER_NAME, TYPE, VIS_CONFIG, LAYER_ORDER, CREATE_TIME FROM LAYERS WHERE LAYER_ID = ?";
        List<Layer> layers = jdbcTemplate.query(sql, new Object[]{layerId}, new LayerRowMapper());
        return layers.isEmpty() ? null : layers.get(0);
    }

    public Layer insert(Layer layer) {
        if (layer.getLayerId() == null) {
            layer.setLayerId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO LAYERS (LAYER_ID, PROJECT_ID, DATASET_ID, LAYER_NAME, TYPE, VIS_CONFIG, LAYER_ORDER, CREATE_TIME) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            layer.getLayerId(),
            layer.getProjectId(),
            layer.getDatasetId(),
            layer.getLayerName(),
            layer.getType(),
            layer.getVisConfig(),
            layer.getLayerOrder(),
            layer.getCreateTime()
        );
        return layer;
    }

    public Layer update(Layer layer) {
        String sql = "UPDATE LAYERS SET PROJECT_ID = ?, DATASET_ID = ?, LAYER_NAME = ?, TYPE = ?, VIS_CONFIG = ?, LAYER_ORDER = ? WHERE LAYER_ID = ?";
        jdbcTemplate.update(sql,
            layer.getProjectId(),
            layer.getDatasetId(),
            layer.getLayerName(),
            layer.getType(),
            layer.getVisConfig(),
            layer.getLayerOrder(),
            layer.getLayerId()
        );
        return layer;
    }

    public void deleteById(String layerId) {
        String sql = "DELETE FROM LAYERS WHERE LAYER_ID = ?";
        jdbcTemplate.update(sql, layerId);
    }

    public void deleteByProjectId(String projectId) {
        String sql = "DELETE FROM LAYERS WHERE PROJECT_ID = ?";
        jdbcTemplate.update(sql, projectId);
    }

    static class LayerRowMapper implements RowMapper<Layer> {
        @Override
        public Layer mapRow(ResultSet rs, int rowNum) throws SQLException {
            Layer layer = new Layer();
            layer.setLayerId(rs.getString("LAYER_ID"));
            layer.setProjectId(rs.getString("PROJECT_ID"));
            layer.setDatasetId(rs.getString("DATASET_ID"));
            layer.setLayerName(rs.getString("LAYER_NAME"));
            layer.setType(rs.getString("TYPE"));
            layer.setVisConfig(rs.getString("VIS_CONFIG"));
            layer.setLayerOrder(rs.getInt("LAYER_ORDER"));
            layer.setCreateTime(rs.getString("CREATE_TIME"));
            return layer;
        }
    }
}
