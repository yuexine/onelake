# P-Spark: extra jars mounted into Spark containers
# Place iceberg-spark-runtime-*.jar and hadoop-aws-*.jar here if offline.
# spark-submit will pick them up via --jars.
#
# Online install (default): spark.jars.packages in spark-defaults.conf pulls them
# from Maven automatically on first run.
