package com.antv.l7vp.repository;

import com.antv.l7vp.model.Widget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class WidgetRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<Widget> findByProjectId(String projectId) {
        String sql = "SELECT WIDGET_ID, PROJECT_ID, CONTAINER_ID, WIDGET_NAME, TYPE, PROPERTIES, SLOT, WIDGET_ORDER FROM WIDGETS WHERE PROJECT_ID = ? ORDER BY WIDGET_ORDER";
        return jdbcTemplate.query(sql, new Object[]{projectId}, new WidgetRowMapper());
    }

    public Widget findById(String widgetId) {
        String sql = "SELECT WIDGET_ID, PROJECT_ID, CONTAINER_ID, WIDGET_NAME, TYPE, PROPERTIES, SLOT, WIDGET_ORDER FROM WIDGETS WHERE WIDGET_ID = ?";
        List<Widget> widgets = jdbcTemplate.query(sql, new Object[]{widgetId}, new WidgetRowMapper());
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    public Widget insert(Widget widget) {
        if (widget.getWidgetId() == null) {
            widget.setWidgetId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO WIDGETS (WIDGET_ID, PROJECT_ID, CONTAINER_ID, WIDGET_NAME, TYPE, PROPERTIES, SLOT, WIDGET_ORDER) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
            widget.getWidgetId(),
            widget.getProjectId(),
            widget.getContainerId(),
            widget.getWidgetName(),
            widget.getType(),
            widget.getProperties(),
            widget.getSlot(),
            widget.getWidgetOrder()
        );
        return widget;
    }

    public Widget update(Widget widget) {
        String sql = "UPDATE WIDGETS SET PROJECT_ID = ?, CONTAINER_ID = ?, WIDGET_NAME = ?, TYPE = ?, PROPERTIES = ?, SLOT = ?, WIDGET_ORDER = ? WHERE WIDGET_ID = ?";
        jdbcTemplate.update(sql,
            widget.getProjectId(),
            widget.getContainerId(),
            widget.getWidgetName(),
            widget.getType(),
            widget.getProperties(),
            widget.getSlot(),
            widget.getWidgetOrder(),
            widget.getWidgetId()
        );
        return widget;
    }

    public void deleteById(String widgetId) {
        String sql = "DELETE FROM WIDGETS WHERE WIDGET_ID = ?";
        jdbcTemplate.update(sql, widgetId);
    }

    public void deleteByProjectId(String projectId) {
        String sql = "DELETE FROM WIDGETS WHERE PROJECT_ID = ?";
        jdbcTemplate.update(sql, projectId);
    }

    static class WidgetRowMapper implements RowMapper<Widget> {
        @Override
        public Widget mapRow(ResultSet rs, int rowNum) throws SQLException {
            Widget widget = new Widget();
            widget.setWidgetId(rs.getString("WIDGET_ID"));
            widget.setProjectId(rs.getString("PROJECT_ID"));
            widget.setContainerId(rs.getString("CONTAINER_ID"));
            widget.setWidgetName(rs.getString("WIDGET_NAME"));
            widget.setType(rs.getString("TYPE"));
            widget.setProperties(rs.getString("PROPERTIES"));
            widget.setSlot(rs.getString("SLOT"));
            widget.setWidgetOrder(rs.getInt("WIDGET_ORDER"));
            return widget;
        }
    }
}
