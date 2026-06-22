package com.onelake.modeling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.onelake.common.exception.DataplaneException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DwdModelDagsterClient {

    private static final String LAUNCH_DWD_MODEL_RUN = """
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

    private final WebClient.Builder webClientBuilder;

    @Value("${onelake.dataplane.dagster.graphql-url:http://localhost:3000/graphql}")
    private String graphqlUrl;

    @Value("${onelake.dataplane.dagster.repository-name:onelake}")
    private String repositoryName;

    @Value("${onelake.dataplane.dagster.location-name:onelake-loc}")
    private String locationName;

    public LaunchResult launchDwdModelRun(String jobName, Map<String, Object> runConfig, List<Map<String, String>> tags) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        Map<String, Object> body = Map.of(
            "query", LAUNCH_DWD_MODEL_RUN,
            "variables", Map.of(
                "job", jobName,
                "repo", repositoryName,
                "location", locationName,
                "config", runConfig,
                "tags", tags == null ? List.of() : tags
            )
        );
        JsonNode resp = client.post()
            .bodyValue(body)
            .retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                r -> r.bodyToMono(String.class).map(b -> new DataplaneException("dagster launch failed: " + b)))
            .bodyToMono(JsonNode.class)
            .block();
        if (resp == null) {
            throw new DataplaneException("dagster empty response");
        }
        if (resp.hasNonNull("errors")) {
            throw new DataplaneException("dagster launch failed: " + resp.get("errors").toString());
        }
        String runId = resp.at("/data/launchRun/run/runId").asText("");
        String status = resp.at("/data/launchRun/run/status").asText("");
        if (runId.isBlank()) {
            throw new DataplaneException("dagster launch failed: " + launchError(resp));
        }
        return new LaunchResult(runId, status);
    }

    public RunStatus getRunStatus(String runId) {
        WebClient client = webClientBuilder.baseUrl(graphqlUrl).build();
        JsonNode resp = client.post()
            .bodyValue(Map.of("query", RUN_STATUS, "variables", Map.of("runId", runId)))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();
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

    private String launchError(JsonNode resp) {
        JsonNode validationErrors = resp.at("/data/launchRun/errors");
        if (validationErrors.isArray() && !validationErrors.isEmpty()) {
            return validationErrors.get(0).path("message").asText(validationErrors.toString());
        }
        return resp.at("/data/launchRun/message").asText(resp.toString());
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

    public record LaunchResult(String runId, String status) {}

    public record RunStatus(String runId, String status, Instant startedAt, Instant finishedAt) {}
}
