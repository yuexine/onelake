COMPOSE := docker compose -f docker-compose.yml
MVN := mvn -q

.PHONY: up down ps logs seed migrate backend debug frontend dev clean help fmt

help:
	@echo "OneLake 控制面 - 本地开发 Makefile"
	@echo "  make up          启动数据面底座（postgres/redis/minio/trino/keycloak/...）"
	@echo "  make down        停止并清理数据面"
	@echo "  make ps          查看容器状态"
	@echo "  make logs        跟踪数据面日志"
	@echo "  make seed        初始化 Keycloak realm + MinIO bucket"
	@echo "  make migrate     Flyway 在 8 个 schema 建表"
	@echo "  make backend     本地运行控制面（带 devtools 热重载）"
	@echo "  make debug       以 JDWP 5005 端口启动，供 IDE 远程挂载"
	@echo "  make frontend    启动前端 + 自动生成 API SDK"
	@echo "  make dev         一条龙: up + migrate + backend"
	@echo "  make clean       清理 target/"

up:
	$(COMPOSE) up -d
	@echo "waiting for postgres..." && sleep 5
	@echo "数据面已启动。访问: MinIO=9001  Trino=8080  Keycloak=8081  OpenMetadata=8585"

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
	$(MVN) -pl bootstrap flyway:migrate

backend:
	$(MVN) -pl bootstrap spring-boot:run

debug:
	$(MVN) -pl bootstrap spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

frontend:
	cd web-console && pnpm install && pnpm gen:api && pnpm dev

dev: up migrate backend

clean:
	$(MVN) clean

fmt:
	$(MVN) spotless:apply || true
