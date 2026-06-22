package com.onelake.common.notification;

import com.onelake.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "通知中心", description = "为控制台通知铃铛和通知抽屉提供消息读取状态。")
public class NotificationController {

    private final NotificationService service;

    @Operation(
        summary = "查询通知列表",
        description = "用途：按当前用户返回最近通知和未读状态。前端对接：NotificationAPI.list，由 useNotifications 和 NotificationCenter 使用。"
    )
    @GetMapping
    public ApiResponse<List<NotificationDTO>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.list(limit));
    }

    @Operation(
        summary = "标记单条通知为已读",
        description = "用途：用户打开或点击某条通知后更新已读状态。前端对接：NotificationAPI.markRead，由 NotificationCenter 调用。"
    )
    @PostMapping("/{id}/read")
    public ApiResponse<NotificationDTO> markRead(@PathVariable UUID id) {
        return ApiResponse.ok(service.markRead(id));
    }

    @Operation(
        summary = "标记全部通知为已读",
        description = "用途：批量清除当前用户通知未读数。前端对接：NotificationAPI.markAllRead，由 NotificationCenter 的全部已读操作调用。"
    )
    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        service.markAllRead();
        return ApiResponse.ok(null);
    }
}
