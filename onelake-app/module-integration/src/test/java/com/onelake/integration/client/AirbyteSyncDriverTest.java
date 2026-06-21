package com.onelake.integration.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AirbyteSyncDriverTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postAddsBearerTokenWhenClientCredentialsAreConfigured() throws IOException {
        AtomicReference<String> authHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/applications/token", exchange -> {
            respond(exchange, 200, """
                {"access_token":"token-123","token_type":"Bearer","expires_in":900}
                """);
        });
        server.createContext("/api/v1/source_definitions/list", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                {"sourceDefinitions":[]}
                """);
        });
        server.start();

        AirbyteSyncDriver driver = new AirbyteSyncDriver(WebClient.builder());
        ReflectionTestUtils.setField(driver, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/api/v1");
        ReflectionTestUtils.setField(driver, "clientId", "client-id");
        ReflectionTestUtils.setField(driver, "clientSecret", "client-secret");
        ReflectionTestUtils.setField(driver, "tokenPath", "/applications/token");

        assertThat(driver.listSourceDefinitions()).isEmpty();
        assertThat(authHeader.get()).isEqualTo("Bearer token-123");
    }

    @Test
    void definitionSpecRequestIncludesWorkspaceIdWhenConfigured() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/source_definition_specifications/get", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"connectionSpecification":{"type":"object","properties":{}}}
                """);
        });
        server.start();

        AirbyteSyncDriver driver = new AirbyteSyncDriver(WebClient.builder());
        ReflectionTestUtils.setField(driver, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/api/v1");
        ReflectionTestUtils.setField(driver, "workspaceId", "workspace-1");

        driver.getSourceDefinitionSpec("definition-1");

        assertThat(requestBody.get()).contains("\"sourceDefinitionId\":\"definition-1\"");
        assertThat(requestBody.get()).contains("\"workspaceId\":\"workspace-1\"");
    }

    @Test
    void connectionListRequestIncludesWorkspaceIdWhenConfigured() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/connections/list", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"connections":[]}
                """);
        });
        server.createContext("/api/v1/connections/create", exchange -> respond(exchange, 200, """
                {"connectionId":"connection-1"}
                """));
        server.start();

        AirbyteSyncDriver driver = new AirbyteSyncDriver(WebClient.builder());
        ReflectionTestUtils.setField(driver, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/api/v1");
        ReflectionTestUtils.setField(driver, "workspaceId", "workspace-1");

        String connectionId = driver.ensureConnection("source-1", "destination-1", "sync-name");

        assertThat(connectionId).isEqualTo("connection-1");
        assertThat(requestBody.get()).contains("\"workspaceId\":\"workspace-1\"");
    }

    @Test
    void ensureConnectionUsesDiscoveredCatalogAndTargetNamespace() throws IOException {
        AtomicReference<String> createBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/connections/list", exchange -> respond(exchange, 200, """
                {"connections":[]}
                """));
        server.createContext("/api/v1/sources/discover_schema", exchange -> respond(exchange, 200, """
                {"catalog":{"streams":[{"stream":{"name":"orders","namespace":"public","jsonSchema":{"type":"object","properties":{"id":{"type":"number"},"name":{"type":"string"}}},"supportedSyncModes":["full_refresh","incremental"],"sourceDefinedPrimaryKey":[["id"]]},"config":{"syncMode":"full_refresh","destinationSyncMode":"overwrite","primaryKey":[["id"]],"selected":true,"aliasName":"orders"}}]}}
                """));
        server.createContext("/api/v1/connections/create", exchange -> {
            createBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"connectionId":"connection-1"}
                """);
        });
        server.start();

        AirbyteSyncDriver driver = new AirbyteSyncDriver(WebClient.builder());
        ReflectionTestUtils.setField(driver, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/api/v1");

        String connectionId = driver.ensureConnection(
            "source-1",
            "destination-1",
            "sync-name",
            "public.orders",
            "ods.orders",
            java.util.List.of(java.util.Map.of("source", "id", "sourceType", "bigint"))
        );

        assertThat(connectionId).isEqualTo("connection-1");
        assertThat(createBody.get()).contains("\"namespaceDefinition\":\"customformat\"");
        assertThat(createBody.get()).contains("\"namespaceFormat\":\"ods\"");
        assertThat(createBody.get()).contains("\"sourceDefinedPrimaryKey\":[[\"id\"]]");
        assertThat(createBody.get()).contains("\"aliasName\":\"orders\"");
    }

    @Test
    void jobSnapshotReadsNestedAttemptStats() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/jobs/get", exchange -> respond(exchange, 200, """
                {"job":{"status":"succeeded"},"attempts":[{"attempt":{"recordsSynced":3,"bytesSynced":260}}]}
                """));
        server.start();

        AirbyteSyncDriver driver = new AirbyteSyncDriver(WebClient.builder());
        ReflectionTestUtils.setField(driver, "baseUrl", "http://localhost:" + server.getAddress().getPort() + "/api/v1");

        AirbyteSyncDriver.AirbyteJobSnapshot snapshot = driver.getJobSnapshot(2);

        assertThat(snapshot.status()).isEqualTo("succeeded");
        assertThat(snapshot.recordsSynced()).isEqualTo(3);
        assertThat(snapshot.bytesSynced()).isEqualTo(260);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
