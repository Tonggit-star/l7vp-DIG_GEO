package com.antv.l7vp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ThumbnailController {

    @Value("${l7vp.thumbnails.path:/opt/l7vp/frontend/thumbnails}")
    private String thumbnailsPath;

    @Value("${l7vp.thumbnails.url-prefix:/thumbnails}")
    private String thumbnailsUrlPrefix;

    /**
     * 上传项目缩略图
     */
    @PostMapping("/thumbnails/upload")
    public ResponseEntity<?> uploadThumbnail(@RequestParam("file") MultipartFile file) {
        try {
            // 检查文件是否为空
            if (file.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "文件不能为空");
                return ResponseEntity.badRequest().body(error);
            }

            // 检查文件类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                Map<String, Object> error = new HashMap<>();
                error.put("success", false);
                error.put("message", "只支持图片文件");
                return ResponseEntity.badRequest().body(error);
            }

            // 创建目录
            File uploadDir = new File(thumbnailsPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".png";
            String filename = UUID.randomUUID().toString() + extension;

            // 保存文件
            Path filePath = Paths.get(thumbnailsPath, filename);
            Files.write(filePath, file.getBytes());

            // 返回文件URL
            String fileUrl = thumbnailsUrlPrefix + "/" + filename;

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("url", fileUrl);
            result.put("filename", filename);

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            e.printStackTrace();
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "文件上传失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}