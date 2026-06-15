package com.onelake.common.exception;

/**
 * 数据面调用异常（对应《技术初始化文档》§6.9 错误码 50010）。
 */
public class DataplaneException extends BizException {
    public DataplaneException(String msg) {
        super(50010, msg);
    }

    public DataplaneException(String msg, Throwable cause) {
        super(50010, msg, cause);
    }
}
