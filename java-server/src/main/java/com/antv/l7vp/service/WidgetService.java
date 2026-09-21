package com.antv.l7vp.service;

import com.antv.l7vp.model.Widget;
import com.antv.l7vp.repository.WidgetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class WidgetService {

    @Autowired
    private WidgetRepository widgetRepository;

    public List<Widget> findByProjectId(String projectId) {
        return widgetRepository.findByProjectId(projectId);
    }

    public void saveWidgets(String projectId, List<Widget> widgets) {
        List<Widget> existingWidgets = widgetRepository.findByProjectId(projectId);

        Set<String> existingIds = new HashSet<>();
        for (Widget widget : existingWidgets) {
            existingIds.add(widget.getWidgetId());
        }

        Set<String> newIds = new HashSet<>();
        List<Widget> toInsert = new ArrayList<>();
        for (Widget widget : widgets) {
            if (widget.getWidgetId() == null || widget.getWidgetId().isEmpty()) {
                toInsert.add(widget);
            } else {
                newIds.add(widget.getWidgetId());
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (String id : existingIds) {
            if (!newIds.contains(id)) {
                toDelete.add(id);
            }
        }

        for (Widget widget : toInsert) {
            widget.setProjectId(projectId);
            widgetRepository.insert(widget);
        }

        for (Widget widget : widgets) {
            if (widget.getWidgetId() != null && existingIds.contains(widget.getWidgetId())) {
                widget.setProjectId(projectId);
                widgetRepository.update(widget);
            }
        }

        for (String id : toDelete) {
            widgetRepository.deleteById(id);
        }
    }

    public void deleteByProjectId(String projectId) {
        widgetRepository.deleteByProjectId(projectId);
    }
}
