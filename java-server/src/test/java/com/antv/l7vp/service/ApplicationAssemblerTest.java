package com.antv.l7vp.service;

import com.antv.l7vp.model.*;
import com.antv.l7vp.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationAssemblerTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private DatasetRepository datasetRepository;
    @Mock
    private DatasetColumnRepository datasetColumnRepository;
    @Mock
    private DatasetRowRepository datasetRowRepository;
    @Mock
    private LayerRepository layerRepository;
    @Mock
    private WidgetRepository widgetRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ApplicationAssembler assembler;

    private String projectId;
    private Project project;

    @BeforeEach
    void setUp() {
        projectId = "proj-001";
        project = new Project();
        project.setProjectId(projectId);
        project.setProjectName("Test Project");
        project.setDescription("Test Description");
        project.setCreateTime("2026-01-01 10:00:00");
        project.setUpdateTime("2026-06-01 10:00:00");
        project.setThumbnail("/thumbnails/test.png");
        project.setAssetPackageIds(Arrays.asList("pkg-1", "pkg-2"));
    }

    // =========== assemble() tests ===========

    @Test
    void should_assemble_application_json_when_project_exists() {
        when(projectRepository.findById(projectId)).thenReturn(project);
        when(datasetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(layerRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(widgetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());

        Map<String, Object> result = assembler.assemble(projectId);

        assertNotNull(result);
        assertTrue(result.containsKey("metadata"));
        assertTrue(result.containsKey("datasets"));
        assertTrue(result.containsKey("spec"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertEquals("Test Project", metadata.get("name"));
        assertEquals("Test Description", metadata.get("description"));

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) result.get("spec");
        assertNotNull(spec.get("layers"));
        assertNotNull(spec.get("widgets"));
        assertNotNull(spec.get("map"));
    }

    @Test
    void should_assemble_datasets_with_lazy_flag_for_local_type() throws Exception {
        Dataset dataset = new Dataset();
        dataset.setDatasetId("ds-001");
        dataset.setProjectId(projectId);
        dataset.setDatasetName("Local Data");
        dataset.setType("local");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rowCount", 5000);
        dataset.setMetadata("{\"rowCount\":5000}");

        when(projectRepository.findById(projectId)).thenReturn(project);
        when(datasetRepository.findByProjectId(projectId)).thenReturn(Collections.singletonList(dataset));
        when(datasetColumnRepository.findByDatasetId("ds-001")).thenReturn(Collections.emptyList());
        when(layerRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(widgetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(objectMapper.readValue(eq("{\"rowCount\":5000}"), eq(Map.class))).thenReturn(meta);

        Map<String, Object> result = assembler.assemble(projectId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) result.get("datasets");
        assertEquals(1, datasets.size());
        Map<String, Object> ds = datasets.get(0);
        assertEquals("ds-001", ds.get("id"));
        assertEquals("local", ds.get("type"));
        assertEquals(true, ds.get("_lazy"));
        assertTrue(ds.containsKey("_rowCount"));
        assertTrue(((List<?>) ds.get("data")).isEmpty());
    }

    @Test
    void should_assemble_non_local_dataset_without_lazy_flag() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId("ds-002");
        dataset.setProjectId(projectId);
        dataset.setDatasetName("Remote Data");
        dataset.setType("remote");
        dataset.setMetadata("{}");

        when(projectRepository.findById(projectId)).thenReturn(project);
        when(datasetRepository.findByProjectId(projectId)).thenReturn(Collections.singletonList(dataset));
        when(datasetColumnRepository.findByDatasetId("ds-002")).thenReturn(Collections.emptyList());
        when(layerRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(widgetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());

        Map<String, Object> result = assembler.assemble(projectId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) result.get("datasets");
        assertEquals(1, datasets.size());
        assertNull(datasets.get(0).get("_lazy"));
    }

    @Test
    void should_assemble_layers_ordered() throws Exception {
        Layer layer1 = new Layer();
        layer1.setLayerId("layer-1");
        layer1.setProjectId(projectId);
        layer1.setLayerName("First Layer");
        layer1.setType("BubbleLayer");
        layer1.setLayerOrder(0);
        layer1.setVisConfig("{\"color\":\"red\"}");

        Layer layer2 = new Layer();
        layer2.setLayerId("layer-2");
        layer2.setProjectId(projectId);
        layer2.setLayerName("Second Layer");
        layer2.setType("TileLayer");
        layer2.setLayerOrder(1);

        when(projectRepository.findById(projectId)).thenReturn(project);
        when(datasetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(layerRepository.findByProjectId(projectId)).thenReturn(Arrays.asList(layer1, layer2));
        when(widgetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());

        when(objectMapper.readValue(eq("{\"color\":\"red\"}"), eq(Map.class)))
            .thenReturn(Collections.singletonMap("color", "red"));

        Map<String, Object> result = assembler.assemble(projectId);

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) result.get("spec");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) spec.get("layers");
        assertEquals(2, layers.size());
        assertEquals("layer-1", layers.get(0).get("id"));
        assertEquals("BubbleLayer", layers.get(0).get("type"));
    }

    @Test
    void should_throw_when_project_not_found_for_assemble() {
        when(projectRepository.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class, () -> assembler.assemble("nonexistent"));
    }

    // =========== disassemble() tests ===========

    @Test
    void should_disassemble_update_project_metadata() {
        when(projectRepository.findById(projectId)).thenReturn(project);

        Map<String, Object> application = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "Updated Name");
        metadata.put("description", "Updated Desc");
        application.put("metadata", metadata);
        application.put("datasets", Collections.emptyList());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("layers", Collections.emptyList());
        spec.put("widgets", Collections.emptyList());
        application.put("spec", spec);

        assembler.disassemble(projectId, application);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).update(captor.capture());
        Project updated = captor.getValue();
        assertEquals("Updated Name", updated.getProjectName());
        assertEquals("Updated Desc", updated.getDescription());
        assertNotNull(updated.getUpdateTime());
    }

    @Test
    void should_disassemble_insert_new_layers() {
        when(projectRepository.findById(projectId)).thenReturn(project);
        when(datasetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(layerRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(widgetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());

        Map<String, Object> application = new LinkedHashMap<>();
        application.put("metadata", new LinkedHashMap<>());
        application.put("datasets", Collections.emptyList());

        Map<String, Object> spec = new LinkedHashMap<>();
        Map<String, Object> newLayer = new LinkedHashMap<>();
        newLayer.put("id", "new-layer-1");
        newLayer.put("name", "New Layer");
        newLayer.put("type", "IconLayer");
        spec.put("layers", Collections.singletonList(newLayer));
        spec.put("widgets", Collections.emptyList());
        application.put("spec", spec);

        assembler.disassemble(projectId, application);

        verify(layerRepository).insert(any(Layer.class));
    }

    @Test
    void should_disassemble_delete_removed_widgets() {
        when(projectRepository.findById(projectId)).thenReturn(project);
        when(datasetRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(layerRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());

        Widget oldWidget = new Widget();
        oldWidget.setWidgetId("widget-to-delete");
        oldWidget.setProjectId(projectId);
        oldWidget.setWidgetName("Old Widget");
        when(widgetRepository.findByProjectId(projectId)).thenReturn(Collections.singletonList(oldWidget));

        Map<String, Object> application = new LinkedHashMap<>();
        application.put("metadata", new LinkedHashMap<>());
        application.put("datasets", Collections.emptyList());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("layers", Collections.emptyList());
        spec.put("widgets", Collections.emptyList()); // no widgets in new spec — old one should be deleted
        application.put("spec", spec);

        assembler.disassemble(projectId, application);

        verify(widgetRepository).deleteById("widget-to-delete");
    }

    @Test
    void should_disassemble_throw_when_project_not_found() {
        when(projectRepository.findById("nonexistent")).thenReturn(null);

        Map<String, Object> application = new LinkedHashMap<>();
        assertThrows(RuntimeException.class, () -> assembler.disassemble("nonexistent", application));
    }
}
