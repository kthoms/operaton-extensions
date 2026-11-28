/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

class FilterAndMappingTest {

    static final MockProducer<String, String> PRODUCER = new MockProducer<>(
            true, (org.apache.kafka.clients.producer.Partitioner) null, new StringSerializer(), new StringSerializer());

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> cfg.getProcessEnginePlugins().add(plugin()))
            .build();

    static HistoryKafkaPlugin plugin() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setProducer(PRODUCER);
        plugin.setEventTypes(List.of("process-instance", "task-instance.complete"));
        plugin.setTopicMapping(Map.of("process-instance.end", "finished-processes"));
        plugin.setTopic("all-history");
        plugin.setFormat(PayloadFormat.CLOUDEVENTS);
        plugin.setCloudEventsSource("urn:test:engine");
        plugin.setKeepDatabaseHandler(false);
        return plugin;
    }

    @BeforeEach
    void clear() {
        PRODUCER.clear();
    }

    @Test
    @Deployment(resources = "bpmn/simple.bpmn")
    void filtersByCategoryAndEventAndMapsTopics() throws Exception {
        var instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getTaskService()
                .complete(
                        engine.getTaskService().createTaskQuery().singleResult().getId());

        List<ProducerRecord<String, String>> records = PRODUCER.history();
        ObjectMapper mapper = new ObjectMapper();
        assertThat(records)
                .extracting(r -> mapper.readTree(r.value()).get("type").asText())
                .containsExactly(
                        "org.operaton.history.process-instance.start",
                        "org.operaton.history.task-instance.complete",
                        "org.operaton.history.process-instance.end");
        assertThat(records)
                .extracting(ProducerRecord::topic)
                .containsExactly("all-history", "all-history", "finished-processes");
        JsonNode ce = mapper.readTree(records.get(0).value());
        assertThat(ce.get("specversion").asText()).isEqualTo("1.0");
        assertThat(ce.get("source").asText()).isEqualTo("urn:test:engine");
        assertThat(ce.get("subject").asText()).isEqualTo(instance.getId());
        assertThat(ce.get("datacontenttype").asText()).isEqualTo("application/json");
        assertThat(ce.get("id").asText()).isNotBlank();
        assertThat(ce.get("time").asText()).isNotBlank();
        assertThat(new String(
                        records.get(0).headers().lastHeader("content-type").value()))
                .isEqualTo("application/cloudevents+json");
    }

    @Test
    @Deployment(resources = "bpmn/simple.bpmn")
    void databaseHandlerCanBeReplaced() {
        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .count())
                .isZero();
    }
}
