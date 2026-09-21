package com.antv.l7vp.service;

import com.antv.l7vp.model.Project;
import com.antv.l7vp.repository.ProjectRepository;
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
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ApplicationAssembler applicationAssembler;

    @InjectMocks
    private ProjectService projectService;

    @Test
    void should_list_all_projects() {
        when(projectRepository.findAll()).thenReturn(Collections.emptyList());

        List<Project> projects = projectService.listProjects();

        assertNotNull(projects);
        assertTrue(projects.isEmpty());
    }

    @Test
    void should_get_project_application() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("metadata", new LinkedHashMap<>());
        when(applicationAssembler.assemble("proj-1")).thenReturn(expected);

        Map<String, Object> result = projectService.getProjectApplication("proj-1");

        assertNotNull(result);
        assertEquals(expected, result);
    }

    @Test
    void should_create_project() {
        Map<String, Object> application = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "New Project");
        application.put("metadata", metadata);
        application.put("datasets", Collections.emptyList());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("layers", Collections.emptyList());
        spec.put("widgets", Collections.emptyList());
        application.put("spec", spec);

        Map<String, Object> assembled = new LinkedHashMap<>();
        assembled.put("metadata", metadata);
        // 模拟 insert 设置 UUID
        doAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setProjectId("generated-uuid");
            return p;
        }).when(projectRepository).insert(any(Project.class));
        when(applicationAssembler.assemble(anyString())).thenReturn(assembled);

        Map<String, Object> result = projectService.createProject(application);

        assertNotNull(result);
        verify(projectRepository).insert(any(Project.class));
    }

    @Test
    void should_update_project() {
        Project project = new Project();
        project.setProjectId("proj-1");
        project.setProjectName("Old Name");
        when(projectRepository.findById("proj-1")).thenReturn(project);

        Map<String, Object> application = new LinkedHashMap<>();
        application.put("metadata", new LinkedHashMap<>());
        application.put("datasets", Collections.emptyList());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("layers", Collections.emptyList());
        spec.put("widgets", Collections.emptyList());
        application.put("spec", spec);

        Map<String, Object> assembled = new LinkedHashMap<>();
        when(applicationAssembler.assemble("proj-1")).thenReturn(assembled);

        Map<String, Object> result = projectService.updateProject("proj-1", application);

        assertNotNull(result);
    }

    @Test
    void should_throw_when_updating_nonexistent_project() {
        when(projectRepository.findById("nonexistent")).thenReturn(null);

        Map<String, Object> application = new LinkedHashMap<>();
        assertThrows(RuntimeException.class,
            () -> projectService.updateProject("nonexistent", application));
    }

    @Test
    void should_delete_project() {
        Project project = new Project();
        project.setProjectId("proj-1");
        when(projectRepository.findById("proj-1")).thenReturn(project);

        projectService.deleteProject("proj-1");

        verify(projectRepository).deleteById("proj-1");
    }

    @Test
    void should_throw_when_deleting_nonexistent_project() {
        when(projectRepository.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> projectService.deleteProject("nonexistent"));
    }

    @Test
    void should_update_thumbnail() {
        Project project = new Project();
        project.setProjectId("proj-1");
        project.setThumbnail("old.png");
        when(projectRepository.findById("proj-1")).thenReturn(project);
        when(projectRepository.update(any(Project.class))).thenReturn(project);

        Project result = projectService.updateThumbnail("proj-1", "new.png");

        assertNotNull(result);
        verify(projectRepository).update(any(Project.class));
    }
}
