package com.onelake.orchestration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排控制面访问 Dagster GraphQL API 的轻量客户端。
 *
 * <p>封装作业启动、子图选择、运行状态、终止、仓库作业发现和 code location 重载。
 * 所有 GraphQL union 响应都在此转换为明确结果或 {@link DataplaneException}，上层服务
 * 无需感知 Dagster 的响应结构。</p>
 */
@Component
@RequiredArgsConstructor
public class DagsterClient {

    private static final String LAUNCH_RUN = """
        mutation($job: String!, $repo: String!, $location: String!) {
          launchRun(executionParams: {
            selector: { repositoryName: $repo, repositoryLocationName: $location, jobName: $job },
            executionMetadata: {}
          }) {
            __typename
            ... on LaunchRunSuccess { run { runId status } }
            ... on PythonError { message }
            ... on RunConfigValidationInvalid { errors { message } }
          }
        }""";

    private static final String LAUNCH_RUN_WITH_CONFIG = """
        mutation($job: String!, $repo: String!, $location: String!, $config: RunConfigData!, $tags: [ExecutionTag!]) {
          launchRun(executionParams: {
            selector: { repositoryName: $repo, repositoryLocationName: $location, jobName: $job },
            runConfigData: $config,
            executionMetadata: { tags: $tags }
          }) {
            __typename
            ... on LaunchRunSuccess { run { runId status } }
            ... on PythonError { message }
            ... on RunConfigValidationInvalid { errors { message } }
          }
        }""";

    private static final String LAUNCH_RUN_WITH_CONFIG_AND_SELECTION = """
        mutation($job: String!, $repo: String!, $location: String!, $config: RunConfigData!, $tags: [ExecutionTag!], $solidSelection: [String!]) {
          launchRun(executionParams: {
            selector: {
              repositoryName: $repo,
              repositoryLocationName: $location,
              jobName: $job,
              solidSelection: $solidSelection
            },
            runConfigData: $config,
            executionMetadata: { tags: $tags }
          }) {
            __typename
            ... on LaunchRunSuccess { run { runId status } }
            ... on PythonError { message }
            ... on RunConfigValidationInvalid { errors { message } }
          }
        }""";

    private static final String RUN_STATUS = """
        query($runId: ID!) {
          runOrError(runId: $runId) {
            __typename
            ... on Run { runId status startTime endTime }
            ... on PythonError { message }
          }
        }""";

    private static final String TERMINATE_RUN = """
        mutation($runId: String!, $policy: TerminateRunPolicy!) {
          terminateRun(runId: $runId, terminatePolicy: $policy) {
            __typename
            ... on TerminateRunSuccess { run { runId status } }
            ... on TerminateRunFailure { message run { runId status } }
            ... on RunNotFoundError { message }
            ... on PythonError { message }
          }
        }""";

    private static final String REPOSITORY_JOBS = """
        query($repo: String!, $location: String!) {
          repositoryOrError(repositorySelector: {
            repositoryName: $repo,
            repositoryLocationName: $location
          }) {
            __typename
            ... on Repository { jobs { name } }
            ... on RepositoryNotFoundError { message }
            ... on PythonError { message }
          }
        }""";

    private static final String RELOAD_REPOSITORY_LOCATION = """
        mutation($location: String!) {
          reloadRepositoryLocation(repositoryLocationName: $location) {
            __typename
            ... on PythonError { message }
          }
        }""";

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.dagster.graphql-url:http://localhost:3000/graphql}")
    private String graphqlUrl;

    /** 启动不携带 runConfig 和标签的 Dagster 作业。 */
    public String launch(String jobName, String repo, String location) {
        return launch(jobName, repo, location, null, null);
    }

    /** 启动完整作业，并携带由 Java 控制面生成的 runConfig 与运行标签。 */
    public String launch(String jobName, String repo, String location,
                         Map<String, Object> runConfig,
                         Iterable<Map<String, String>> tags) {
        return launch(jobName, repo, location, runConfig, tags, null);
    }

    /**
     * 启动 Dagster 作业，并可选择具体节点子图。
     *
     * <p>GRAPH 节点重跑通过 {@code solidSelection} 只提交目标子图；runConfig 仍由 Java
     * 提供，而 Dagster 保留选中节点原生的 step identity。</p>
     *
     * @return Dagster runId
     */
    public String launch(String jobName, String repo, String location,
                         Map<String, Object> runConfig,
                         Iterable<Map<String, String>> tags,
                         Iterable<String> solidSelection) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        List<String> selected = new ArrayList<>();
        if (solidSelection != null) {
            solidSelection.forEach(selected::add);
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("job", jobName);
        variables.put("repo", repo);
        variables.put("location", location);
        if (runConfig != null) {
            variables.put("config", runConfig);
            variables.put("tags", tags == null ? List.of() : tags);
        }
        if (!selected.isEmpty()) {
            variables.put("solidSelection", selected);
        }
        String query = runConfig == null
                ? LAUNCH_RUN
                : (selected.isEmpty() ? LAUNCH_RUN_WITH_CONFIG : LAUNCH_RUN_WITH_CONFIG_AND_SELECTION);
        var body = Map.of("query", query,
            "variables", variables);
        JsonNode resp = client.post()
            .bodyValue(body)
            .retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("dagster launch failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
        if (resp == null) throw new DataplaneException("dagster empty response");
        if (resp.hasNonNull("errors")) {
            throw new DataplaneException("dagster launch failed: " + resp.get("errors").toString());
        }
        String runId = resp.at("/data/launchRun/run/runId").asText("");
        if (runId.isBlank()) {
            throw new DataplaneException("dagster launch failed: " + launchError(resp));
        }
        return runId;
    }

    private String launchError(JsonNode resp) {
        JsonNode validationErrors = resp.at("/data/launchRun/errors");
        if (validationErrors.isArray() && !validationErrors.isEmpty()) {
            return validationErrors.get(0).path("message").asText(validationErrors.toString());
        }
        return resp.at("/data/launchRun/message").asText(resp.toString());
    }

    /** 查询 Dagster 运行状态，并把秒级 Unix 时间转换为 {@link Instant}。 */
    public RunStatus getRunStatus(String runId) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        JsonNode resp = client.post()
            .bodyValue(Map.of("query", RUN_STATUS, "variables", Map.of("runId", runId)))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(5));
        if (resp == null) {
            throw new DataplaneException("dagster empty response");
        }
        if (resp.hasNonNull("errors")) {
            throw new DataplaneException("dagster run status failed: " + resp.get("errors").toString());
        }
        JsonNode run = resp.at("/data/runOrError");
        if (!"Run".equals(run.path("__typename").asText())) {
            throw new DataplaneException("dagster run status failed: " + run.path("message").asText(run.toString()));
        }
        return new RunStatus(
            run.path("runId").asText(runId),
            run.path("status").asText(""),
            epochSeconds(run.path("startTime")),
            epochSeconds(run.path("endTime"))
        );
    }

    /**
     * 请求 Dagster 终止一次运行。
     *
     * @return Dagster 接受终止请求，或该运行已经不存在/已进入终态时返回 true。
     */
    public boolean terminate(String dagsterRunId, boolean force) {
        if (!StringUtils.hasText(dagsterRunId)) {
            throw new DataplaneException("dagster terminate failed: runId is blank");
        }
        String policy = force ? "MARK_AS_CANCELED_IMMEDIATELY" : "SAFE_TERMINATE";
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        JsonNode resp = client.post()
            .bodyValue(Map.of(
                "query", TERMINATE_RUN,
                "variables", Map.of("runId", dagsterRunId, "policy", policy)))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(5));
        if (resp == null) {
            throw new DataplaneException("dagster empty response");
        }
        if (resp.hasNonNull("errors")) {
            throw new DataplaneException("dagster terminate failed: " + resp.get("errors").toString());
        }
        JsonNode result = resp.at("/data/terminateRun");
        String type = result.path("__typename").asText("");
        if ("TerminateRunSuccess".equals(type) || "RunNotFoundError".equals(type)) {
            return true;
        }
        String status = result.at("/run/status").asText("");
        String message = result.path("message").asText(result.toString());
        if ("TerminateRunFailure".equals(type)
                && (isTerminalDagsterStatus(status) || isAlreadyTerminalTerminateMessage(message))) {
            return true;
        }
        throw new DataplaneException("dagster terminate failed: " + message);
    }

    /** 查询指定 repository/code location 当前暴露的全部作业名。 */
    public List<String> listJobs(String repo, String location) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        JsonNode resp = client.post()
            .bodyValue(Map.of("query", REPOSITORY_JOBS, "variables", Map.of("repo", repo, "location", location)))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(5));
        if (resp == null) {
            throw new DataplaneException("dagster empty response");
        }
        if (resp.hasNonNull("errors")) {
            throw new DataplaneException("dagster repository jobs failed: " + resp.get("errors").toString());
        }
        JsonNode repository = resp.at("/data/repositoryOrError");
        if (!"Repository".equals(repository.path("__typename").asText())) {
            throw new DataplaneException("dagster repository jobs failed: "
                + repository.path("message").asText(repository.toString()));
        }
        List<String> jobs = new ArrayList<>();
        JsonNode jobNodes = repository.path("jobs");
        if (jobNodes.isArray()) {
            jobNodes.forEach(job -> {
                String name = job.path("name").asText("");
                if (!name.isBlank()) {
                    jobs.add(name);
                }
            });
        }
        return jobs;
    }

    /** 重新加载 code location，使最新流水线拓扑成为 Dagster 原生 job 图。 */
    public void reloadRepositoryLocation(String location) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        JsonNode resp = client.post()
            .bodyValue(Map.of("query", RELOAD_REPOSITORY_LOCATION, "variables", Map.of("location", location)))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(15));
        if (resp == null || resp.hasNonNull("errors")) {
            throw new DataplaneException("dagster reload location failed: " + resp);
        }
        JsonNode result = resp.at("/data/reloadRepositoryLocation");
        if (!"WorkspaceLocationEntry".equals(result.path("__typename").asText())) {
            throw new DataplaneException("dagster reload location failed: "
                    + result.path("message").asText(result.toString()));
        }
    }

    private boolean isTerminalDagsterStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "SUCCESS", "SUCCEEDED", "FAILURE", "FAILED", "CANCELED", "CANCELLED" -> true;
            default -> false;
        };
    }

    private boolean isAlreadyTerminalTerminateMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim().toUpperCase();
        return normalized.contains("STATUS SUCCESS")
            || normalized.contains("STATUS SUCCEEDED")
            || normalized.contains("STATUS FAILURE")
            || normalized.contains("STATUS FAILED")
            || normalized.contains("STATUS CANCELED")
            || normalized.contains("STATUS CANCELLED")
            || normalized.contains("ALREADY FINISHED")
            || normalized.contains("ALREADY COMPLETED")
            || normalized.contains("TERMINAL STATE");
    }

    private Instant epochSeconds(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        double seconds = node.asDouble(Double.NaN);
        if (Double.isNaN(seconds) || seconds <= 0) {
            return null;
        }
        return Instant.ofEpochMilli((long) (seconds * 1000));
    }

    /**
     * Dagster 运行状态查询结果。
     *
     * @param runId Dagster run ID
     * @param status Dagster 原始状态字符串，由上层统一映射为 DagStatus
     * @param startedAt Dagster 报告的开始时间，可为空
     * @param finishedAt Dagster 报告的结束时间，可为空
     */
    public record RunStatus(String runId, String status, Instant startedAt, Instant finishedAt) {}
}
