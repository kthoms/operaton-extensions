/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageCalculator;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageEvent;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageResult;
import org.operaton.extensions.plugin.processtestcoverage.model.ModelCoverage;
import org.operaton.extensions.plugin.processtestcoverage.model.ProcessModel;
import org.operaton.extensions.plugin.processtestcoverage.model.TestRun;
import org.operaton.extensions.plugin.processtestcoverage.model.TestSuite;

/**
 * Writes the coverage of a finished suite as {@code coverage.json} and a self-contained
 * {@code index.html}. The HTML embeds the data and the BPMN XML and renders the diagrams with bpmn-js
 * loaded from a CDN; without network access it falls back to lists of covered and uncovered
 * elements.
 */
public final class CoverageReportWriter {

    /** Default bpmn-js script (pinned version). */
    public static final String DEFAULT_BPMN_JS_URL =
            "https://cdn.jsdelivr.net/npm/bpmn-js@17.11.1/dist/bpmn-navigated-viewer.production.min.js";

    /** Default bpmn-js stylesheet (pinned version). */
    public static final String DEFAULT_BPMN_JS_CSS_URL =
            "https://cdn.jsdelivr.net/npm/bpmn-js@17.11.1/dist/assets/bpmn-js.css";

    private static final String TEMPLATE = "/org/operaton/extensions/plugin/processtestcoverage/report/report.html";

    private final String bpmnJsUrl;
    private final String bpmnJsCssUrl;

    public CoverageReportWriter(String bpmnJsUrl, String bpmnJsCssUrl) {
        this.bpmnJsUrl = bpmnJsUrl;
        this.bpmnJsCssUrl = bpmnJsCssUrl;
    }

    /** The report data as a JSON-ready structure. */
    public Map<String, Object> toData(TestSuite suite, Collection<ProcessModel> models) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("suite", suite.id());
        data.put("name", suite.name());
        data.put("generatedAt", Instant.now().toString());
        data.put("bpmnJsUrl", bpmnJsUrl);
        data.put("bpmnJsCssUrl", bpmnJsCssUrl);
        List<Object> modelData = new ArrayList<>();
        for (ProcessModel model : models) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", model.key());
            m.put("version", model.version());
            m.put("versionTag", model.versionTag());
            m.put("totalElementCount", model.totalElementCount());
            m.put("flowNodeIds", model.flowNodeIds().stream().sorted().toList());
            m.put("sequenceFlowIds", model.sequenceFlowIds().stream().sorted().toList());
            m.put("bpmn", model.bpmnXml());
            modelData.add(m);
        }
        data.put("models", modelData);
        data.put("coverage", coverage(suite.events(), models));
        List<Object> runs = new ArrayList<>();
        for (TestRun run : suite.runs()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", run.id());
            r.put("name", run.name());
            r.put("displayName", run.displayName());
            r.put("coverage", coverage(run.events(), models));
            List<Object> events = new ArrayList<>();
            for (CoverageEvent e : run.events()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("source", e.source().name());
                ev.put("elementId", e.elementId());
                ev.put("elementType", e.elementType());
                ev.put("processKey", e.processKey());
                ev.put("timestamp", e.timestamp().toString());
                events.add(ev);
            }
            r.put("events", events);
            runs.add(r);
        }
        data.put("runs", runs);
        return data;
    }

    private static Map<String, Object> coverage(Collection<CoverageEvent> events, Collection<ProcessModel> models) {
        CoverageResult result = CoverageCalculator.calculate(events, models);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ratio", result.isEmpty() ? null : result.ratio());
        out.put("covered", result.covered());
        out.put("total", result.total());
        List<Object> perModel = new ArrayList<>();
        for (ModelCoverage mc : result.models()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("processKey", mc.processKey());
            m.put("ratio", mc.ratio());
            m.put("covered", mc.covered());
            m.put("total", mc.total());
            m.put("coveredFlowNodeIds", mc.coveredFlowNodeIds());
            m.put("coveredSequenceFlowIds", mc.coveredSequenceFlowIds());
            perModel.add(m);
        }
        out.put("models", perModel);
        return out;
    }

    /**
     * Writes {@code coverage.json} and {@code index.html} into {@code directory} (created if missing).
     *
     * @throws UncheckedIOException when a file cannot be written
     */
    public void write(Path directory, TestSuite suite, Collection<ProcessModel> models) {
        String json = Json.write(toData(suite, models));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("coverage.json"), json, StandardCharsets.UTF_8);
            // "</" would end the embedding script element early
            String safe = json.replace("</", "<\\/");
            Files.writeString(
                    directory.resolve("index.html"),
                    template().replace("/*__DATA__*/null", safe),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the coverage report of " + suite.id() + " to " + directory, e);
        }
    }

    private static String template() throws IOException {
        try (InputStream in = CoverageReportWriter.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("report template " + TEMPLATE + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
