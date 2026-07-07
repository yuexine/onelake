# JupyterHub 镜像：含 papermill + 算法库 + trino/spark client + onelake Python SDK
# 一行说明：JupyterHub 4.x 多用户 + JupyterLab + PySpark/Trino + 算法库
FROM jupyterhub/jupyterhub:4.1

USER root

# 系统依赖（OpenJDK 17 for PySpark + libsasl2 for Trino）
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless \
      libsasl2-modules \
      libsasl2-dev \
      curl \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Python 算法 + 工程 + 数据栈
RUN pip install --no-cache-dir \
      numpy==1.26.* \
      pandas==2.2.* \
      scipy==1.13.* \
      scikit-learn==1.5.* \
      statsmodels==0.14.* \
      prophet==1.1.* \
      plotly==5.22.* \
      matplotlib==3.9.* \
      seaborn==0.13.* \
      papermill==2.6.* \
      nbconvert==7.16.* \
      scrapbook==0.5.* \
      jupyterlab==4.2.* \
      ipywidgets==8.1.* \
      trino==0.330.* \
      pyiceberg==0.6.* \
      pyspark==3.5.* \
      requests==2.32.* \
      psycopg2-binary==2.9.* \
      redis==5.0.* \
      python-dotenv==1.0.*

RUN pip install --no-cache-dir oauthenticator==16.*

# onelake Python SDK（由 P4b 实现后，挂载 onelake-app/jupyter/onelake 到 /opt/onelake/sdk）
# 通过 editable install 或 PYTHONPATH 让用户 cell 中可 `from onelake import dataset, publish`
ENV PYTHONPATH="/opt/onelake/sdk:${PYTHONPATH}"

# JupyterHub 配置 + pre_spawn_hook（短期 token 由控制面签发）
COPY jupyterhub_config.py /srv/jupyterhub/jupyterhub_config.py
COPY pre_spawn_hook.py /srv/jupyterhub/pre_spawn_hook.py

# 入口由 base image 提供（jupyterhub -f /srv/jupyterhub/jupyterhub_config.py）
EXPOSE 8000
