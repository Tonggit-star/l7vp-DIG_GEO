package com.antv.l7vp.service;

import com.antv.l7vp.model.TileConfig;
import com.antv.l7vp.repository.TileConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class TileConfigService {

    @Autowired
    private TileConfigRepository tileConfigRepository;

    public List<TileConfig> listConfigs() {
        return tileConfigRepository.findAll();
    }

    public TileConfig getConfig() {
        return tileConfigRepository.findDefault();
    }

    @Transactional
    public TileConfig createConfig(TileConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(java.util.UUID.randomUUID().toString());
        }
        config.setUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return tileConfigRepository.insert(config);
    }

    @Transactional
    public TileConfig updateConfig(String id, TileConfig config) {
        config.setId(id);
        config.setUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return tileConfigRepository.update(config);
    }

    public void deleteConfig(String id) {
        tileConfigRepository.deleteById(id);
    }

    public TileConfig saveConfig(TileConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId("default");
        }
        config.setUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        TileConfig existing = tileConfigRepository.findById(config.getId());
        if (existing != null) {
            return tileConfigRepository.update(config);
        } else {
            return tileConfigRepository.insert(config);
        }
    }
}
