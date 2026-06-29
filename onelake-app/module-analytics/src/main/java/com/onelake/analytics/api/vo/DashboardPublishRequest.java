package com.onelake.analytics.api.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

/**
 * 大屏发布请求。
 * isPublic=true 时签发 shareToken（公开通道）。
 * 公开分享对数据集 row_filter 有硬校验（见 §5.3）。
 */
@Data
public class DashboardPublishRequest {

    @NotNull
    private Boolean isPublic;

    private Instant expireAt;
}
