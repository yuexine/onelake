-- M4-B/G：任务类型扩展分类 + 算子引用 + 算子版本锁定
-- 关联 docs/数据开发与编排模块V2升级计划.md §4.2（B）、§4.7（G）

ALTER TABLE orchestration.pipeline_task
  ADD COLUMN IF NOT EXISTS category         VARCHAR(16) NOT NULL DEFAULT 'EXEC', -- EXEC|CONTROL|OBSERVE
  ADD COLUMN IF NOT EXISTS operator_ref     VARCHAR(128),                        -- G2 拖入的算子标识
  ADD COLUMN IF NOT EXISTS operator_version VARCHAR(32);                         -- G3 锁定版本

-- 存量节点按枚举的兼容映射回填；QUALITY_GATE 仍保留既有 Spark 执行路径。
UPDATE orchestration.pipeline_task
SET category = CASE task_type
  WHEN 'SYNC_REF' THEN 'OBSERVE'
  ELSE 'EXEC'
END;

-- task_type 为 varchar 且无 DB CHECK 约束，新增枚举值仅需应用层扩展。
CREATE INDEX IF NOT EXISTS idx_task_operator ON orchestration.pipeline_task (operator_ref)
  WHERE operator_ref IS NOT NULL;

ALTER TABLE orchestration.task_run
  ADD COLUMN IF NOT EXISTS operator_version VARCHAR(32);  -- G3 run 复现锁定
