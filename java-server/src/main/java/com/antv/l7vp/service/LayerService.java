package com.antv.l7vp.service;

import com.antv.l7vp.model.Layer;
import com.antv.l7vp.repository.LayerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class LayerService {

    @Autowired
    private LayerRepository layerRepository;

    public List<Layer> findByProjectId(String projectId) {
        return layerRepository.findByProjectId(projectId);
    }

    public void saveLayers(String projectId, List<Layer> layers) {
        List<Layer> existingLayers = layerRepository.findByProjectId(projectId);

        Set<String> existingIds = new HashSet<>();
        for (Layer layer : existingLayers) {
            existingIds.add(layer.getLayerId());
        }

        Set<String> newIds = new HashSet<>();
        List<Layer> toInsert = new ArrayList<>();
        for (Layer layer : layers) {
            if (layer.getLayerId() == null || layer.getLayerId().isEmpty()) {
                toInsert.add(layer);
            } else {
                newIds.add(layer.getLayerId());
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (String id : existingIds) {
            if (!newIds.contains(id)) {
                toDelete.add(id);
            }
        }

        for (Layer layer : toInsert) {
            layer.setProjectId(projectId);
            layerRepository.insert(layer);
        }

        for (Layer layer : layers) {
            if (layer.getLayerId() != null && existingIds.contains(layer.getLayerId())) {
                layer.setProjectId(projectId);
                layerRepository.update(layer);
            }
        }

        for (String id : toDelete) {
            layerRepository.deleteById(id);
        }
    }

    public void deleteByProjectId(String projectId) {
        layerRepository.deleteByProjectId(projectId);
    }
}
