"""
Trino 直查封装：SQL -> pandas DataFrame。
"""
import os
from typing import List, Dict, Any

import pandas as pd
from trino.dbapi import connect as trino_connect


def query_trino_to_pandas(sql: str) -> pd.DataFrame:
    """
    执行 Trino 查询并返回 pandas DataFrame。

    凭据：环境变量 ONELAKE_TOKEN / ONELAKE_TENANT_ID（由 pre_spawn_hook 注入）。
    """
    host = os.environ.get('TRINO_HOST', 'trino')
    port = int(os.environ.get('TRINO_PORT', '8080'))
    user = os.environ.get('ONELAKE_TENANT_ID', 'onelake-anonymous')
    token = os.environ.get('ONELAKE_TOKEN', '')

    with trino_connect(
        host=host,
        port=port,
        user=user,
        http_scheme='http',
        http_headers={'X-Onelake-Token': token} if token else None,
    ) as conn:
        return pd.read_sql_query(sql, conn)


def query_trino_raw(sql: str) -> List[Dict[str, Any]]:
    """返回 list[dict]，避免 pandas 依赖。"""
    host = os.environ.get('TRINO_HOST', 'trino')
    port = int(os.environ.get('TRINO_PORT', '8080'))
    user = os.environ.get('ONELAKE_TENANT_ID', 'onelake-anonymous')

    with trino_connect(host=host, port=port, user=user) as conn:
        cur = conn.cursor()
        cur.execute(sql)
        cols = [d[0] for d in cur.description]
        rows = cur.fetchall()
        return [dict(zip(cols, r)) for r in rows]
