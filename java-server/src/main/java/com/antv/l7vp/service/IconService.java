package com.antv.l7vp.service;

import com.antv.l7vp.dto.IconLookupResult;
import com.antv.l7vp.model.IconCategory;
import com.antv.l7vp.model.IconItem;
import com.antv.l7vp.repository.IconCategoryRepository;
import com.antv.l7vp.repository.IconRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class IconService {

    @Autowired
    private IconCategoryRepository iconCategoryRepository;

    @Autowired
    private IconRepository iconRepository;

    @Value("${l7vp.icons.path:/opt/l7vp/icons}")
    private String iconsPath;

    @Value("${l7vp.icons.url-prefix:/icons}")
    private String iconsUrlPrefix;

    // ==================== 分类管理 ====================

    public List<IconCategory> listCategories() {
        return iconCategoryRepository.findAll();
    }

    public IconCategory createCategory(String name) {
        IconCategory category = new IconCategory();
        category.setCategoryName(name);
        category.setSortOrder(iconCategoryRepository.findAll().size());
        category.setCreateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return iconCategoryRepository.insert(category);
    }

    public IconCategory updateCategory(String id, String name) {
        IconCategory category = iconCategoryRepository.findById(id);
        if (category == null) {
            throw new RuntimeException("分类不存在");
        }
        category.setCategoryName(name);
        return iconCategoryRepository.update(category);
    }

    public void deleteCategory(String id) {
        IconCategory category = iconCategoryRepository.findById(id);
        if (category == null) {
            throw new RuntimeException("分类不存在");
        }
        // 级联删除图标文件和DB记录
        List<IconItem> icons = iconRepository.findByCategoryId(id);
        for (IconItem icon : icons) {
            deleteIconFile(icon);
        }
        iconRepository.deleteByCategoryId(id);
        iconCategoryRepository.deleteById(id);
    }

    public void reorderCategories(List<Map<String, Object>> orders) {
        iconCategoryRepository.batchUpdateOrder(orders);
    }

    // ==================== 图标管理 ====================

    public List<IconItem> listIcons(String categoryId) {
        return iconRepository.findByCategoryId(categoryId);
    }

    public List<IconItem> listAllIcons() {
        List<IconCategory> categories = iconCategoryRepository.findAll();
        List<IconItem> allIcons = new ArrayList<>();
        for (IconCategory cat : categories) {
            allIcons.addAll(iconRepository.findByCategoryId(cat.getCategoryId()));
        }
        return allIcons;
    }

    public List<IconItem> uploadIcons(String categoryId, MultipartFile[] files) {
        IconCategory category = iconCategoryRepository.findById(categoryId);
        if (category == null) {
            throw new RuntimeException("分类不存在");
        }

        List<IconItem> uploaded = new ArrayList<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 批量上传前一次性计算默认值，循环中递增 codeName 避免重复
        String defaultLibraryCode = getDefaultLibraryCode(categoryId);
        int nextCodeName = Integer.parseInt(getDefaultCodeName(categoryId, defaultLibraryCode));
        int startSortOrder = iconRepository.findByCategoryId(categoryId).size();

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            // 生成UUID文件名
            String originalName = file.getOriginalFilename();
            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf('.'));
            }
            String uuidName = UUID.randomUUID().toString() + ext;

            // 确定文件类型
            String fileType = "";
            if (ext.length() > 1) {
                fileType = ext.substring(1).toLowerCase();
            }

            // 写入文件
            try {
                Path categoryDir = Paths.get(iconsPath, categoryId).toAbsolutePath().normalize();
                Files.createDirectories(categoryDir);
                Path filePath = categoryDir.resolve(uuidName);
                file.transferTo(filePath.toFile());
            } catch (IOException e) {
                String fullPath = "";
                try {
                    fullPath = Paths.get(iconsPath, categoryId).toAbsolutePath().toString();
                } catch (Exception ignored) {}
                throw new RuntimeException("图标文件写入失败: " + originalName + " (path=" + fullPath + "): " + e.getMessage(), e);
            }

            // 构建 URL
            String url = iconsUrlPrefix + "/" + categoryId + "/" + uuidName;

            // 预计算默认值，循环内递增，冲突时从 DB 重查
            String codeName = String.valueOf(nextCodeName++);

            IconItem icon = new IconItem();
            icon.setCategoryId(categoryId);
            icon.setLibraryCode(defaultLibraryCode);
            icon.setFileName(uuidName);
            icon.setOriginalName(originalName);
            icon.setFileType(fileType);
            icon.setFileSize(file.getSize());
            icon.setUrl(url);
            icon.setCreateTime(now);

            // 尝试插入，唯一约束冲突时从 DB 重查最新 codeName 并重试
            while (true) {
                icon.setCodeName(codeName);
                icon.setSortOrder(startSortOrder++);
                try {
                    iconRepository.insert(icon);
                    break;
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // getDefaultCodeName 返回的就是 max+1，直接使用
                    codeName = getDefaultCodeName(categoryId, defaultLibraryCode);
                }
            }
            uploaded.add(icon);
        }

        return uploaded;
    }

    public IconItem updateIconMeta(String iconId, String libraryCode, String codeName) {
        IconItem icon = iconRepository.findById(iconId);
        if (icon == null) {
            throw new RuntimeException("图标不存在");
        }
        // 唯一性校验: 同分类下 (libraryCode, codeName) 不能重复
        if (libraryCode != null && codeName != null) {
            IconItem existing = iconRepository.findByCode(libraryCode, codeName);
            if (existing != null && !existing.getIconId().equals(iconId)) {
                throw new com.antv.l7vp.exception.DuplicateCodeException(libraryCode, codeName);
            }
            icon.setLibraryCode(libraryCode);
            icon.setCodeName(codeName);
        }
        iconRepository.update(icon);
        return icon;
    }

    public void deleteIcon(String iconId) {
        IconItem icon = iconRepository.findById(iconId);
        if (icon == null) {
            throw new RuntimeException("图标不存在");
        }
        deleteIconFile(icon);
        iconRepository.deleteById(iconId);
    }

    public void reorderIcons(List<Map<String, Object>> orders) {
        iconRepository.batchUpdateOrder(orders);
    }

    public IconItem moveIcon(String iconId, String targetCategoryId) {
        IconItem icon = iconRepository.findById(iconId);
        if (icon == null) {
            throw new RuntimeException("图标不存在");
        }
        IconCategory targetCategory = iconCategoryRepository.findById(targetCategoryId);
        if (targetCategory == null) {
            throw new RuntimeException("目标分类不存在");
        }

        // 移动文件
        try {
            Path oldPath = Paths.get(iconsPath, icon.getCategoryId(), icon.getFileName());
            Path newCategoryDir = Paths.get(iconsPath, targetCategoryId);
            Files.createDirectories(newCategoryDir);
            Path newPath = newCategoryDir.resolve(icon.getFileName());
            Files.move(oldPath, newPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("移动图标文件失败", e);
        }

        // 更新DB
        String oldCategoryId = icon.getCategoryId();
        icon.setCategoryId(targetCategoryId);
        icon.setUrl(iconsUrlPrefix + "/" + targetCategoryId + "/" + icon.getFileName());
        iconRepository.update(icon);

        return icon;
    }

    public IconLookupResult lookupByCode(String libraryCode, String codeName) {
        IconItem icon = iconRepository.findByCode(libraryCode, codeName);
        IconLookupResult result = new IconLookupResult();
        result.setUrl(icon != null ? icon.getUrl() : null);
        return result;
    }

    /**
     * 计算某分类下出现次数最多的 library_code（用于上传默认值）
     */
    private String getDefaultLibraryCode(String categoryId) {
        List<IconItem> icons = iconRepository.findByCategoryId(categoryId);
        if (icons.isEmpty()) {
            return "1";
        }
        // 统计各个 library_code 的出现次数，排除空值
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (IconItem icon : icons) {
            String code = icon.getLibraryCode();
            if (code != null && !code.isEmpty()) {
                counts.merge(code, 1L, Long::sum);
            }
        }
        if (counts.isEmpty()) {
            return "1";
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("1");
    }

    /**
     * 计算同一 library_code 内的最大数字 code_name + 1（用于上传默认值）
     */
    private String getDefaultCodeName(String categoryId, String libraryCode) {
        List<IconItem> icons = iconRepository.findByCategoryId(categoryId);
        int maxNum = 0;
        for (IconItem icon : icons) {
            if (libraryCode != null && libraryCode.equals(icon.getLibraryCode())) {
                String codeName = icon.getCodeName();
                if (codeName != null && !codeName.isEmpty()) {
                    try {
                        int num = Integer.parseInt(codeName);
                        if (num > maxNum) {
                            maxNum = num;
                        }
                    } catch (NumberFormatException ignored) {
                        // 非数字的 code_name 忽略
                    }
                }
            }
        }
        return String.valueOf(maxNum + 1);
    }

    private void deleteIconFile(IconItem icon) {
        try {
            Path filePath = Paths.get(iconsPath, icon.getCategoryId(), icon.getFileName());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            // 文件删除失败不抛异常, 仅记录
        }
    }
}
