# 数据分析与可视化模块 · 联调验收清单

> 适用版本：设计方案 v1.1（P1-P4d + P5-A/B/C 全量交付）
> 目标：本机 / CI 环境下，按本文档逐项验证两条主链路 + 启动就绪。

---

## 〇、前置依赖（一次性准备）

| 依赖 | 版本 | 用途 | 校验命令 |
|------|------|------|----------|
| Docker Desktop | ≥ 4.20 | 数据面容器 + Testcontainers | `docker version` |
| docker-compose | v2 | 启动多组件栈 | `docker compose version` |
| Java | 17 | 控制面 | `java -version` |
| Maven | ≥ 3.8 | 构建 | `mvn -v` |
| Node.js | ≥ 18 | 前端 | `node -v` |
| pnpm | ≥ 8 | 前端依赖管理 | `pnpm -v` |
| OpenAI API Key（可选） | — | NL2SQL 智能建数据集 | `echo $OPENAI_API_KEY` |

---

## 一、构建与单元测试（无 Docker 也能跑）

```bash
cd onelake-app

# 1) 后端：10 模块编译 + module-analytics 单元测试（41 个测试全绿）
mvn install -DskipTests -Djacoco.skip=true
mvn -pl module-analytics test -Djacoco.skip=true

# 2) 前端：依赖安装 + TypeScript 检查 + 生产构建
cd web-console
pnpm install
pnpm exec tsc --noEmit   # 必须 0 错误
pnpm build                # 必须 ✓ built 成功（chunk size 警告可忽略）
cd ..
```

**预期**：
- ✅ Maven 10 模块全 SUCCESS
- ✅ module-analytics 测试 `Tests run: 41, Failures: 0, Errors: 0`
- ✅ tsc 0 错误
- ✅ vite build 成功，dist/assets/ 含 7+ 个 analytics 相关 chunk（DashboardList / ScreenDesigner / DatasetList / DatasetDetail / Library / Notebooks / ScreenShare）

---

## 二、数据库迁移（CI 跑）

```bash
# 在 Docker 环境下验证 Flyway 迁移全部 schema
mvn -pl bootstrap test \
    -Dtest=BootstrapFlywaySmokeTest \
    -Dtest.docker.enabled=true \
    -Djacoco.skip=true
```

**预期 5 个测试全绿**：
- `allSchemasMigrated` — 9 个 schema 全部建成
- `analyticsCoreTablesExist` — 7 个 analytics 表（dataset / dashboard / dashboard_publication / notebook / notebook_template / notebook_run / query_log）
- `dashboardHasOptimisticLockColumn` — dashboard.version / current_publication_id / status 列存在
- `datasetHasUniqueTenantName` — analytics.dataset 有 tenant+name unique 索引
- `publicationIsCurrentUniqueIndex` — dashboard_publication.is_current 的 partial unique index 存在

> 无 Docker 时跳过（@EnabledIfSystemProperty 自动跳过），不阻塞其他测试。

---

## 三、数据面 + 控制面启动

### 3.1 启动数据面容器

```bash
cd onelake-app
make up                 # postgres/redis/minio/hive-metastore/trino/keycloak/openmetadata/postgrest/apisix/dagster/superset/jupyterhub 等 Compose 服务；Airbyte 单独 make airbyte-up
make seed               # Keycloak realm "onelake" + 角色（DE/ADMIN/CONSUMER/SEC/OPS）+ MinIO bucket
make migrate            # Flyway 应用全部 9 schema 迁移
```

**端口清单**：
| 服务 | 端口 | 健康检查 |
|------|------|----------|
| PostgreSQL | 5432 | `psql -h localhost -U onelake -d onelake -c '\dn'`（应见 9 schema） |
| Redis | 6379 | `redis-cli ping` |
| Keycloak | 8081 | `curl http://localhost:8081/realms/onelake/.well-known/openid-configuration` |
| Trino | 18080 | `curl http://localhost:18080/v1/info` |
| Superset | 8088 | `curl http://localhost:8088/health` |
| JupyterHub | 18000 | `curl http://localhost:18000/` |
| Dagster | 3000 | `curl http://localhost:3000/graphql -d '{"query":"{repositories{locations{name}}}"}'` |

### 3.2 启动控制面（Spring Boot :8080，APISIX :9080 代理）

```bash
make backend           # mvn -pl bootstrap spring-boot:run
# 或后台启动：mvn -pl bootstrap spring-boot:run > /tmp/backend.log 2>&1 &
```

**健康检查**：
```bash
curl http://localhost:8080/actuator/health    # 应返回 {"status":"UP"}
curl http://localhost:8080/v3/api-docs         # OpenAPI 文档（含 analytics/* 路径）
```

### 3.3 启动前端

```bash
cd web-console
pnpm dev               # vite dev server，端口 5173
```

**健康检查**：
```bash
curl -o /dev/null -w "%{http_code}\n" http://localhost:5173/      # 200
curl -o /dev/null -w "%{http_code}\n" http://localhost:5173/analytics/dashboards  # 200
curl -o /dev/null -w "%{http_code}\n" http://localhost:5173/analytics/datasets    # 200
curl -o /dev/null -w "%{http_code}\n" http://localhost:5173/share/screen/test     # 200
```

打开浏览器 http://localhost:5173 ，Keycloak SSO 跳转登录 → 用 `admin/admin` 登录（由 `make seed` 创建）→ 返回前端首页。左侧 SideNav 应见 11 段菜单（含"数据分析"）。

---

## 四、主链路一：建数据集 → 拖折线图 → 发布 → 分享

**前置**：已有 Iceberg 表（由 dbt 工程或集成任务产出）。如果没有，可用 Trino 系统表模拟：

```sql
-- 在 Trino CLI 中创建测试表
CREATE TABLE iceberg.dwd.demo_dashboard_src (
  stat_date date,
  region varchar,
  gmv decimal(18,2)
);
INSERT INTO iceberg.dwd.demo_dashboard_src VALUES
  (date '2024-01-01', '华东', 12000),
  (date '2024-01-02', '华东', 13500),
  (date '2024-01-01', '华南', 9800),
  (date '2024-01-02', '华南', 10200);
```

### 4.1 创建数据集

1. 进入 `/analytics/datasets` → 点"新建数据集"
2. 表单：
   - 名称：`demo_gmv_by_region`
   - 来源类型：ASSET
   - 资产 FQN：`iceberg.dwd.demo_dashboard_src`
   - 密级：L2 内部
3. 保存 → 列表出现新数据集
4. 点击列表行"浏览" → 进入详情页 → 点"试查询"
5. **预期**：返回 4 行数据，耗时 < 1s（首次缓存未命中）

### 4.2（可选）用 NL2SQL 智能建数据集

> 需要环境变量 `OPENAI_API_KEY` 已配置。

1. 进入 `/analytics/datasets` → 点"新建数据集"
2. 来源类型：SQL
3. 在"AI 生成 SQL"卡片输入：
   - 资产 FQN：`iceberg.dwd.demo_dashboard_src`
   - 问题：`华东最近 30 天 GMV 汇总`
4. 点"生成 SQL" → 应得到类似 `SELECT stat_date, gmv FROM "iceberg.dwd.demo_dashboard_src" WHERE ... LIMIT 100`
5. 点"应用到 SQL 编辑器" → 提交保存

### 4.3 创建大屏 + 拖折线图

1. 进入 `/analytics/dashboards` → "新建大屏" → 输入名称"GMV 监控"
2. 进入设计器 → 左侧 Palette 拖"折线图" 到画布
3. 选中折线图 → 右侧 Inspector "数据" Tab：
   - 数据集：选 `demo_gmv_by_region`
   - 维度：`stat_date`
   - 指标：`gmv` (sum)
4. 折线图应实时渲染（按 stat_date 两条线：华东 / 华南）

### 4.4 钻取联动验证（P5-B）

1. 拖第二个折线图到画布
2. 第一个折线图 → Inspector "交互" Tab → 添加事件：filter → 写入变量 `region`
3. 第二个折线图 → Inspector "数据" Tab → 添加 filter：field=region，op==，fromVar=`region`
4. 点击第一个折线图的某一点（如 stat_date=2024-01-01, region=华东）
5. **预期**：第二个折线图自动刷新只显示 `region='华东'` 的数据
6. 底部"全局筛选器"条应出现 region 变量 + 当前值"华东"

### 4.5 保存 + 发布 + 分享

1. 顶部"保存草稿" → version +1
2. 顶部"发布" → 弹窗：
   - 开启公开分享：❌（先选否）
   - 发布 → 应得到 v1 内部版本
3. 再次发布 → 开启公开分享：✅
   - **预期**：返回成功 + 复制按钮（分享链接形如 `/share/screen/<token>`）
4. 浏览器隐身窗口打开分享链接
5. **预期**：渲染只读大屏（无需登录），无 SideNav / Header

### 4.6 row_filter 安全约束验证

1. 新建一个数据集 `demo_with_filter`：
   - 来源：ASSET，FQN：`iceberg.dwd.demo_dashboard_src`
   - 行级过滤：`region = '华东'`
2. 在新大屏中拖一个折线图绑定该数据集
3. 发布 → 开启公开分享
4. **预期**：被拒绝，错误消息"公开分享不允许数据集带 row_filter"

---

## 五、主链路二：新建 Notebook → 交互执行 → 调度落表

> 这条链路依赖 JupyterHub 服务可用。

### 5.1 JupyterHub 单点登录验证

1. 进入 `/analytics/notebooks` → "新建 Notebook" → 名称"RFM 探索" → kernel=python3
2. 列表出现新 Notebook → 点"打开 JupyterLab"
3. **预期**：新窗口打开 JupyterHub，自动 SSO（Keycloak JWT 已透传）

### 5.2 交互执行 + 资产直连

在 JupyterLab 中新建 cell：

```python
import sys
sys.path.insert(0, '/opt/onelake/sdk')
from onelake import dataset

df = dataset("iceberg.dwd.demo_dashboard_src").to_pandas()
print(df.shape)
df.head()
```

**预期**：输出 DataFrame，4 行 × 3 列（来自 demo_dashboard_src 表）

### 5.3 调度执行（papermill via Dagster）

1. 在 Notebook 中添加 papermill 参数 cell（标记 `parameters`）：
   ```python
   region_filter = "华东"  # type: ignore
   ```
2. 保存 Notebook
3. 在前端 `/analytics/notebooks` → 点击 Notebook → "调度运行"（POST `/api/v1/analytics/notebooks/{id}/runs`）
4. 在 Dagster UI（http://localhost:3000）观察 `onelake_notebook_run` job
5. **预期**：状态 PENDING → RUNNING → SUCCEEDED，耗时 < 30s
6. 控制面日志：30 秒内 `NotebookRunSyncScheduler` 轮询到 Dagster 完成状态，更新 `notebook_run.status=SUCCEEDED`
7. `analytics.notebook_run.output_html` 字段指向 nbconvert 生成的 HTML 报告路径

### 5.4 产出注册（P4d 写 SDK）

在 JupyterLab 中：

```python
from onelake import dataset, publish
import pandas as pd

# 简单聚合后产出新表
df = dataset("iceberg.dwd.demo_dashboard_src").to_pandas()
summary = df.groupby('region')['gmv'].sum().reset_index()
summary.columns = ['region', 'total_gmv']

publish(summary, "demo_gmv_by_region_summary", classification="L1")
```

**预期**：
- Spark 写新表 `iceberg.dwd.demo_gmv_by_region_summary`
- 控制面日志：`registered notebook artifact: iceberg.dwd.demo_gmv_by_region_summary (tenant=...)`
- `analytics.dataset` 表新增一行（source_type=NOTEBOOK）
- `analytics.query_log` 出现对应查询记录
- 在前端 `/analytics/datasets` 列表中能看到新数据集

---

## 六、Outbox 事件追踪

验证事件链路（每步都应触发对应 Outbox 事件）：

```sql
-- 在 psql 中查询最近事件
SELECT event_type, aggregate_id, status, occurred_at
FROM common.outbox_event
WHERE event_type LIKE 'analytics.%'
ORDER BY occurred_at DESC LIMIT 20;
```

**预期事件类型**：
- `analytics.dashboard.published` — 发布大屏时
- `analytics.notebook.run-submitted` — 提交 Notebook 调度时
- `analytics.notebook.run-status-changed` — NotebookRunSyncScheduler 轮询到状态变更时
- `analytics.notebook.timeout` — 超 30min 仍在 RUNNING 兜底置 FAILED 时
- `analytics.notebook.artifact-published` — onelake.publish() 注册资产时
- `analytics.query.slow` — 查询耗时 > 5s 时

---

## 七、性能与可观测性

### 7.1 单一飞行（single-flight）缓存验证

1. 同一数据集 `demo_gmv_by_region` 在大屏上绑定 3 个组件
2. 同时刷新大屏
3. **预期**：3 个组件共享 1 次 Trino 查询（Redis 缓存命中）
4. 查 `analytics.query_log`：3 条记录，其中 2 条 `cache_hit=true`

### 7.2 慢查询告警

构造慢查询（如 SELECT 一个超大表的 100 万行）：
1. 配置 `ANALYTICS_QUERY_SLOW_THRESHOLD=1000`（1s）
2. 触发查询
3. **预期**：
   - `analytics.query_log` 中该查询 `duration_ms > 1000`
   - `common.outbox_event` 出现 `analytics.query.slow` 事件
   - monitor 模块消费事件 → 告警中心（如果接入了 monitor）

### 7.3 Prometheus 指标

```bash
curl http://localhost:8080/actuator/prometheus | grep analytics_
```

**预期指标**：
- `analytics_query_duration_seconds_bucket{dataset_id=...}`
- `analytics_query_cache_hit_total`
- `analytics_notebook_run_duration_seconds_bucket{status=...}`

---

## 八、故障排查 Checklist

| 症状 | 可能原因 | 排查 |
|------|----------|------|
| 前端"数据分析"菜单不见 | App.tsx NAV 未含 analytics / BarChartOutlined 未 import | 检查 `web-console/src/App.tsx` |
| `/analytics/datasets` 404 | routes.tsx 未注册 | `grep analytics routes.tsx` 应见 7 条 |
| DatasetController 返回 401 | 未登录或 JWT 缺角色 | 用 admin 账户登录，JWT realm_access.roles 应含 `DE` |
| DatasetController 返回 40100 | TenantContext 未注入 | 检查 Keycloak realm 中 `tenant_id` claim 是否已加 |
| Trino 查询失败 50010 | Trino 未启动 / 网络不通 / 表不存在 | `curl http://localhost:8080/v1/info`；`SHOW TABLES FROM iceberg.dwd` |
| Superset 嵌入加载失败 | 数据源缺 tenant_id 列 / GUEST_TOKEN_JWT_SECRET 不一致 | 后端日志查 `verifyDatasourceHasTenantColumn` |
| 公开分享被拒（40003） | 绑定数据集有 row_filter | 这是预期行为（§5.3 安全约束） |
| Notebook 调度永远 RUNNING | NotebookRunSyncScheduler 没启动 / Dagster 不可达 | 后端日志查 `NotebookRunSyncScheduler`；30min 后自动 timeout |
| NL2SQL 报 50020 | OPENAI_API_KEY 未配置 / LLM 不可达 | `echo $OPENAI_API_KEY`；后端日志查 `OpenAiLlmClient` |
| 联动点击无反应 | events 配置不全 / filter.fromVar 写错 | 检查 Inspector 交互 Tab + 数据 Tab filter fromVar |
| Flyway migration 失败 | 历史迁移被改 / schema 列表不全 | `application.yml` 的 `flyway.schemas` 应含 `analytics` |

---

## 九、回归测试矩阵

每次 module-analytics 改动后，至少跑：

```bash
# 1) 单元测试（< 5 秒）
mvn -pl module-analytics test -Djacoco.skip=true

# 2) 前端类型检查 + 构建（< 10 秒）
cd web-console && pnpm exec tsc --noEmit && pnpm build

# 3) 完整链路（手动，CI 用 Selenium/Playwright）
#    建数据集 → 拖图 → 发布 → 公开分享（§四）
#    新建 Notebook → 交互执行 → 调度落表（§五）
```

CI 推荐加入：
```yaml
- name: Analytics unit tests
  run: mvn -pl module-analytics test -Djacoco.skip=true
- name: Flyway smoke (Docker required)
  run: mvn -pl bootstrap test -Dtest=BootstrapFlywaySmokeTest -Dtest.docker.enabled=true
- name: Frontend build
  run: cd web-console && pnpm install && pnpm exec tsc --noEmit && pnpm build
```

---

## 十、验收通过判据

| 项 | 通过标准 |
|----|----------|
| 单元测试 | 41 个测试全绿 |
| 前端构建 | tsc 0 错误 + vite build 成功 |
| 数据库迁移 | 9 schema 全部建成（Flyway smoke test 通过） |
| 主链路一 | 建数据集 → 拖折线图 → 发布 → 公开分享链接可看（含 row_filter 拦截验证） |
| 主链路二 | Notebook JupyterLab 打开 → 交互执行 → papermill 调度 SUCCEEDED → onelake.publish() 注册新资产 |
| Outbox 事件 | 6 类 analytics.* 事件按场景触发，可在 common.outbox_event 查询到 |
| 性能 | 同数据集多组件并发查询走 single-flight 缓存；慢查询触发告警 |

满足以上 7 项即视为联调通过，可进入生产灰度。

---

✅ 文档维护：本文档随设计方案 v1.1 同步更新。问题反馈 → `docs/数据分析与可视化模块设计方案.md` 评审。
