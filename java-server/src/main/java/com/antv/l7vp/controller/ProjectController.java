package com.antv.l7vp.controller;

import com.antv.l7vp.model.Project;
import com.antv.l7vp.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @GetMapping("/projects")
    public List<Project> getAllProjects() {
        return projectService.listProjects();
    }

    @PostMapping("/projects")
    public ResponseEntity<?> createProject(@RequestBody Map<String, Object> application) {
        try {
            Map<String, Object> result = projectService.createProject(application);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            // 递归获取根因
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            error.put("rootCause", cause.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<Map<String, Object>> getProjectById(@PathVariable String id) {
        try {
            Map<String, Object> application = projectService.getProjectApplication(id);
            return ResponseEntity.ok(application);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/projects/{id}")
    public ResponseEntity<Map<String, Object>> updateProject(@PathVariable String id, @RequestBody Map<String, Object> application) {
        try {
            Map<String, Object> updated = projectService.updateProject(id, application);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            error.put("rootCause", cause.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(error);
        }
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable String id) {
        try {
            projectService.deleteProject(id);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/projects/{id}/thumbnail")
    public ResponseEntity<?> updateProjectThumbnail(
            @PathVariable String id,
            @RequestBody Map<String, String> requestBody) {
        try {
            String thumbnailUrl = requestBody.get("thumbnailUrl");
            Project updatedProject = projectService.updateThumbnail(id, thumbnailUrl);
            return ResponseEntity.ok(updatedProject);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("{\"status\":\"ok\",\"message\":\"L7VP后端服务运行正常\"}");
    }
}
