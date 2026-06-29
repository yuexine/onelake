-- ============================================================================
-- OneLake · analytics schema (V1)
-- 数据分析与可视化模块：数据集 + 大屏 + Notebook + 查询日志
-- 详见 docs/数据分析与可视化模块设计方案.md
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS analytics;

-- ============ 数据集：可视化/分析的统一数据入口 ============
CREATE TABLE IF NOT EXISTS analytics.dataset (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    name          VARCHAR(128) NOT NULL,
    source_type   VARCHAR(16) NOT NULL,          -- ASSET / SQL / API / NOTEBOOK
    asset_fqn     VARCHAR(512),                   -- 句柄：Iceberg 表 FQN（source_type=ASSET）
    select_sql    TEXT,                           -- Trino SQL（source_type=SQL/ASSET 生成）
    api_id        UUID,                           -- 句柄：dataservice api（source_type=API）
    field_schema  JSONB,                          -- [{name,type,classification}]
    classification VARCHAR(8) NOT NULL DEFAULT 'L1',  -- 密级快照（随资产继承）
    cache_ttl_sec INT NOT NULL DEFAULT 300,
    row_filter    TEXT,                           -- 行级过滤（注入 where）
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX IF NOT EXISTS idx_dataset_tenant ON analytics.dataset(tenant_id);

-- ============ 大屏定义（自研编排器） ============
CREATE TABLE IF NOT EXISTS analytics.dashboard (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    canvas        JSONB NOT NULL,                 -- {width,height,theme,background}
    spec          JSONB NOT NULL DEFAULT '[]',    -- 草稿态组件数组（见 §7.1 ScreenSpec）；发布时冻结为 publication.snapshot
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT/PUBLISHED/OFFLINE
    current_publication_id UUID,                  -- 指向当前线上 publication（冗余，加速读）
    version       INT NOT NULL DEFAULT 0,         -- 乐观锁，防并发编辑覆盖
    thumbnail     VARCHAR(512),                   -- 缩略图（MinIO）
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dashboard_tenant ON analytics.dashboard(tenant_id);

-- ============ 大屏发布快照 + 分享（每次发布插一行，带版本） ============
CREATE TABLE IF NOT EXISTS analytics.dashboard_publication (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dashboard_id  UUID NOT NULL REFERENCES analytics.dashboard(id),
    tenant_id     UUID NOT NULL,
    version       INT NOT NULL DEFAULT 1,
    snapshot      JSONB NOT NULL,                 -- 发布时冻结的 canvas+spec
    share_token   VARCHAR(64) UNIQUE,             -- 公开分享 token（仅 is_public=true 时非空）
    is_public     BOOLEAN NOT NULL DEFAULT FALSE,
    is_current    BOOLEAN NOT NULL DEFAULT TRUE,  -- 当前线上版（每个 dashboard 仅一条为 true）
    expire_at     TIMESTAMPTZ,
    published_by  UUID,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 每个 dashboard 同时只能有一条"当前线上版"
CREATE UNIQUE INDEX IF NOT EXISTS uq_dashboard_publication_current
    ON analytics.dashboard_publication(dashboard_id) WHERE is_current = TRUE;
CREATE INDEX IF NOT EXISTS idx_publication_dashboard
    ON analytics.dashboard_publication(dashboard_id, version DESC);

-- ============ Notebook 文档元数据（内容存 JupyterHub/MinIO） ============
CREATE TABLE IF NOT EXISTS analytics.notebook (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    name          VARCHAR(128) NOT NULL,
    kernel        VARCHAR(32) NOT NULL DEFAULT 'python3', -- python3 / pyspark
    storage_path  VARCHAR(512) NOT NULL,          -- 句柄：hub 上的 .ipynb 路径
    params_schema JSONB,                           -- 可参数化字段（papermill）
    template_id   UUID,                            -- 来源算法模板（引用 analytics.notebook_template.id；P4d 启用）
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notebook_tenant ON analytics.notebook(tenant_id);

-- ============ 算法模板库（平台预置 + 租户自定义；P4d 启用） ============
CREATE TABLE IF NOT EXISTS analytics.notebook_template (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID,                           -- NULL = 平台预置（所有租户可见）
    name          VARCHAR(128) NOT NULL,
    category      VARCHAR(64) NOT NULL,           -- CLUSTERING / REGRESSION / FORECAST / CORRELATION / EDA
    description   VARCHAR(512),
    storage_path  VARCHAR(512) NOT NULL,          -- 句柄：模板 .ipynb 在 MinIO 的路径
    params_schema JSONB,                          -- papermill 参数 schema
    kernel        VARCHAR(32) NOT NULL DEFAULT 'python3',
    icon          VARCHAR(64),                    -- 卡片图标 key
    sort_order    INT NOT NULL DEFAULT 0,
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notebook_template_lookup
    ON analytics.notebook_template(COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'), category, sort_order);

-- ============ Notebook 调度执行实例 ============
CREATE TABLE IF NOT EXISTS analytics.notebook_run (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notebook_id   UUID NOT NULL REFERENCES analytics.notebook(id),
    tenant_id     UUID NOT NULL,
    dagster_run_id VARCHAR(64),                   -- 句柄：Dagster run
    params        JSONB,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED
    output_html   VARCHAR(512),                   -- nbconvert 产出（MinIO）
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notebook_run_lookup ON analytics.notebook_run(tenant_id, status, created_at);

-- ============ 查询日志（慢查询、配额、血缘命中证据；见 §5.4） ============
CREATE TABLE IF NOT EXISTS analytics.query_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    dataset_id    UUID NOT NULL,
    dashboard_id  UUID,                           -- 来自哪个大屏的查询（可空）
    sql_md5       VARCHAR(32) NOT NULL,
    duration_ms   INT NOT NULL,
    rows          INT NOT NULL,
    cache_hit     BOOLEAN NOT NULL DEFAULT FALSE,
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_query_log_tenant_time ON analytics.query_log(tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_query_log_dataset_time ON analytics.query_log(dataset_id, created_at);
