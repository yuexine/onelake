"""
pre_spawn_hook：每次 spawn Notebook 前调控制面 /api/v1/analytics/notebooks/{id}/issue-token
拿短期 token（默认 1h 过期），通过 environment 注入到 Notebook 进程。

关键设计（§7.11）：
- 不在镜像中存长期密钥
- ONELAKE_TOKEN / ONELAKE_TENANT_ID / ONELAKE_NOTEBOOK_ID 由控制面动态下发
- 每次启动 Notebook 自动刷新 token（无需用户手动）

参数说明（从 spawner.environment['onelake_notebook_id'] 传入）：
- ONELAKE_NOTEBOOK_ID：analytics.notebook.id（前端启动 Notebook 时由控制面写入）
- ONELAKE_NOTEBOOK_NAME：用于日志标识
- ONELAKE_CONTROL_PLANE_URL：控制面 URL（默认 http://backend:8080）
"""
import os
import requests


def pre_spawn_hook(spawner):
    auth_state = yield spawner.authenticator.get_auth_state(spawner.user.name)
    if not auth_state:
        raise RuntimeError(f'no auth_state for user {spawner.user.name}')

    access_token = auth_state.get('access_token')
    if not access_token:
        raise RuntimeError('auth_state missing access_token')

    control_plane_url = os.environ.get(
        'ONELAKE_CONTROL_PLANE_URL', 'http://backend:8080'
    ).rstrip('/')

    # 从 spawner.environment 获取 notebook_id（由前端创建 notebook 时写入）
    notebook_id = (spawner.environment or {}).get('ONELAKE_NOTEBOOK_ID')
    if not notebook_id:
        # 用户级 default notebook（首次访问）；控制面按需 lazy 注册
        notebook_id = f'user-{spawner.user.name}'

    # 调控制面 issue-token（控制面用 admin token 调，或携带用户 JWT 调）
    try:
        resp = requests.post(
            f'{control_plane_url}/api/v1/analytics/notebooks/issue-token',
            json={
                'notebook_id': notebook_id,
                'username': spawner.user.name,
            },
            headers={'Authorization': f'Bearer {access_token}'},
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        data = body.get('data', {})
    except Exception as e:
        # 控制面不可达时降级：用用户自己的 access_token 作为 ONELAKE_TOKEN（与 Keycloak 同源）
        print(f'[pre_spawn_hook] issue-token failed, fallback to user JWT: {e}', flush=True)
        data = {
            'token': access_token,
            'tenant_id': (spawner.environment or {}).get('ONELAKE_TENANT_ID', ''),
            'notebook_id': notebook_id,
            'expires_in': 3600,
        }

    # 注入环境变量到 Notebook 进程
    spawner.environment['ONELAKE_TOKEN'] = data.get('token', access_token)
    spawner.environment['ONELAKE_TENANT_ID'] = data.get('tenant_id', '')
    spawner.environment['ONELAKE_NOTEBOOK_ID'] = data.get('notebook_id', notebook_id)
    spawner.environment['ONELAKE_USERNAME'] = spawner.user.name
    spawner.environment['TRINO_HOST'] = os.environ.get('TRINO_HOST', 'trino')
    spawner.environment['ONELAKE_CONTROL_PLANE_URL'] = control_plane_url

    print(
        f'[pre_spawn_hook] token injected for {spawner.user.name} '
        f'(tenant={data.get("tenant_id")}, expires_in={data.get("expires_in", 3600)}s)',
        flush=True,
    )
