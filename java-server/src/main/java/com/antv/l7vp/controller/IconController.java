package com.antv.l7vp.controller;

import com.antv.l7vp.dto.IconLookupResult;
import com.antv.l7vp.exception.DuplicateCodeException;
import com.antv.l7vp.model.IconCategory;
import com.antv.l7vp.model.IconItem;
import com.antv.l7vp.service.IconService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class IconController {

    @Autowired
    private IconService iconService;

    // ==================== 兼容接口 ====================

    /**
     * 获取全部图标（兼容旧接口，无参数时返回全部）
     */
    @GetMapping("/icons")
    public ResponseEntity<?> getIcons(@RequestParam(required = false) String categoryId) {
        try {
            if (categoryId != null) {
                List<IconItem> icons = iconService.listIcons(categoryId);
                return ResponseEntity.ok(icons);
            }
            // 兼容旧格式: 返回分类+图标列表
            List<IconCategory> categories = iconService.listCategories();
            List<Map<String, Object>> result = new ArrayList<>();
            for (IconCategory cat : categories) {
                Map<String, Object> categoryMap = new LinkedHashMap<>();
                categoryMap.put("type", cat.getCategoryName());
                categoryMap.put("categoryId", cat.getCategoryId());
                categoryMap.put("sortOrder", cat.getSortOrder());

                List<IconItem> icons = iconService.listIcons(cat.getCategoryId());
                List<Map<String, Object>> iconList = new ArrayList<>();
                for (IconItem icon : icons) {
                    Map<String, Object> iconMap = new LinkedHashMap<>();
                    iconMap.put("id", icon.getIconId());
                    iconMap.put("name", icon.getOriginalName());
                    iconMap.put("url", icon.getUrl());
                    iconMap.put("libraryCode", icon.getLibraryCode());
                    iconMap.put("codeName", icon.getCodeName());
                    iconList.add(iconMap);
                }
                categoryMap.put("icons", iconList);
                result.add(categoryMap);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    // ==================== 分类管理 ====================

    @GetMapping("/icons/categories")
    public List<IconCategory> getCategories() {
        return iconService.listCategories();
    }

    @PostMapping("/icons/categories")
    public IconCategory createCategory(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isEmpty()) {
            throw new RuntimeException("分类名称不能为空");
        }
        return iconService.createCategory(name);
    }

    @PutMapping("/icons/categories/{id}")
    public IconCategory updateCategory(@PathVariable String id, @RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isEmpty()) {
            throw new RuntimeException("分类名称不能为空");
        }
        return iconService.updateCategory(id, name);
    }

    @DeleteMapping("/icons/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        try {
            iconService.deleteCategory(id);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/icons/categories/reorder")
    public ResponseEntity<Void> reorderCategories(@RequestBody List<Map<String, Object>> orders) {
        iconService.reorderCategories(orders);
        return ResponseEntity.ok().build();
    }

    // ==================== 图标 CRUD ====================

    @PostMapping("/icons/upload")
    public ResponseEntity<?> uploadIcons(
            @RequestParam("categoryId") String categoryId,
            @RequestParam("files") MultipartFile[] files) {
        try {
            List<IconItem> icons = iconService.uploadIcons(categoryId, files);
            return ResponseEntity.ok(icons);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            return ResponseEntity.status(500).body(error);
        }
    }

    @PutMapping("/icons/{id}")
    public ResponseEntity<?> updateIcon(@PathVariable String id, @RequestBody Map<String, String> body) {
        String libraryCode = body.get("libraryCode");
        String codeName = body.get("codeName");
        try {
            IconItem icon = iconService.updateIconMeta(id, libraryCode, codeName);
            return ResponseEntity.ok(icon);
        } catch (DuplicateCodeException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("libraryCode", e.getLibraryCode());
            error.put("codeName", e.getCodeName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }
    }

    @DeleteMapping("/icons/{id}")
    public ResponseEntity<Void> deleteIcon(@PathVariable String id) {
        try {
            iconService.deleteIcon(id);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/icons/reorder")
    public ResponseEntity<Void> reorderIcons(@RequestBody List<Map<String, Object>> orders) {
        iconService.reorderIcons(orders);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/icons/{id}/move")
    public ResponseEntity<IconItem> moveIcon(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            String targetCategoryId = body.get("targetCategoryId");
            IconItem icon = iconService.moveIcon(id, targetCategoryId);
            return ResponseEntity.ok(icon);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ==================== 图标查找 ====================

    @GetMapping("/icons/lookup")
    public ResponseEntity<IconLookupResult> lookupIcon(
            @RequestParam String lib,
            @RequestParam String code) {
        IconLookupResult result = iconService.lookupByCode(lib, code);
        if (result.getUrl() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }
}
