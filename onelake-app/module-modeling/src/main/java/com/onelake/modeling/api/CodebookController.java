package com.onelake.modeling.api;

import com.onelake.common.api.ApiResponse;
import com.onelake.modeling.dto.CodebookDecisionRequest;
import com.onelake.modeling.dto.CodebookDTO;
import com.onelake.modeling.dto.CodebookPublishRequest;
import com.onelake.modeling.dto.CodebookRequest;
import com.onelake.modeling.dto.CodebookVersionDTO;
import com.onelake.modeling.service.CodebookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/modeling/codebooks")
@RequiredArgsConstructor
@Tag(name = "标准字典", description = "字段治理字典集、版本和映射项管理接口。")
public class CodebookController {

    private final CodebookService service;

    @Operation(
        summary = "查询标准字典",
        description = "用途：按关键字、状态和业务域查询字段治理字典集。前端对接：DWD 治理设计器的字典匹配配置下拉。"
    )
    @GetMapping
    public ApiResponse<List<CodebookDTO>> list(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String domain
    ) {
        return ApiResponse.ok(service.list(keyword, status, domain));
    }

    @Operation(
        summary = "创建标准字典",
        description = "用途：创建可发布版本的字段治理字典草稿。前端对接：后续字典管理页的新建字典动作。"
    )
    @PostMapping
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<CodebookDTO> create(@RequestBody CodebookRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @Operation(
        summary = "获取标准字典详情",
        description = "用途：读取字典元信息、当前草稿映射项和最新发布版本。前端对接：DWD 治理设计器字典选择后的详情回显。"
    )
    @GetMapping("/{id}")
    public ApiResponse<CodebookDTO> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @Operation(
        summary = "更新标准字典",
        description = "用途：编辑字典草稿、映射项和未命中策略。前端对接：后续字典管理页的编辑动作。"
    )
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<CodebookDTO> update(@PathVariable UUID id, @RequestBody CodebookRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @Operation(
        summary = "发布标准字典版本",
        description = "用途：将当前字典映射项发布为固定版本快照。前端对接：后续字典管理页的发布动作，DWD 治理设计器只消费已发布版本。"
    )
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<CodebookDTO> publish(@PathVariable UUID id, @RequestBody(required = false) CodebookPublishRequest request) {
        return ApiResponse.ok(service.publish(id, request));
    }

    @Operation(
        summary = "废弃标准字典",
        description = "用途：将不再推荐使用的字典集标记为废弃。前端对接：后续字典管理页的治理动作。"
    )
    @PostMapping("/{id}/deprecate")
    @PreAuthorize("hasAnyRole('DE','ADMIN')")
    public ApiResponse<CodebookDTO> deprecate(
        @PathVariable UUID id,
        @RequestBody(required = false) CodebookDecisionRequest request
    ) {
        return ApiResponse.ok(service.deprecate(id, request == null ? null : request.comment()));
    }

    @Operation(
        summary = "查询标准字典版本",
        description = "用途：返回字典历次发布版本快照。前端对接：后续字典管理页的版本历史区。"
    )
    @GetMapping("/{id}/versions")
    public ApiResponse<List<CodebookVersionDTO>> versions(@PathVariable UUID id) {
        return ApiResponse.ok(service.versions(id));
    }
}
