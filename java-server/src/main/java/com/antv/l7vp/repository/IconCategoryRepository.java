package com.antv.l7vp.repository;

import com.antv.l7vp.model.IconCategory;
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
public class IconCategoryRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<IconCategory> findAll() {
        String sql = "SELECT CATEGORY_ID, CATEGORY_NAME, SORT_ORDER, CREATE_TIME FROM ICON_CATEGORIES ORDER BY SORT_ORDER";
        return jdbcTemplate.query(sql, new IconCategoryRowMapper());
    }

    public IconCategory findById(String categoryId) {
        String sql = "SELECT CATEGORY_ID, CATEGORY_NAME, SORT_ORDER, CREATE_TIME FROM ICON_CATEGORIES WHERE CATEGORY_ID = ?";
        List<IconCategory> categories = jdbcTemplate.query(sql, new Object[]{categoryId}, new IconCategoryRowMapper());
        return categories.isEmpty() ? null : categories.get(0);
    }

    public IconCategory insert(IconCategory category) {
        if (category.getCategoryId() == null) {
            category.setCategoryId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO ICON_CATEGORIES (CATEGORY_ID, CATEGORY_NAME, SORT_ORDER, CREATE_TIME) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            category.getCategoryId(),
            category.getCategoryName(),
            category.getSortOrder(),
            category.getCreateTime()
        );
        return category;
    }

    public IconCategory update(IconCategory category) {
        String sql = "UPDATE ICON_CATEGORIES SET CATEGORY_NAME = ?, SORT_ORDER = ? WHERE CATEGORY_ID = ?";
        jdbcTemplate.update(sql,
            category.getCategoryName(),
            category.getSortOrder(),
            category.getCategoryId()
        );
        return category;
    }

    public void deleteById(String categoryId) {
        String sql = "DELETE FROM ICON_CATEGORIES WHERE CATEGORY_ID = ?";
        jdbcTemplate.update(sql, categoryId);
    }

    public void batchUpdateOrder(List<Map<String, Object>> orders) {
        String sql = "UPDATE ICON_CATEGORIES SET SORT_ORDER = ? WHERE CATEGORY_ID = ?";
        jdbcTemplate.batchUpdate(sql, orders, orders.size(), (ps, order) -> {
            ps.setInt(1, ((Number) order.get("sortOrder")).intValue());
            ps.setString(2, (String) order.get("categoryId"));
        });
    }

    static class IconCategoryRowMapper implements RowMapper<IconCategory> {
        @Override
        public IconCategory mapRow(ResultSet rs, int rowNum) throws SQLException {
            IconCategory category = new IconCategory();
            category.setCategoryId(rs.getString("CATEGORY_ID"));
            category.setCategoryName(rs.getString("CATEGORY_NAME"));
            category.setSortOrder(rs.getInt("SORT_ORDER"));
            category.setCreateTime(rs.getString("CREATE_TIME"));
            return category;
        }
    }
}
