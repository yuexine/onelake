COMPOSE := docker compose -f docker-compose.yml
MVN := mvn -q

.PHONY: up up-core dagster-up dagster-down airbyte-up airbyte-status airbyte-credentials airbyte-down down ps logs seed migrate ods-dwd-baseline ods-dwd-verify backend debug frontend dev clean help fmt

help:
	@echo "OneLake 控制面 - 本地开发 Makefile"
	@echo "  make up          启动 Compose 数据面（不含 Airbyte；Airbyte 用 abctl）"
	@echo "  make up-core     启动控制面最小依赖（postgres/redis/minio/keycloak）"
	@echo "  make dagster-up  构建并启动 Dagster webserver/daemon/code-location"
	@echo "  make airbyte-up  使用 abctl 启动本地 Airbyte（默认 http://localhost:8000）"
	@echo "  make airbyte-status / airbyte-credentials / airbyte-down"
	@echo "  make down        停止并清理数据面"
	@echo "  make ps          查看容器状态"
	@echo "  make logs        跟踪数据面日志"
	@echo "  make seed        初始化 Keycloak realm + MinIO bucket"
	@echo "  make migrate     Flyway 按 schema 顺序执行迁移"
	@echo "  make ods-dwd-baseline  准备 ODS->DWD 样例源表与 Iceberg ODS 表"
	@echo "  make ods-dwd-verify    验证 ODS->DWD 样例基线"
	@echo "  make backend     本地运行控制面（带 devtools 热重载）"
	@echo "  make debug       以 JDWP 5005 端口启动，供 IDE 远程挂载"
	@echo "  make frontend    启动前端 + 自动生成 API SDK"
	@echo "  make dev         一条龙: up + migrate + backend"
	@echo "  make clean       清理 target/"

up:
	$(COMPOSE) up -d
	@echo "waiting for postgres..." && sleep 5
	@echo "Compose 数据面已启动。访问: MinIO=9001  Trino=8080  Keycloak=8081  OpenMetadata=8585  Dagster=3000"
	@echo "Airbyte 不再由 docker-compose 管理，请使用: make airbyte-up"

up-core:
	$(COMPOSE) up -d postgres redis minio keycloak
	@echo "控制面最小依赖已启动。访问: MinIO=9001  Keycloak=8081"

dagster-up:
	$(COMPOSE) up -d --build dagster-postgres dagster-user-code dagster-webserver dagster-daemon
	@echo "Dagster 已启动。访问: http://localhost:3000"

dagster-down:
	$(COMPOSE) stop dagster-daemon dagster-webserver dagster-user-code dagster-postgres

airbyte-up:
	./scripts/airbyte-local.sh install

airbyte-status:
	./scripts/airbyte-local.sh status

airbyte-credentials:
	./scripts/airbyte-local.sh credentials

airbyte-down:
	./scripts/airbyte-local.sh uninstall

down:
	$(COMPOSE) down

ps:
	$(COMPOSE) ps

logs:
	$(COMPOSE) logs -f --tail=200

seed:
	./scripts/keycloak-realm.sh
	./scripts/minio-bucket.sh warehouse

migrate:
	MVN="$(MVN)" ./scripts/flyway-migrate.sh

ods-dwd-baseline:
	./scripts/ods-dwd-baseline.sh all

ods-dwd-verify:
	./scripts/ods-dwd-baseline.sh verify

backend:
	$(MVN) -pl bootstrap -am install -DskipTests -Djacoco.skip=true
	$(MVN) -pl bootstrap spring-boot:run

debug:
	$(MVN) -pl bootstrap -am install -DskipTests -Djacoco.skip=true
	$(MVN) -pl bootstrap spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

frontend:
	cd web-console && pnpm install && pnpm gen:api && pnpm dev

dev: up migrate backend

clean:
	$(MVN) clean

fmt:
	$(MVN) spotless:apply || true
