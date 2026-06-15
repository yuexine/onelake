package com.onelake.integration.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.common.context.TenantContext;
import com.onelake.common.exception.BizException;
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
}
