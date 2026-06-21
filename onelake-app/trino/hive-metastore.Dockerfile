FROM apache/hive:4.0.0

ADD https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar /opt/hive/lib/postgresql-42.7.3.jar
USER root
RUN cp /opt/hadoop/share/hadoop/tools/lib/hadoop-aws-3.3.6.jar /opt/hive/lib/ \
    && cp /opt/hadoop/share/hadoop/tools/lib/aws-java-sdk-bundle-1.12.367.jar /opt/hive/lib/ \
    && chmod 0644 /opt/hive/lib/postgresql-42.7.3.jar \
    && chmod 0644 /opt/hive/lib/hadoop-aws-3.3.6.jar \
    && chmod 0644 /opt/hive/lib/aws-java-sdk-bundle-1.12.367.jar
USER hive
