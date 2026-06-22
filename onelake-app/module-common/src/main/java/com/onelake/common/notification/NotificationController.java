package com.onelake.common.notification;

import com.onelake.common.api.ApiResponse;
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
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public ApiResponse<List<NotificationDTO>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(service.list(limit));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<NotificationDTO> markRead(@PathVariable UUID id) {
        return ApiResponse.ok(service.markRead(id));
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        service.markAllRead();
        return ApiResponse.ok(null);
    }
}
