"""
OneLake Python SDK（onelake 包）—— Notebook 内的"读"与"写"入口。

设计原则（§7.11）：
- 读路径 dataset(fqn).to_pandas/.to_spark() 直查 Trino/Spark，不经控制面 REST
- 写路径 publish(df, name) 由 Spark 落 Iceberg 后调控制面 REST 注册资产
- 短期凭据由 JupyterHub pre_spawn_hook 注入环境变量（ONELAKE_TOKEN）

子模块：
- onelake.dataset / onelake.publish   : 用户面向 API
- onelake._control_plane              : 调控制面 REST（内部）
- onelake._trino / _spark             : 引擎连接器（内部）
"""
from onelake.api import dataset, publish, DatasetHandle

__all__ = ['dataset', 'publish', 'DatasetHandle']
__version__ = '0.1.0'
