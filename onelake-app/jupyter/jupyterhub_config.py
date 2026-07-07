# JupyterHub 配置：Keycloak OAuth + DockerSpawner + pre_spawn_hook 短期 token
# 详见 https://jupyterhub.readthedocs.io/en/stable/reference/config-services.html
import os
import sys

# 加载 pre_spawn_hook（spawn 前调控制面 issue-token）
sys.path.insert(0, '/srv/jupyterhub')
from pre_spawn_hook import pre_spawn_hook  # noqa: E402

c = get_config()  # type: ignore  # noqa: F821

# ============ 基础 ============
c.JupyterHub.ip = '0.0.0.0'
c.JupyterHub.port = int(os.environ.get('JUPYTERHUB_PORT', '8000'))
c.JupyterHub.hub_ip = '0.0.0.0'
c.JupyterHub.allow_origin = '*'

# DB 持久化（hub state）
c.JupyterHub.db_url = 'sqlite:////srv/jupyterhub/data/jupyterhub.sqlite'

# ============ 认证：Keycloak OAuth ============
from oauthenticator.generic import GenericOAuthenticator  # noqa: E402

KEYCLOAK_URL = os.environ.get('KEYCLOAK_ISSUER', 'http://keycloak:8080/realms/onelake')

c.JupyterHub.authenticator_class = GenericOAuthenticator
c.GenericOAuthenticator.oauth_callback_url = os.environ.get(
    'OAUTH_CALLBACK_URL', 'http://localhost:8000/hub/oauth_callback'
)
c.GenericOAuthenticator.client_id = os.environ.get('OAUTH_CLIENT_ID', 'jupyterhub')
c.GenericOAuthenticator.client_secret = os.environ.get('OAUTH_CLIENT_SECRET', 'jupyterhub-dev-secret')
c.GenericOAuthenticator.authorize_url = f'{KEYCLOAK_URL}/protocol/openid-connect/auth'
c.GenericOAuthenticator.token_url = f'{KEYCLOAK_URL}/protocol/openid-connect/token'
c.GenericOAuthenticator.userdata_url = f'{KEYCLOAK_URL}/protocol/openid-connect/userinfo'
c.GenericOAuthenticator.userdata_method = 'GET'
c.GenericOAuthenticator.username_key = 'preferred_username'
c.GenericOAuthenticator.claim_groups_key = 'realm_access.roles'
c.GenericOAuthenticator.allowed_groups = ['DE', 'ADMIN']

# ============ Spawner：每用户独立容器（notebook docker image）============
# P4a 简化：使用 DummySpawner（同进程内 spawn），生产换 DockerSpawner/KubeSpawner。
# 注释保留 DockerSpawner 配置以便生产迁移。
#
# from dockerspawner import DockerSpawner
# c.JupyterHub.spawner_class = DockerSpawner
# c.DockerSpawner.image = 'onelake/jupyter-notebook:latest'
# c.DockerSpawner.network = ['onelake_default']
# c.DockerSpawner.remove_containers = True
# c.DockerSpawner.mem_limit = '2G'
# c.DockerSpawner.cpu_limit = 2.0
# c.DockerSpawner.volumes = {
#     'jupyterdata/{username}': '/home/jovyan/work',
#     '/var/run/docker.sock': '/var/run/docker.sock',
# }

from jupyterhub.spawner import SimpleLocalProcessSpawner  # noqa: E402
c.JupyterHub.spawner_class = SimpleLocalProcessSpawner
c.Spawner.notebook_dir = '/home/jovyan/work'

# ============ pre_spawn_hook：调控制面 issue-token 拿短期 token ============
# 关键设计（§7.11）：
#   pre_spawn_hook 在每次 spawn 前调用，从控制面拿 1h 过期的 ONELAKE_TOKEN，
#   通过 environment 注入到 Notebook 进程，避免镜像内硬编码长期密钥。
c.Spawner.pre_spawn_hook = pre_spawn_hook

# ============ 管理员 ============
c.Authenticator.admin_users = {'onelake-admin'}

# ============ 日志 ============
c.JupyterHub.log_level = 'INFO'

print(f'[jupyterhub] config loaded, keycloak={KEYCLOAK_URL}', flush=True)
