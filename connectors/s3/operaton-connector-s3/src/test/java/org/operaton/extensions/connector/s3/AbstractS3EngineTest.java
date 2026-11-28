/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Base of the engine tests: an embedded engine (H2) with the Connect plugin and the S3 plugin
 * pointing at LocalStack, see {@code operaton.cfg.xml}.
 */
abstract class AbstractS3EngineTest {

    static final String BUCKET;
    static final String OTHER_BUCKET;

    static {
        LocalStackSupport.ensureStarted();
        BUCKET = LocalStackSupport.newBucket(false);
        OTHER_BUCKET = LocalStackSupport.newBucket(false);
        System.setProperty("s3.test.bucket", BUCKET);
        System.setProperty("s3.test.bucket2", OTHER_BUCKET);
    }

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    static void deploy(String... resources) {
        var deployment = engine.getRepositoryService().createDeployment().name("s3-test");
        for (String resource : resources) {
            deployment.addClasspathResource("bpmn/" + resource + ".bpmn");
        }
        Deployment deployed = deployment.deploy();
        engine.manageDeployment(deployed);
    }

    static ProcessInstance start(String processKey, Map<String, Object> variables) {
        return engine.getRuntimeService().startProcessInstanceByKey(processKey, variables);
    }

    static Object historic(ProcessInstance instance, String variable) {
        HistoricVariableInstance value = engine.getHistoryService()
                .createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName(variable)
                .singleResult();
        return value == null ? null : value.getValue();
    }

    static byte[] getObject(String bucket, String key) {
        try (S3Client admin = LocalStackSupport.admin()) {
            return admin.getObjectAsBytes(
                            GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
        }
    }

    static void putObject(String bucket, String key, byte[] content) {
        try (S3Client admin = LocalStackSupport.admin()) {
            admin.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
        }
    }

    static byte[] download(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " returned " + response.statusCode());
        }
        return response.body();
    }
}
