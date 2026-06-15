package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
import com.onelake.integration.client.FileCollectorClient;
import com.onelake.integration.domain.entity.FileSource;
import com.onelake.integration.repository.FileSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** 文件采集源管理（对应前端 FileCollect 页面）。 */
@RestController
@RequestMapping("/api/v1/integration/file-sources")
@RequiredArgsConstructor
public class FileSourceController {

    private final FileSourceRepository repo;
    private final FileCollectorClient fileClient;

    @PostMapping
    @PreAuthorize("hasRole('DE')")
    public ApiResponse<FileSource> create(@RequestBody Map<String, String> body) {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        FileSource fs = new FileSource();
        fs.setTenantId(tid);
        fs.setName(body.get("name"));
        fs.setSourceType(body.getOrDefault("sourceType", "SFTP"));
        fs.setEndpoint(body.get("endpoint"));
        fs.setBasePath(body.getOrDefault("basePath", "/"));
        fs.setWatchMode(body.getOrDefault("watchMode", "event"));
        fs.setEnabled(true);
        fs.setCreatedAt(Instant.now());
        repo.save(fs);
        return ApiResponse.ok(fs);
    }

    @GetMapping
    public ApiResponse<List<FileSource>> list() {
        UUID tid = TenantContext.getTenantId();
        if (tid == null) throw new BizException(40100, "租户上下文缺失");
        return ApiResponse.ok(repo.findByTenantId(tid));
    }

    /** 列出文件源下的文件（含大小、ETag、去重标记）。 */
    @GetMapping("/{id}/files")
    public ApiResponse<List<Map<String, Object>>> listFiles(@PathVariable UUID id) {
        FileSource fs = repo.findById(id)
            .orElseThrow(() -> new BizException(40400, "文件源不存在"));
        // 从 endpoint 提取 bucket 名（MinIO 模式：endpoint 最后一段或 basePath 第一段）
        String bucket = extractBucket(fs);
        String prefix = fs.getBasePath() == null ? "" : fs.getBasePath();
        return ApiResponse.ok(fileClient.listFiles(bucket, prefix));
    }

    private String extractBucket(FileSource fs) {
        // 简单启发：basePath 的第一段是 bucket，如 /onelake/inbound/orders/ → onelake
        String base = fs.getBasePath();
        if (base == null || base.isBlank()) return "onelake";
        String trimmed = base.replaceAll("^/+", "").replaceAll("/+$", "");
        String[] parts = trimmed.split("/");
        return parts.length > 0 ? parts[0] : "onelake";
    }
}
