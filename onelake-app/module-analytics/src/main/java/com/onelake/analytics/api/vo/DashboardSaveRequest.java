package com.onelake.analytics.api.vo;

import lombok.Data;

import java.util.Map;

/**
 * 大屏保存请求（草稿态 spec 持久化）。
 * spec 是 ScreenSpec JSON 序列化字符串（前端渲染核心）。
 */
@Data
public class DashboardSaveRequest {

    private String name;
    private String description;
    private Map<String, Object> canvas;     // {width,height,theme,background}
    private Object spec;                    // WidgetNode[]
    private String thumbnail;
    private Integer expectedVersion;        // 乐观锁：客户端当前看到的 version
}
