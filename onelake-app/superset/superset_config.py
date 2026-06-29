# Superset 配置：开启 EMBEDDED_SUPERSET + guest token JWT + CORS
# 详见 https://superset.apache.org/docs/installation/configuring-superset
import os

# ============ 嵌入式 SDK ============
FEATURE_FLAGS = {
    "EMBEDDED_SUPERSET": True,
}

# ============ Guest Token JWT ============
GUEST_TOKEN_JWT_SECRET = os.environ.get("GUEST_TOKEN_JWT_SECRET", "onelake-superset-guest")
GUEST_TOKEN_JWT_ALGO = "HS256"
GUEST_TOKEN_JWT_EXP_SECONDS = int(os.environ.get("GUEST_TOKEN_JWT_EXP_SECONDS", "3600"))
GUEST_TOKEN_JWT_LEEWAY = 60  # 容忍 60s 时钟漂移

# ============ CORS（嵌入 SDK 通过 iframe 加载）============
CORS_OPTIONS = {
    "allow_headers": ["*"],
    "allow_origin": ["*"],  # 生产建议精确配置前端域名
    "allow_methods": ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    "supports_credentials": False,
}

# ============ TALISMAN（iframe 必须放开 framing）============
TALISMAN_CONFIG = {
    "content_security_policy": {
        "frame-ancestors": [
            "http://localhost:5173",   # vite dev
            "http://localhost:9080",   # APISIX
            "http://localhost:8080",   # spring boot
        ],
    },
    "force_https": False,
    "strict_transport_security": False,
}

# ============ DB / Cache（沿用 docker-compose 默认）============
SQLALCHEMY_DATABASE_URI = "sqlite:////app/superset_home/superset.db"
CACHE_CONFIG = {
    "CACHE_TYPE": "RedisCache",
    "CACHE_DEFAULT_TIMEOUT": 60 * 60 * 24,
    "CACHE_REDIS_URL": os.environ.get("REDIS_URL", "redis://redis:6379/1"),
}
DATA_CACHE_CONFIG = CACHE_CONFIG

# ============ Secret ============
SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "onelake-superset-dev")

# ============ Public role 让 guest token 能访问 dashboard ============
PUBLIC_ROLE_LIKE = "Gamma"

print("[superset] config loaded, EMBEDDED_SUPERSET=True", flush=True)
