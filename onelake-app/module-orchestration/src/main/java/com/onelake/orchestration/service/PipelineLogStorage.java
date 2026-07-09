package com.onelake.orchestration.service;

import com.onelake.common.exception.DataplaneException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 流水线节点日志对象存储访问组件。
 *
 * <p>当前通过 MinIO 读取 Dagster/Spark 写入的日志对象，供节点运行详情接口流式返回。
 */
@Component
@Slf4j
public class PipelineLogStorage {

    private final MinioClient client;
    private final String bucket;

    @Autowired
    public PipelineLogStorage(
            @Value("${onelake.dataplane.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${onelake.dataplane.minio.access-key:minio}") String accessKey,
            @Value("${onelake.dataplane.minio.secret-key:minio12345}") String secretKey,
            @Value("${onelake.dataplane.minio.log-bucket:onelake-logs}") String bucket) {
        this(MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build(), bucket);
    }

    PipelineLogStorage(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.warn("流水线日志读取失败 bucket={} key={}：{}", bucket, objectKey, e.getMessage());
            throw new DataplaneException("读取节点日志失败", e);
        }
    }

    public long size(String objectKey) {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build()).size();
        } catch (Exception e) {
            log.warn("流水线日志元数据读取失败 bucket={} key={}：{}", bucket, objectKey, e.getMessage());
            throw new DataplaneException("读取节点日志元数据失败", e);
        }
    }
}
