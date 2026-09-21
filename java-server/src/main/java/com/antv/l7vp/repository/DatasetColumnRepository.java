package com.antv.l7vp.repository;

import com.antv.l7vp.model.DatasetColumn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class DatasetColumnRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<DatasetColumn> findByDatasetId(String datasetId) {
        String sql = "SELECT COLUMN_ID, DATASET_ID, COLUMN_NAME, COLUMN_TYPE, COLUMN_INDEX, COLUMN_COMMENT FROM DATASET_COLUMNS WHERE DATASET_ID = ? ORDER BY COLUMN_INDEX";
        return jdbcTemplate.query(sql, new Object[]{datasetId}, new DatasetColumnRowMapper());
    }

    public void batchInsert(String datasetId, List<DatasetColumn> columns) {
        String sql = "INSERT INTO DATASET_COLUMNS (COLUMN_ID, DATASET_ID, COLUMN_NAME, COLUMN_TYPE, COLUMN_INDEX, COLUMN_COMMENT) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, columns, columns.size(), (ps, column) -> {
            if (column.getColumnId() == null) {
                column.setColumnId(UUID.randomUUID().toString());
            }
            column.setDatasetId(datasetId);
            ps.setString(1, column.getColumnId());
            ps.setString(2, datasetId);
            ps.setString(3, column.getColumnName());
            ps.setString(4, column.getColumnType());
            ps.setInt(5, column.getColumnIndex() != null ? column.getColumnIndex() : 0);
            ps.setString(6, column.getColumnComment());
        });
    }

    public void deleteByDatasetId(String datasetId) {
        String sql = "DELETE FROM DATASET_COLUMNS WHERE DATASET_ID = ?";
        jdbcTemplate.update(sql, datasetId);
    }

    static class DatasetColumnRowMapper implements RowMapper<DatasetColumn> {
        @Override
        public DatasetColumn mapRow(ResultSet rs, int rowNum) throws SQLException {
            DatasetColumn column = new DatasetColumn();
            column.setColumnId(rs.getString("COLUMN_ID"));
            column.setDatasetId(rs.getString("DATASET_ID"));
            column.setColumnName(rs.getString("COLUMN_NAME"));
            column.setColumnType(rs.getString("COLUMN_TYPE"));
            column.setColumnIndex(rs.getInt("COLUMN_INDEX"));
            column.setColumnComment(rs.getString("COLUMN_COMMENT"));
            return column;
        }
    }
}
