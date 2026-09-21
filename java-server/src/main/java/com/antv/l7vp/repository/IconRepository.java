package com.antv.l7vp.repository;

import com.antv.l7vp.model.IconItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class IconRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<IconItem> findByCategoryId(String categoryId) {
        String sql = "SELECT ICON_ID, CATEGORY_ID, LIBRARY_CODE, CODE_NAME, FILE_NAME, ORIGINAL_NAME, FILE_TYPE, FILE_SIZE, URL, SORT_ORDER, CREATE_TIME FROM ICONS WHERE CATEGORY_ID = ? ORDER BY SORT_ORDER";
        return jdbcTemplate.query(sql, new Object[]{categoryId}, new IconItemRowMapper());
    }

    public IconItem findById(String iconId) {
        String sql = "SELECT ICON_ID, CATEGORY_ID, LIBRARY_CODE, CODE_NAME, FILE_NAME, ORIGINAL_NAME, FILE_TYPE, FILE_SIZE, URL, SORT_ORDER, CREATE_TIME FROM ICONS WHERE ICON_ID = ?";
        List<IconItem> icons = jdbcTemplate.query(sql, new Object[]{iconId}, new IconItemRowMapper());
        return icons.isEmpty() ? null : icons.get(0);
    }

    public IconItem findByCode(String libraryCode, String codeName) {
        String sql = "SELECT ICON_ID, CATEGORY_ID, LIBRARY_CODE, CODE_NAME, FILE_NAME, ORIGINAL_NAME, FILE_TYPE, FILE_SIZE, URL, SORT_ORDER, CREATE_TIME FROM ICONS WHERE LIBRARY_CODE = ? AND CODE_NAME = ?";
        List<IconItem> icons = jdbcTemplate.query(sql, new Object[]{libraryCode, codeName}, new IconItemRowMapper());
        return icons.isEmpty() ? null : icons.get(0);
    }

    public IconItem insert(IconItem icon) {
        if (icon.getIconId() == null) {
            icon.setIconId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO ICONS (ICON_ID, CATEGORY_ID, LIBRARY_CODE, CODE_NAME, FILE_NAME, ORIGINAL_NAME, FILE_TYPE, FILE_SIZE, URL, SORT_ORDER, CREATE_TIME) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            icon.getIconId(),
            icon.getCategoryId(),
            icon.getLibraryCode(),
            icon.getCodeName(),
            icon.getFileName(),
            icon.getOriginalName(),
            icon.getFileType(),
            icon.getFileSize(),
            icon.getUrl(),
            icon.getSortOrder(),
            icon.getCreateTime()
        );
        return icon;
    }

    public IconItem update(IconItem icon) {
        String sql = "UPDATE ICONS SET CATEGORY_ID = ?, LIBRARY_CODE = ?, CODE_NAME = ?, FILE_NAME = ?, ORIGINAL_NAME = ?, FILE_TYPE = ?, FILE_SIZE = ?, URL = ?, SORT_ORDER = ? WHERE ICON_ID = ?";
        jdbcTemplate.update(sql,
            icon.getCategoryId(),
            icon.getLibraryCode(),
            icon.getCodeName(),
            icon.getFileName(),
            icon.getOriginalName(),
            icon.getFileType(),
            icon.getFileSize(),
            icon.getUrl(),
            icon.getSortOrder(),
            icon.getIconId()
        );
        return icon;
    }

    public void deleteById(String iconId) {
        String sql = "DELETE FROM ICONS WHERE ICON_ID = ?";
        jdbcTemplate.update(sql, iconId);
    }

    public void deleteByCategoryId(String categoryId) {
        String sql = "DELETE FROM ICONS WHERE CATEGORY_ID = ?";
        jdbcTemplate.update(sql, categoryId);
    }

    public void batchUpdateOrder(List<Map<String, Object>> orders) {
        String sql = "UPDATE ICONS SET SORT_ORDER = ? WHERE ICON_ID = ?";
        jdbcTemplate.batchUpdate(sql, orders, orders.size(), (ps, order) -> {
            ps.setInt(1, ((Number) order.get("sortOrder")).intValue());
            ps.setString(2, (String) order.get("iconId"));
        });
    }

    static class IconItemRowMapper implements RowMapper<IconItem> {
        @Override
        public IconItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            IconItem icon = new IconItem();
            icon.setIconId(rs.getString("ICON_ID"));
            icon.setCategoryId(rs.getString("CATEGORY_ID"));
            icon.setLibraryCode(rs.getString("LIBRARY_CODE"));
            icon.setCodeName(rs.getString("CODE_NAME"));
            icon.setFileName(rs.getString("FILE_NAME"));
            icon.setOriginalName(rs.getString("ORIGINAL_NAME"));
            icon.setFileType(rs.getString("FILE_TYPE"));
            icon.setFileSize(rs.getLong("FILE_SIZE"));
            icon.setUrl(rs.getString("URL"));
            icon.setSortOrder(rs.getInt("SORT_ORDER"));
            icon.setCreateTime(rs.getString("CREATE_TIME"));
            return icon;
        }
    }
}
