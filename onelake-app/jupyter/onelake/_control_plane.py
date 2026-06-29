"""
控制面 REST 客户端（内部使用）。
"""
import os
import requests


class ControlPlaneClient:
    """调控制面 REST（issue-token / register-artifact / get-dataset）。"""

    def __init__(self, base_url: str, token: str, tenant_id: str):
        self.base_url = base_url.rstrip('/')
        self.token = token
        self.tenant_id = tenant_id

    @classmethod
    def from_env(cls) -> "ControlPlaneClient":
        return cls(
            base_url=os.environ.get('ONELAKE_CONTROL_PLANE_URL', 'http://backend:8080'),
            token=os.environ.get('ONELAKE_TOKEN', ''),
            tenant_id=os.environ.get('ONELAKE_TENANT_ID', ''),
        )

    def _headers(self) -> dict:
        return {
            'Authorization': f'Bearer {self.token}',
            'X-Trace-Id': f'onelake-py-{os.getpid()}',
        }

    def get_dataset(self, dataset_id: str) -> dict:
        """GET /api/v1/analytics/datasets/{id}"""
        resp = requests.get(
            f'{self.base_url}/api/v1/analytics/datasets/{dataset_id}',
            headers=self._headers(),
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get('code') != 0:
            raise RuntimeError(f"get_dataset failed: {body}")
        return body['data']

    def register_artifact(self, fqn: str, classification: str,
                          description: str = '', produced_by_notebook: str = '') -> dict:
        """
        POST /api/v1/analytics/notebooks/artifact
        控制面会发 Outbox ANALYTICS_NOTEBOOK_ARTIFACT_PUBLISHED 事件：
        - catalog 消费 → 登记新资产
        - analytics 自身消费 → 建 dataset 记录（source_type=NOTEBOOK）
        """
        resp = requests.post(
            f'{self.base_url}/api/v1/analytics/notebooks/artifact',
            json={
                'fqn': fqn,
                'classification': classification,
                'description': description,
                'produced_by_notebook': produced_by_notebook,
            },
            headers=self._headers(),
            timeout=10,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get('code') != 0:
            raise RuntimeError(f"register_artifact failed: {body}")
        return body.get('data', {})
