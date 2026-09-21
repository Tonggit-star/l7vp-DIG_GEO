package com.antv.l7vp.controller;

import com.antv.l7vp.model.TileConfig;
import com.antv.l7vp.service.TileConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class TileConfigController {

    @Autowired
    private TileConfigService tileConfigService;

    /** 获取所有瓦片配置 */
    @GetMapping("/tile-configs")
    public List<TileConfig> listConfigs() {
        return tileConfigService.listConfigs();
    }

    /** 获取默认瓦片（兼容旧 API） */
    @GetMapping("/tile-config")
    public ResponseEntity<TileConfig> getConfig() {
        TileConfig config = tileConfigService.getConfig();
        if (config == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(config);
    }

    /** 新增瓦片 */
    @PostMapping("/tile-configs")
    public TileConfig createConfig(@RequestBody TileConfig config) {
        return tileConfigService.createConfig(config);
    }

    /** 更新瓦片 */
    @PutMapping("/tile-configs/{id}")
    public TileConfig updateConfig(@PathVariable String id, @RequestBody TileConfig config) {
        return tileConfigService.updateConfig(id, config);
    }

    /** 删除瓦片 */
    @DeleteMapping("/tile-configs/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable String id) {
        tileConfigService.deleteConfig(id);
        return ResponseEntity.ok().build();
    }

    /** 保存瓦片（兼容旧 API: PUT /api/tile-config） */
    @PutMapping("/tile-config")
    public TileConfig updateConfig(@RequestBody TileConfig config) {
        return tileConfigService.saveConfig(config);
    }
}
