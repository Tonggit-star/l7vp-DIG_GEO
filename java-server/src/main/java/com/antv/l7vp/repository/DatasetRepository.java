package com.antv.l7vp.repository;

import com.antv.l7vp.model.Dataset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class DatasetRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Dataset> findByProjectId(String projectId) {
        String sql = "SELECT DATASET_ID, PROJECT_ID, DATASET_NAME, TYPE, METADATA, FILTER, CREATE_TIME FROM DATASETS WHERE PROJECT_ID = ?";
        return jdbcTemplate.query(sql, new Object[]{projectId}, new DatasetRowMapper());
    }

    public Dataset findById(String datasetId) {
        String sql = "SELECT DATASET_ID, PROJECT_ID, DATASET_NAME, TYPE, METADATA, FILTER, CREATE_TIME FROM DATASETS WHERE DATASET_ID = ?";
        List<Dataset> datasets = jdbcTemplate.query(sql, new Object[]{datasetId}, new DatasetRowMapper());
        return datasets.isEmpty() ? null : datasets.get(0);
    }

    public Dataset insert(Dataset dataset) {
        if (dataset.getDatasetId() == null) {
            dataset.setDatasetId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO DATASETS (DATASET_ID, PROJECT_ID, DATASET_NAME, TYPE, METADATA, FILTER, CREATE_TIME) VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            dataset.getDatasetId(),
            dataset.getProjectId(),
            dataset.getDatasetName(),
            dataset.getType(),
            dataset.getMetadata(),
            dataset.getFilter(),
            dataset.getCreateTime()
        );
        return dataset;
    }

    public Dataset update(Dataset dataset) {
        String sql = "UPDATE DATASETS SET DATASET_NAME = ?, TYPE = ?, METADATA = ?, FILTER = ? WHERE DATASET_ID = ?";
        jdbcTemplate.update(sql,
            dataset.getDatasetName(),
            dataset.getType(),
            dataset.getMetadata(),
            dataset.getFilter(),
            dataset.getDatasetId()
        );
        return dataset;
    }

    public void deleteById(String datasetId) {
        String sql = "DELETE FROM DATASETS WHERE DATASET_ID = ?";
        jdbcTemplate.update(sql, datasetId);
    }

    public void deleteByProjectId(String projectId) {
        String sql = "DELETE FROM DATASETS WHERE PROJECT_ID = ?";
        jdbcTemplate.update(sql, projectId);
    }

    static class DatasetRowMapper implements RowMapper<Dataset> {
        @Override
        public Dataset mapRow(ResultSet rs, int rowNum) throws SQLException {
            Dataset dataset = new Dataset();
            dataset.setDatasetId(rs.getString("DATASET_ID"));
            dataset.setProjectId(rs.getString("PROJECT_ID"));
            dataset.setDatasetName(rs.getString("DATASET_NAME"));
            dataset.setType(rs.getString("TYPE"));
            dataset.setMetadata(rs.getString("METADATA"));
            dataset.setFilter(rs.getString("FILTER"));
            dataset.setCreateTime(rs.getString("CREATE_TIME"));
            return dataset;
        }
    }
}
