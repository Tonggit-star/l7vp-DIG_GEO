package com.antv.l7vp.service;

import com.antv.l7vp.model.Project;
import com.antv.l7vp.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ApplicationAssembler applicationAssembler;

    @Autowired
    private FlinkJobService flinkJobService;

    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    public Map<String, Object> getProjectApplication(String projectId) {
        return applicationAssembler.assemble(projectId);
    }

    @Transactional
    public Map<String, Object> createProject(Map<String, Object> application) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Project project = new Project();
        project.setCreateTime(now);
        project.setUpdateTime(now);

        // 优先从顶层读取 metadata；若无，则从 applicationConfig.metadata 读取
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) application.get("metadata");
        if (metadata == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> appConfig = (Map<String, Object>) application.get("applicationConfig");
            if (appConfig != null) {
                metadata = (Map<String, Object>) appConfig.get("metadata");
            }
        }
        if (metadata != null) {
            if (metadata.containsKey("name")) {
                Object name = metadata.get("name");
                project.setProjectName(name != null ? name.toString() : "未命名项目");
            }
            if (metadata.containsKey("description")) {
                Object desc = metadata.get("description");
                project.setDescription(desc != null ? desc.toString() : null);
            }
            if (metadata.containsKey("thumbnail")) {
                Object thumb = metadata.get("thumbnail");
                project.setThumbnail(thumb != null ? thumb.toString() : null);
            }
            if (metadata.containsKey("assetPackageIds")) {
                Object apids = metadata.get("assetPackageIds");
                if (apids instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) apids;
                    project.setAssetPackageIds(list);
                }
            }
        }

        if (project.getProjectName() == null) {
            project.setProjectName("未命名项目");
        }

        projectRepository.insert(project);

        // 将 metadata 中补充 projectId
        if (metadata == null) {
            metadata = new java.util.LinkedHashMap<>();
            application.put("metadata", metadata);
        }
        metadata.put("projectId", project.getProjectId());

        // 调用 disassemble 写入 datasets/layers/widgets
        applicationAssembler.disassemble(project.getProjectId(), application);

        // flink.auto-start=true 时，为含 kafka 配置的流式数据集自动提交 Flink 作业（幂等）
        flinkJobService.startProjectStreamJobsIfAuto(project.getProjectId());

        return applicationAssembler.assemble(project.getProjectId());
    }

    @Transactional
    public Map<String, Object> updateProject(String projectId, Map<String, Object> application) {
        Project project = projectRepository.findById(projectId);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        log.info("[UPDATE_PROJECT] projectId={}, topKeys={}", projectId, application.keySet());
        // 检查 applicationConfig 中的 datasets
        @SuppressWarnings("unchecked")
        Map<String, Object> appConfig = (Map<String, Object>) application.get("applicationConfig");
        if (appConfig != null) {
            List<?> dsList = (List<?>) appConfig.get("datasets");
            if (dsList != null && !dsList.isEmpty()) {
                Map<String, Object> firstDs = (Map<String, Object>) dsList.get(0);
                List<?> data = (List<?>) firstDs.get("data");
                log.info("[UPDATE_PROJECT] firstDataset keys={}, hasData={}, dataSize={}",
                    firstDs.keySet(), data != null, data != null ? data.size() : 0);
            }
        }

        applicationAssembler.disassemble(projectId, application);

        // 同上：自动提交 kafka 流式作业
        flinkJobService.startProjectStreamJobsIfAuto(projectId);

        return applicationAssembler.assemble(projectId);
    }

    @Transactional
    public void deleteProject(String projectId) {
        Project project = projectRepository.findById(projectId);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }
        // 项目删除联动：先取消该项目所有流式数据集的 Flink 作业
        flinkJobService.stopProjectStreamJobs(projectId);
        projectRepository.deleteById(projectId);
    }

    public Project updateThumbnail(String projectId, String thumbnailUrl) {
        Project project = projectRepository.findById(projectId);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }
        project.setThumbnail(thumbnailUrl);
        project.setUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return projectRepository.update(project);
    }
}
