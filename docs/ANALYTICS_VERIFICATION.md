# 数据分析与可视化模块验收清单

> 修订日期：2026-07-09
>
> 本文是当前验收清单，不记录历史测试通过数。实际测试数以当前 Maven 输出为准。

## 1. 静态构建

```bash
cd onelake-app
mvn -q -pl module-analytics -am test -Djacoco.skip=true

cd web-console
pnpm exec tsc --noEmit
pnpm build
```

## 2. 数据库迁移

Analytics 迁移目录：

```text
bootstrap/src/main/resources/db/migration/analytics/V1__analytics.sql
```

当前 Flyway schema 口径是 10 个 schema，其中 `analytics` 拥有 7 张表，`dataservice_api` 是视图 schema。

## 3. 服务探活

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/v3/api-docs >/dev/null
curl -fsS http://localhost:5173/ >/dev/null
curl -fsS http://localhost:18080/v1/info >/dev/null
curl -fsS http://localhost:8088/health
curl -i http://localhost:18000/
curl -fsS http://localhost:3000/
```

JupyterHub 未登录返回重定向也可能表示服务进程可达；需继续验证 SSO。

## 4. 登录账号

前端开发用户：

```text
dev / dev123456
```

`admin / admin` 是 Keycloak master 管理员，不应写成前端业务用户。

## 5. 主链路

### 5.1 数据集

1. 创建数据集。
2. 执行试查询。
3. 检查 `query_log`。
4. 检查脱敏/row_filter 约束。

### 5.2 Dashboard

1. 创建 Dashboard。
2. 绑定数据集和组件。
3. 保存草稿。
4. 发布内部版本。
5. 公开分享时验证 row_filter 禁止规则。
6. 打开 `/share/screen/:token`。

### 5.3 Notebook

1. 创建 Notebook。
2. 获取 JupyterLab URL。
3. 提交 Notebook run。
4. Dagster job 完成后回写状态。
5. artifact 发布到 Catalog。

### 5.4 NL2SQL

1. 设置 `OPENAI_API_KEY`。
2. 调用 `/api/v1/analytics/nl2sql`。
3. 校验生成 SQL 仍经过只读/安全约束。

## 6. 必须注明的风险

- Superset/JupyterHub/OpenAI 均是外部依赖，不可仅凭代码存在写“已验收”。
- Notebook SSO 依赖 Keycloak client 配置，需要和 `make seed` 保持一致。
- 前端 Analytics 页面已接 API，但真实数据可用依赖 Trino/Iceberg 表和后端服务。
