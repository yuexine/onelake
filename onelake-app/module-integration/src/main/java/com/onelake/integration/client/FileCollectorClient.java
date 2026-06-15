package com.onelake.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onelake.common.exception.DataplaneException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件采集客户端（S3 / MinIO REST API 驱动）。
 *
 * <p>使用 WebClient 直接调用 MinIO / S3 兼容的 REST API（ListObjectsV2），
 * 避免引入 ~30MB 的 AWS SDK 依赖，与 Airbyte driver 保持一致的架构风格。
 *
 * <p>能力：
 * <ul>
 *   <li>{@link #listFiles} — 列出 bucket 下的文件（含大小/ETag）</li>
 *   <p>去重：ETag (MD5) 相同的文件视为重复，前端标记"去重跳过"</li>
 * </ul>
 */
@Slf4j
@Component
public class FileCollectorClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileCollectorClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Value("${onelake.dataplane.minio.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${onelake.dataplane.minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${onelake.dataplane.minio.secret-key:minioadmin}")
    private String secretKey;

    /**
     * 列出 bucket 下指定 prefix 的文件列表。
     *
     * @param bucket MinIO bucket 名
     * @param prefix 路径前缀（如 inbound/orders/）
     * @return 文件列表：filename, sizeMb, etag(MD5), dedupKey
     */
    public List<Map<String, Object>> listFiles(String bucket, String prefix) {
        String uri = "/" + bucket + "?list-type=2&prefix=" + (prefix == null ? "" : prefix);
        log.info("FileCollector listFiles bucket={} prefix={}", bucket, prefix);

        try {
            String xml = webClientBuilder.build()
                .get()
                .uri(endpoint + uri)
                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString((accessKey + ":" + secretKey).getBytes()))
                .retrieve()
                .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                    r -> r.bodyToMono(String.class)
                        .map(b -> new DataplaneException("minio listFiles failed: " + b)))
                .bodyToMono(String.class)
                .block();

            return parseS3Listing(xml);
        } catch (DataplaneException e) {
            throw e;
        } catch (Exception e) {
            log.warn("FileCollector listFiles failed (MinIO may be down): {}", e.getMessage());
            return List.of(); // 空列表而非异常，前端显示空态
        }
    }

    /**
     * 检测重复文件：比较 etag (MD5) 集合。
     * @param files 当前扫描到的文件列表
     * @param knownEtags 之前已采集过的 etag 集合
     * @return 每个文件是否重复
     */
    public Map<String, Boolean> detectDuplicates(List<Map<String, Object>> files,
                                                  java.util.Set<String> knownEtags) {
        Map<String, Boolean> result = new java.util.HashMap<>();
        for (Map<String, Object> f : files) {
            String etag = String.valueOf(f.getOrDefault("etag", ""));
            String filename = String.valueOf(f.get("filename"));
            result.put(filename, knownEtags.contains(etag));
        }
        return result;
    }

    /**
     * 解析 S3 ListObjectsV2 XML 响应（简化版，提取 Contents 节点）。
     * MinIO 返回标准 S3 XML 格式。
     */
    @SuppressWarnings("java:S2755") // XML parsing is safe here (trusted MinIO source)
    private List<Map<String, Object>> parseS3Listing(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            var doc = builder.parse(new java.io.ByteArrayInputStream(xml.getBytes()));
            var contents = doc.getElementsByTagName("Contents");
            java.util.Set<String> seenEtogs = new java.util.HashSet<>();
            for (int i = 0; i < contents.getLength(); i++) {
                var node = contents.item(i);
                var children = node.getChildNodes();
                String key = null, etag = null;
                long size = 0;
                for (int j = 0; j < children.getLength(); j++) {
                    var child = children.item(j);
                    String name = child.getNodeName();
                    String text = child.getTextContent().trim();
                    switch (name) {
                        case "Key" -> key = text;
                        case "ETag" -> etag = text.replace("\"", "");
                        case "Size" -> { try { size = Long.parseLong(text); } catch (NumberFormatException ignored) {} }
                    }
                }
                if (key == null) continue;
                boolean dup = etag != null && !seenEtogs.add(etag);
                files.add(Map.of(
                    "filename", key,
                    "sizeMb", size / (1024.0 * 1024.0),
                    "etag", etag == null ? "-" : etag,
                    "dedup", dup
                ));
            }
        } catch (Exception e) {
            log.warn("parseS3Listing failed: {}", e.getMessage());
        }
        return files;
    }
}
