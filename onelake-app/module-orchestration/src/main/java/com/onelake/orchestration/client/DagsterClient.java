package com.onelake.orchestration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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
