package com.antv.l7vp.repository;

import com.antv.l7vp.model.DatasetRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class DatasetRowRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Map<String, Object> findByDatasetId(String datasetId, int page, int size) {
        String countSql = "SELECT COUNT(*) FROM DATASET_ROWS WHERE DATASET_ID = ?";
        Integer total = jdbcTemplate.queryForObject(countSql, new Object[]{datasetId}, Integer.class);
        if (total == null) total = 0;

        int offset = page * size;
        String sql = "SELECT ROW_ID, DATASET_ID, ROW_INDEX, ROW_DATA FROM DATASET_ROWS WHERE DATASET_ID = ? ORDER BY ROW_INDEX LIMIT ? OFFSET ?";
        List<DatasetRow> rows = jdbcTemplate.query(sql, new Object[]{datasetId, size, offset}, new DatasetRowRowMapper());

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    public void batchInsert(String datasetId, List<DatasetRow> rows, int batchSize) {
        String sql = "INSERT INTO DATASET_ROWS (ROW_ID, DATASET_ID, ROW_INDEX, ROW_DATA) VALUES (?, ?, ?, ?)";
        jdbcTemplate.batchUpdate(sql, rows, batchSize, (ps, row) -> {
            if (row.getRowId() == null) {
                row.setRowId(UUID.randomUUID().toString());
            }
            row.setDatasetId(datasetId);
            ps.setString(1, row.getRowId());
            ps.setString(2, datasetId);
            ps.setInt(3, row.getRowIndex() != null ? row.getRowIndex() : 0);
            ps.setString(4, row.getRowData());
        });
    }

    public void deleteByDatasetId(String datasetId) {
        String sql = "DELETE FROM DATASET_ROWS WHERE DATASET_ID = ?";
        jdbcTemplate.update(sql, datasetId);
    }

    static class DatasetRowRowMapper implements RowMapper<DatasetRow> {
        @Override
        public DatasetRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            DatasetRow row = new DatasetRow();
            row.setRowId(rs.getString("ROW_ID"));
            row.setDatasetId(rs.getString("DATASET_ID"));
            row.setRowIndex(rs.getInt("ROW_INDEX"));
            row.setRowData(rs.getString("ROW_DATA"));
            return row;
        }
    }
}
