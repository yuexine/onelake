package com.onelake.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Dagster 客户端（复用 module-orchestration.DagsterClient 的 GraphQL 模式，独立一份避免跨模块直读）。
 *
 * 关键操作：
 *   launchRun(repo, location, job, config)    -> 提交 onelake_notebook_run job
 *   runStatus(runId)                          -> 拉取运行状态
 *
 * P4c 启用；P1/P2/P3 占位，但 bean 已注册避免 Service 启动失败。
 */
@Slf4j
@Component("analyticsDagsterClient")
public class DagsterClient {

    private final WebClient webClient;
    private final String repositoryName;
    private final String locationName;

    public DagsterClient(
            @Value("${onelake.dataplane.dagster.graphql-url:http://localhost:3000/graphql}") String graphqlUrl,
            @Value("${onelake.dataplane.dagster.repository-name:onelake}") String repositoryName,
            @Value("${onelake.dataplane.dagster.location-name:onelake-loc}") String locationName,
            WebClient.Builder builder) {
        this.webClient = builder.baseUrl(graphqlUrl).build();
        this.repositoryName = repositoryName;
        this.locationName = locationName;
    }

    /**
     * 提交 Dagster job 运行。
     */
    public String launchRun(String jobName, Map<String, Object> runConfig) {
        Map<String, Object> variables = Map.of(
            "repositoryLocationName", locationName,
            "repositoryName", repositoryName,
            "jobName", jobName,
            "runConfigData", runConfig == null ? Map.of() : runConfig
        );
        String query = """
            mutation Launch($repositoryLocationName: String!, $repositoryName: String!,
                            $jobName: String!, $runConfigData: RunConfigData) {
              launchPipelineExecution(
                repositoryLocationName: $repositoryLocationName,
                repositoryName: $repositoryName,
                jobName: $jobName,
                runConfigData: $runConfigData
              ) { __typename ... on LaunchRunSuccess { run { runId status } }
                              ... on PythonError { message } }
            }
            """;
        try {
            JsonNode resp = webClient.post()
                .uri("")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query, "variables", variables))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .block();
            JsonNode run = resp != null ? resp.path("data").path("launchPipelineExecution").path("run") : null;
            if (run == null || run.isMissingNode() || !run.has("runId")) {
                throw new BizException(50010, "Dagster launchRun 失败：" + resp);
            }
            return run.get("runId").asText();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("dagster launchRun failed: {}", e.getMessage());
            throw new BizException(50010, "Dagster launchRun 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拉取 Dagster run 状态。
     */
    public String runStatus(String runId) {
        Map<String, Object> variables = Map.of("runId", runId);
        String query = """
            query Status($runId: String!) {
              runOrError(runId: $runId) {
                __typename ... on Run { status }
                            ... on RunNotFoundError { message }
              }
            }
            """;
        try {
            JsonNode resp = webClient.post()
                .uri("")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query, "variables", variables))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .block();
            JsonNode status = resp != null ? resp.path("data").path("runOrError").path("status") : null;
            return status == null || status.isMissingNode() ? "UNKNOWN" : status.asText();
        } catch (Exception e) {
            log.error("dagster runStatus failed: {}", e.getMessage());
            return "UNKNOWN";
        }
    }
}
