-- RunStatus 枚举统一（C8 — SUCCESS → SUCCEEDED）
-- 详见 docs/RUNSTATUS_ENUM_AUDIT.md §2.3
-- 只改字符串值，不破坏外键或约束

-- orchestration.job_run
UPDATE orchestration.job_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';

-- modeling.model_run
UPDATE modeling.model_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';

-- integration.sync_run（种子数据中可能存在 SUCCESS）
UPDATE integration.sync_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';

-- quality.run_result（若存在）
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'quality' AND table_name = 'run_result' AND column_name = 'status'
    ) THEN
        UPDATE quality.run_result SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
    END IF;
END $$;

-- 编排/建模 Java 枚举已同步更新（DagStatus.SUCCESS → SUCCEEDED）
-- 前端 types/index.ts 删除 'SUCCESS' 别名，只保留 'SUCCEEDED'
-- Outbox 事件 payload 自 P0 起即用 'SUCCEEDED'，无需迁移
