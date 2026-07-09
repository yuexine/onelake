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
import java.util.List;
import java.util.Map;

/**
 * Dagster GraphQL 客户端（对应《技术初始化文档》§6.5 编排模块）。
 * 调 Dagster GraphQL 触发物化（launchRun）。
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

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.dagster.graphql-url:http://localhost:3000/graphql}")
    private String graphqlUrl;

    public String launch(String jobName, String repo, String location) {
        return launch(jobName, repo, location, null, null);
    }

    public String launch(String jobName, String repo, String location,
                         Map<String, Object> runConfig,
                         Iterable<Map<String, String>> tags) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        Map<String, Object> variables = runConfig == null
            ? Map.of("job", jobName, "repo", repo, "location", location)
            : Map.of(
                "job", jobName,
                "repo", repo,
                "location", location,
                "config", runConfig,
                "tags", tags == null ? java.util.List.of() : tags
            );
        var body = Map.of("query", runConfig == null ? LAUNCH_RUN : LAUNCH_RUN_WITH_CONFIG,
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
     * Request Dagster to terminate a run.
     *
     * @return true when Dagster accepts the request, or the run is already gone/terminal.
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

    public record RunStatus(String runId, String status, Instant startedAt, Instant finishedAt) {}
}
