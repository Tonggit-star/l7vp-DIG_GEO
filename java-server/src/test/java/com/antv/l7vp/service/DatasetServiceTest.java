package com.antv.l7vp.service;

import com.antv.l7vp.dto.CreateDatasetRequest;
import com.antv.l7vp.dto.CreateDatasetResult;
import com.antv.l7vp.dto.PagedRows;
import com.antv.l7vp.dto.ColumnDef;
import com.antv.l7vp.model.Dataset;
import com.antv.l7vp.repository.DatasetColumnRepository;
import com.antv.l7vp.repository.DatasetRepository;
import com.antv.l7vp.repository.DatasetRowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasetServiceTest {

    @Mock
    private DatasetRepository datasetRepository;
    @Mock
    private DatasetColumnRepository datasetColumnRepository;
    @Mock
    private DatasetRowRepository datasetRowRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private DatasetService datasetService;

    @Test
    void should_create_dataset_with_rows() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        // 模拟 insert 后设置 UUID
        doAnswer(inv -> {
            Dataset ds = inv.getArgument(0);
            ds.setDatasetId("generated-ds-1");
            return ds;
        }).when(datasetRepository).insert(any(Dataset.class));

        CreateDatasetRequest request = new CreateDatasetRequest();
        request.setDatasetName("Test Dataset");
        request.setType("local");
        List<ColumnDef> columns = new ArrayList<>();
        ColumnDef col = new ColumnDef();
        col.setName("city");
        col.setType("string");
        col.setIndex(0);
        columns.add(col);
        request.setColumns(columns);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("city", "Beijing");
        row.put("population", 21540000);
        request.setRows(Collections.singletonList(row));

        CreateDatasetResult result = datasetService.createDatasetWithRows("proj-1", request);

        assertNotNull(result);
        assertEquals("generated-ds-1", result.getDatasetId());
        assertEquals(1, result.getRowCount());
        verify(datasetRepository).insert(any(Dataset.class));
        verify(datasetColumnRepository).batchInsert(anyString(), anyList());
        verify(datasetRowRepository).batchInsert(anyString(), anyList(), anyInt());
    }

    @Test
    void should_create_dataset_without_rows() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doAnswer(inv -> {
            Dataset ds = inv.getArgument(0);
            ds.setDatasetId("generated-ds-2");
            return ds;
        }).when(datasetRepository).insert(any(Dataset.class));

        CreateDatasetRequest request = new CreateDatasetRequest();
        request.setDatasetName("Empty Dataset");
        request.setType("remote");
        request.setColumns(Collections.emptyList());
        request.setRows(Collections.emptyList());

        CreateDatasetResult result = datasetService.createDatasetWithRows("proj-1", request);

        assertNotNull(result);
        assertEquals(0, result.getRowCount());
    }

    @Test
    void should_get_rows() throws Exception {
        Dataset dataset = new Dataset();
        dataset.setDatasetId("ds-1");
        dataset.setProjectId("proj-1");
        when(datasetRepository.findById("ds-1")).thenReturn(dataset);

        Map<String, Object> rowResult = new LinkedHashMap<>();
        rowResult.put("rows", Collections.emptyList());
        rowResult.put("total", 0);
        when(datasetRowRepository.findByDatasetId("ds-1", 0, 500)).thenReturn(rowResult);
        when(datasetColumnRepository.findByDatasetId("ds-1")).thenReturn(Collections.emptyList());

        PagedRows result = datasetService.getRows("ds-1", 0, 500);

        assertNotNull(result);
        assertEquals(0, result.getTotal());
    }

    @Test
    void should_throw_when_get_rows_for_nonexistent_dataset() {
        when(datasetRepository.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> datasetService.getRows("nonexistent", 0, 500));
    }

    @Test
    void should_delete_dataset() {
        datasetService.deleteDataset("ds-1");

        verify(datasetRowRepository).deleteByDatasetId("ds-1");
        verify(datasetColumnRepository).deleteByDatasetId("ds-1");
        verify(datasetRepository).deleteById("ds-1");
    }

    @Test
    void should_update_dataset_meta() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId("ds-1");
        dataset.setDatasetName("Old Name");
        when(datasetRepository.findById("ds-1")).thenReturn(dataset);

        Dataset result = datasetService.updateDatasetMeta("ds-1", "New Name", null);

        assertNotNull(result);
        assertEquals("New Name", result.getDatasetName());
    }

    @Test
    void should_find_by_project_id() {
        when(datasetRepository.findByProjectId("proj-1")).thenReturn(Collections.emptyList());

        List<Dataset> result = datasetService.findByProjectId("proj-1");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
