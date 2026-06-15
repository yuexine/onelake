package com.onelake.common.exception;

import lombok.Getter;

/**
 * 业务异常基类（对应《技术初始化文档》§6.9）。
 * 使用：throw new BizException(40400, "数据源不存在");
 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
