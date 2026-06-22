package com.onelake.modeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.util.JsonUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DwdRunArtifactReader {

    public DbtRunArtifacts read(String dbtModelName) {
        Path dbtRoot = dbtProjectDir();
        Path runResultsPath = dbtRoot.resolve("target/run_results.json");
        Path catalogPath = dbtRoot.resolve("target/catalog.json");

        if (!Files.exists(runResultsPath)) {
            return new DbtRunArtifacts(false, null, null, null, "target/run_results.json 不存在", List.of());
        }

        try {
            JsonNode runResults = JsonUtil.parse(Files.readString(runResultsPath));
            ArtifactResult result = findModelResult(runResults, dbtModelName);
            List<DbtCheckResult> checks = findCheckResults(runResults);
            Long catalogRows = readCatalogRows(catalogPath, dbtModelName);
            Long rows = result.rowsAffected() == null ? catalogRows : result.rowsAffected();
            return new DbtRunArtifacts(
                true,
                "target/run_results.json",
                rows,
                result.status(),
                result.errorMessage(),
                checks
            );
        } catch (Exception e) {
            return new DbtRunArtifacts(false, "target/run_results.json", null, null, e.getMessage(), List.of());
        }
    }

    private List<DbtCheckResult> findCheckResults(JsonNode runResults) {
        JsonNode results = runResults.path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<DbtCheckResult> checks = new ArrayList<>();
        for (JsonNode result : results) {
            String uniqueId = result.path("unique_id").asText("");
            if (!uniqueId.startsWith("test.")) {
                continue;
            }
            String status = result.path("status").asText("");
            Long failures = failures(result);
            String message = firstText(result.path("message").asText(""), result.path("failures").asText(""));
            checks.add(new DbtCheckResult(uniqueId, checkName(uniqueId), status, failures, blankToNull(message)));
        }
        return checks;
    }

    private ArtifactResult findModelResult(JsonNode runResults, String dbtModelName) {
        JsonNode results = runResults.path("results");
        if (!results.isArray()) {
            return new ArtifactResult(null, null, "run_results.json 缺少 results 数组");
        }
        ArtifactResult fallback = null;
        for (JsonNode result : results) {
            String uniqueId = result.path("unique_id").asText("");
            String status = result.path("status").asText("");
            Long rowsAffected = rowsAffected(result.path("adapter_response"));
            String message = firstText(result.path("message").asText(""), result.path("failures").asText(""));
            ArtifactResult parsed = new ArtifactResult(status, rowsAffected, blankToNull(message));
            if (uniqueId.endsWith("." + dbtModelName) || uniqueId.contains("." + dbtModelName + ".")) {
                return parsed;
            }
            if (fallback == null && uniqueId.startsWith("model.")) {
                fallback = parsed;
            }
        }
        return fallback == null
            ? new ArtifactResult(null, null, "run_results.json 未找到模型结果: " + dbtModelName)
            : fallback;
    }

    private Long rowsAffected(JsonNode adapterResponse) {
        if (adapterResponse == null || adapterResponse.isMissingNode() || adapterResponse.isNull()) {
            return null;
        }
        for (String field : new String[] {"rows_affected", "rowsAffected", "num_rows", "numRows"}) {
            JsonNode value = adapterResponse.path(field);
            if (value.isNumber()) {
                return value.asLong();
            }
        }
        return null;
    }

    private Long failures(JsonNode result) {
        JsonNode failures = result.path("failures");
        if (failures.isNumber()) {
            return failures.asLong();
        }
        Long rows = rowsAffected(result.path("adapter_response"));
        if (rows == null || rows < 0) {
            return "pass".equalsIgnoreCase(result.path("status").asText("")) ? 0L : 1L;
        }
        return rows;
    }

    private Long readCatalogRows(Path catalogPath, String dbtModelName) {
        if (!Files.exists(catalogPath)) {
            return null;
        }
        try {
            JsonNode catalog = JsonUtil.parse(Files.readString(catalogPath));
            JsonNode nodes = catalog.path("nodes");
            if (!nodes.isObject()) {
                return null;
            }
            var fields = nodes.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!entry.getKey().endsWith("." + dbtModelName)) {
                    continue;
                }
                JsonNode stats = entry.getValue().path("stats");
                for (String field : new String[] {"num_rows", "row_count"}) {
                    JsonNode value = stats.path(field).path("value");
                    if (value.isNumber()) {
                        return value.asLong();
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Path dbtProjectDir() {
        String configured = System.getProperty("onelake.dbt.projectDir");
        if (!StringUtils.hasText(configured)) {
            configured = System.getenv("ONELAKE_DBT_PROJECT_DIR");
        }
        if (!StringUtils.hasText(configured)) {
            configured = "../dbt";
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String checkName(String uniqueId) {
        if (!StringUtils.hasText(uniqueId)) {
            return "dbt_test";
        }
        int lastDot = uniqueId.lastIndexOf('.');
        return lastDot >= 0 && lastDot < uniqueId.length() - 1 ? uniqueId.substring(lastDot + 1) : uniqueId;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private record ArtifactResult(String status, Long rowsAffected, String errorMessage) {}

    public record DbtCheckResult(
        String uniqueId,
        String name,
        String status,
        Long failures,
        String message
    ) {}

    public record DbtRunArtifacts(
        boolean found,
        String artifactsPath,
        Long rowsAffected,
        String dbtStatus,
        String errorMessage,
        List<DbtCheckResult> checks
    ) {}
}
