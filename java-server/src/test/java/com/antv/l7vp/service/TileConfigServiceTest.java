package com.antv.l7vp.service;

import com.antv.l7vp.model.TileConfig;
import com.antv.l7vp.repository.TileConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TileConfigServiceTest {

    @Mock
    private TileConfigRepository tileConfigRepository;

    @InjectMocks
    private TileConfigService tileConfigService;

    @Test
    void should_get_config() {
        TileConfig config = new TileConfig();
        config.setId("default");
        config.setTileUrl("https://example.com/{z}/{x}/{y}");
        when(tileConfigRepository.findDefault()).thenReturn(config);

        TileConfig result = tileConfigService.getConfig();

        assertNotNull(result);
        assertEquals("https://example.com/{z}/{x}/{y}", result.getTileUrl());
    }

    @Test
    void should_get_config_return_null_when_not_found() {
        when(tileConfigRepository.findDefault()).thenReturn(null);

        TileConfig result = tileConfigService.getConfig();

        assertNull(result);
    }

    @Test
    void should_save_config() {
        TileConfig config = new TileConfig();
        config.setTileUrl("https://new.example.com/{z}/{x}/{y}");
        config.setTileName("New Map");
        config.setMinZoom(1);
        config.setMaxZoom(20);

        when(tileConfigRepository.findById("default")).thenReturn(null);
        when(tileConfigRepository.insert(any(TileConfig.class))).thenReturn(config);

        TileConfig result = tileConfigService.saveConfig(config);

        assertNotNull(result);
        assertEquals("default", result.getId());
        assertNotNull(result.getUpdateTime());
        verify(tileConfigRepository).findById("default");
        verify(tileConfigRepository).insert(any(TileConfig.class));
    }
}
