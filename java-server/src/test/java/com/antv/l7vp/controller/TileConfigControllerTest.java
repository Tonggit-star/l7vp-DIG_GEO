package com.antv.l7vp.controller;

import com.antv.l7vp.config.L7vpAuthProperties;
import com.antv.l7vp.model.TileConfig;
import com.antv.l7vp.service.TileConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TileConfigController.class)
class TileConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TileConfigService tileConfigService;

    /** WebConfig 实现 WebMvcConfigurer，会被切片带进来；它的 localUserFilter 需要这个配置 bean */
    @MockBean
    private L7vpAuthProperties l7vpAuthProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void should_get_config_return_ok() throws Exception {
        TileConfig config = new TileConfig();
        config.setId("default");
        config.setTileUrl("https://example.com/{z}/{x}/{y}");
        config.setTileName("Test Map");
        config.setMinZoom(0);
        config.setMaxZoom(18);

        when(tileConfigService.getConfig()).thenReturn(config);

        mockMvc.perform(get("/api/tile-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tileUrl").value("https://example.com/{z}/{x}/{y}"))
            .andExpect(jsonPath("$.tileName").value("Test Map"));
    }

    @Test
    void should_get_config_return_no_content_when_null() throws Exception {
        when(tileConfigService.getConfig()).thenReturn(null);

        mockMvc.perform(get("/api/tile-config"))
            .andExpect(status().isNoContent());
    }

    @Test
    void should_update_config() throws Exception {
        TileConfig config = new TileConfig();
        config.setId("default");
        config.setTileUrl("https://new.example.com/{z}/{x}/{y}");
        config.setTileName("New Map");

        when(tileConfigService.saveConfig(any(TileConfig.class))).thenReturn(config);

        mockMvc.perform(put("/api/tile-config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(config)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tileUrl").value("https://new.example.com/{z}/{x}/{y}"));
    }
}
