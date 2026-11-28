/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/** Runs the document review process end to end against PostgreSQL and an S3 (LocalStack). */
@SpringBootTest
@Testcontainers
class DocumentReviewIT {

    static final String BUCKET = "documents";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static LocalStackContainer s3 =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices("s3");

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        String prefix = "operaton.extensions.connector.s3.stores.documents.";
        registry.add(prefix + "endpoint", () -> s3.getEndpoint().toString());
        registry.add(prefix + "region", s3::getRegion);
        registry.add(prefix + "access-key", s3::getAccessKey);
        registry.add(prefix + "secret-key", s3::getSecretKey);
    }

    @Autowired
    DocumentReviewService service;

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;

    @Autowired
    HistoryService historyService;

    @Autowired
    ManagementService managementService;

    static S3Client admin;

    @BeforeAll
    static void createBucket() {
        admin = S3Client.builder()
                .endpointOverride(s3.getEndpoint())
                .region(Region.of(s3.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .build();
        admin.createBucket(b -> b.bucket(BUCKET));
    }

    @Test
    void approvedDocumentIsReviewedThroughAPresignedLinkAndArchived() throws Exception {
        byte[] content = "Contract text".getBytes(StandardCharsets.UTF_8);
        String processInstanceId = service.submit("contract.txt", "text/plain", content, "alice");

        Task task = awaitReviewTask(processInstanceId);
        assertThat(task.getDescription()).startsWith("Review \"contract.txt\" (submitted by alice): http");
        String url = task.getDescription().substring(task.getDescription().indexOf("http"));

        // the reviewer opens the link: the document is served straight from S3
        HttpResponse<byte[]> download = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(content);

        String documentKey = (String) runtimeService.getVariable(processInstanceId, "documentKey");
        assertThat(documentKey).startsWith("incoming/").endsWith("/contract.txt");
        assertThat(head(documentKey).metadata()).containsEntry("submitted-by", "alice");

        taskService.complete(task.getId(), Map.of("approved", true));

        assertThat(historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .finished()
                        .count())
                .isEqualTo(1);
        assertThat(historyService
                        .createHistoricActivityInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .activityId("archived")
                        .count())
                .isEqualTo(1);
        String archiveKey = documentKey.replace("incoming/", "archive/");
        assertThat(keys("archive/")).contains(archiveKey);
        assertThat(keys("incoming/")).doesNotContain(documentKey);
        assertThat(admin.getObjectAsBytes(GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key(archiveKey)
                                .build())
                        .asByteArray())
                .isEqualTo(content);
    }

    @Test
    void rejectedDocumentIsDeleted() {
        String processInstanceId =
                service.submit("draft.txt", "text/plain", "Draft".getBytes(StandardCharsets.UTF_8), "alice");

        Task task = awaitReviewTask(processInstanceId);
        String documentKey = (String) runtimeService.getVariable(processInstanceId, "documentKey");
        assertThat(keys("incoming/")).contains(documentKey);

        taskService.complete(task.getId(), Map.of("approved", false));

        assertThat(historyService
                        .createHistoricActivityInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .activityId("rejected")
                        .count())
                .isEqualTo(1);
        assertThat(keys("incoming/")).doesNotContain(documentKey);
        assertThat(keys("archive/")).noneMatch(k -> k.endsWith("/draft.txt"));
    }

    @Test
    void aProcessWithoutDocumentFailsLoudlyWithAnIncident() {
        String processInstanceId = runtimeService
                .startProcessInstanceByKey(
                        DocumentReviewService.PROCESS_KEY,
                        Map.of(
                                "documentId", "no-document",
                                "documentName", "missing.txt",
                                "documentContentType", "text/plain",
                                "submitter", "alice"))
                .getId();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(runtimeService
                                .createIncidentQuery()
                                .processInstanceId(processInstanceId)
                                .count())
                        .isEqualTo(1));
        String incidentJob = runtimeService
                .createIncidentQuery()
                .processInstanceId(processInstanceId)
                .singleResult()
                .getConfiguration();
        assertThat(managementService.getJobExceptionStacktrace(incidentJob)).contains("document");
        assertThatThrownBy(() -> head("incoming/no-document/missing.txt")).isInstanceOf(SdkException.class);
    }

    private Task awaitReviewTask(String processInstanceId) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> taskService
                                .createTaskQuery()
                                .processInstanceId(processInstanceId)
                                .count()
                        == 1);
        Task task = taskService
                .createTaskQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        assertThat(task.getName()).isEqualTo("Review document");
        return task;
    }

    private static software.amazon.awssdk.services.s3.model.HeadObjectResponse head(String key) {
        return admin.headObject(
                HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
    }

    private static List<String> keys(String prefix) {
        return admin
                .listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(BUCKET)
                        .prefix(prefix)
                        .build())
                .contents()
                .stream()
                .map(o -> o.key())
                .toList();
    }
}
