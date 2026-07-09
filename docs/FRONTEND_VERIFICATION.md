# 前端实现与验证口径

> 修订日期：2026-07-09
>
> 本文只描述当前 `web-console` 的路由、数据来源和验证方式。页面可点通不等于后端闭环完成；有 mock、fallback 或静态按钮时必须明确标注。

## 1. 技术基线

| 项 | 当前事实 |
| --- | --- |
| 框架 | React 18、Vite 5、TypeScript、Ant Design 5、Pro Components |
| 路由 | React Router，`App` 是 layout route；公开分享不挂 App layout |
| 请求 | `src/api/http.ts`，baseURL `/api/v1`，JWT/TraceId 注入、ApiResponse 解包、401 跳 SSO |
| API facade | `src/api/index.ts` 手写聚合了 System、Task、Notification、Integration、Pipeline、Catalog、Quality、Security、Dataservice、Analytics 等 |
| 开发代理 | Vite 默认 `/api -> http://localhost:8080`；设置 `VITE_API_PROXY_TARGET=http://localhost:9080` 才走 APISIX |
| 风险 | `pnpm gen:api` 输出到 `src/api`，可能覆盖手写 facade；执行前需确认输出目录策略 |

## 2. 状态分档

| 状态 | 定义 |
| --- | --- |
| 已接 API | 主列表/详情/主操作通过 `src/api` 或 `http` 调后端 |
| 混合数据源 | 同页存在真实 API 与 mock 初始值、失败 fallback、静态 tab 或假按钮 |
| 原型待接 | 主要是 mock 或内联静态数据，尚未形成后端闭环 |

## 3. 路由覆盖

当前业务 layout 下有 11 个顶层业务入口：

```text
/dashboard
/integration/*
/lakehouse/*
/orchestration/*
/quality/*
/catalog/*
/security/*
/dataservice/*
/analytics/*
/monitor/*
/system/*
```

另有：

```text
/sso/login
/sso/callback
/share/screen/:token
```

## 4. 模块数据来源

| 模块 | 状态 | 当前说明 |
| --- | --- | --- |
| 工作台 | 混合数据源 | 审批待办接 `SecurityAPI`；KPI、动态、快捷入口含硬编码/原型数据 |
| 数据集成 | 已接 API + 混合 | 数据源、采集任务、向导、运行日志接 `IntegrationAPI`；CDC schema 队列、模板、部分诊断仍混合 mock |
| 湖仓与建模 | 已接 API + 混合 | SQL Workbench、表详情、治理和优化中心接 Catalog/SQL/Modeling/Security/Orchestration API；部分动作仍是导航或待后端闭环 |
| 数据开发/编排 | 已接 API | 主路由使用 `UnifiedPipelineEditor`；`/orchestration/pipelines/:id/graph` 是历史重定向 |
| 数据质量 | 已接 API | 规则、结果、门禁接 `QualityAPI`；资产和术语联动 Catalog/Glossary |
| 目录与血缘 | 已接 API + 混合 | 资产和术语接 API；Lineage、GlobalSearch 和部分详情仍有 fallback/mock |
| 资产与安全 | 混合数据源 | PII 扫描接 API + fallback；脱敏策略、KMS 仍主要是原型 |
| 数据服务 | 混合数据源 | API 市场/详情/草稿/调试/发布/下线有 `DataserviceAPI`；AppKey、订阅、网关多为 mock/静态 |
| 数据分析 | 已接 API + 未运行验证 | 数据集、大屏、分享、模板、NL2SQL、Notebook 主入口接 `AnalyticsAPI`；JupyterHub/Superset/OpenAI 需外部服务验收 |
| 运营与监控 | 原型待接 | 总览、告警、故障复盘、SLA 基本是 mock |
| 系统管理 | 混合/原型 | 审批中心接 Security/Catalog；租户、RBAC、审计、渠道仍多为 mock |

## 5. 与旧验收文档的差异

本次修订废弃以下旧口径：

- 旧的固定页面数量验收口径不再作为当前事实；当前 `src/pages` 有 76 个 `.tsx`，其中包含页面和子组件。
- “所有跳转闭环”不再等同于后端闭环；跨模块导航存在，但参数落地和状态回写需按模块验证。
- “DAG 画布主入口”改为 `UnifiedPipelineEditor`；历史 graph 路由重定向。
- “DaaS/Monitor/System 全部完成”降级为混合或原型状态。

## 6. 验证方式

```bash
cd onelake-app/web-console
pnpm install
pnpm exec tsc --noEmit
pnpm build
pnpm lint
```

浏览器验证建议：

1. 启动后端和前端，打开 `http://localhost:5173/`。
2. 登录开发用户 `dev / dev123456`，不是 Keycloak master 管理员 `admin / admin`。
3. 按模块检查：是否加载真实接口、失败时是否显示清晰错误、是否触发 mock fallback。
4. 对可见 UI 变更补截图或 Playwright 检查；对纯文档修订不要求启动前端。

## 7. 后续前端文档规则

- 记录“页面存在”时，必须同时记录数据来源。
- 出现 fallback/mock 时，写成“混合数据源”，不要写“已完成”。
- `make frontend` 会跑 `pnpm gen:api`，在生成目录调整前不建议把它作为无风险启动命令。
- 新增路由时同步更新 `routes.tsx`、`App.tsx` 菜单和本文。
