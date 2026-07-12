package com.onelake.orchestration.domain.enums;

/** 编排节点按执行语义划分的稳定分类。 */
public enum TaskCategory {
    /** 启动数据或脚本执行引擎的节点。 */
    EXEC,
    /** 改变图控制流但不直接产出数据资产的节点。 */
    CONTROL,
    /** 观察、等待或通知外部状态且不直接产出数据资产的节点。 */
    OBSERVE
}
