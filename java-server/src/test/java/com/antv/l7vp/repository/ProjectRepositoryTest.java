package com.antv.l7vp.repository;

import com.antv.l7vp.model.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProjectRepository projectRepository;

    @Test
    void should_find_all_projects() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
            .thenReturn(Collections.emptyList());

        List<Project> projects = projectRepository.findAll();

        assertNotNull(projects);
        assertTrue(projects.isEmpty());
    }

    @Test
    void should_find_by_id_returns_null_when_not_found() {
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
            .thenReturn(Collections.emptyList());

        Project project = projectRepository.findById("nonexistent");

        assertNull(project);
    }

    @Test
    void should_insert_project_with_generated_uuid() throws Exception {
        Project project = new Project();
        project.setProjectName("New Project");
        project.setDescription("Desc");
        project.setCreateTime("2026-01-01");

        Project result = projectRepository.insert(project);

        assertNotNull(result);
        assertNotNull(result.getProjectId());
        assertEquals("New Project", result.getProjectName());
    }

    @Test
    void should_insert_project_with_existing_id() throws Exception {
        Project project = new Project();
        project.setProjectId("existing-id");
        project.setProjectName("Test");

        Project result = projectRepository.insert(project);

        assertEquals("existing-id", result.getProjectId());
    }

    @Test
    void should_update_project() throws Exception {
        Project project = new Project();
        project.setProjectId("proj-1");
        project.setProjectName("Updated");

        Project result = projectRepository.update(project);

        assertEquals("Updated", result.getProjectName());
    }

    @Test
    void should_delete_by_id() {
        projectRepository.deleteById("proj-1");

        verify(jdbcTemplate).update(contains("DELETE"), eq("proj-1"));
    }
}
