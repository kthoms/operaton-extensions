/*
 * Copyright (C) 2019 - 2024 Camunda
 * Modifications Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/camunda-process-test-coverage (Apache-2.0),
 * translated from Kotlin to Java and adapted to Operaton.
 */
package org.operaton.extensions.plugin.processtestcoverage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageCalculator;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageResult;
import org.operaton.extensions.plugin.processtestcoverage.model.TestRun;
import org.operaton.extensions.plugin.processtestcoverage.model.TestSuite;
import org.operaton.extensions.plugin.processtestcoverage.report.CoverageReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Jupiter extension that measures process test coverage. It is an Operaton
 * {@link ProcessEngineExtension} (use it instead of, not next to, that extension) that additionally
 * installs the {@link ProcessTestCoveragePlugin}, records the events of every test method, writes the
 * report when the test class has finished and asserts the configured minimum coverage.
 *
 * <pre>{@code
 * @RegisterExtension
 * static ProcessTestCoverageExtension coverage =
 *         ProcessTestCoverageExtension.newBuilder().withMinimumCoverage(0.8).build();
 * }</pre>
 *
 * <p>Register it in a {@code static} field or with {@code @ExtendWith}: class coverage needs the
 * before-all and after-all callbacks. Test methods of one class must not run in parallel. The
 * engine is private to the extension (random engine name) and closed after the class.
 *
 * <p>Ported in design from the {@code ProcessEngineCoverageExtension} of camunda-process-test-coverage.
 * This class is not thread-safe; JUnit calls it from one thread at a time.
 */
public class ProcessTestCoverageExtension extends ProcessEngineExtension {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessTestCoverageExtension.class);

    /** System property: minimum class coverage between 0 and 1, used when the builder sets none. */
    public static final String PROP_MINIMUM_COVERAGE = "operaton.process-test-coverage.minimum-coverage";
    /** System property: report directory, used when the builder sets none. */
    public static final String PROP_REPORT_DIRECTORY = "operaton.process-test-coverage.report-directory";
    /** System property: comma separated process definition keys to exclude. */
    public static final String PROP_EXCLUDED_KEYS = "operaton.process-test-coverage.excluded-process-keys";
    /** Default report directory, relative to the working directory. */
    public static final String DEFAULT_REPORT_DIRECTORY = "target/process-test-coverage";

    private final Double classMinimum;
    private final Double defaultMethodMinimum;
    private final Map<String, Double> methodMinimums;
    private final Set<String> excludedKeys;
    private final Path reportDirectory;
    private final boolean writeReport;
    private final CoverageReportWriter reportWriter;

    private CoverageCollector collector;
    private TestSuite suite;
    private String suiteOwnerContextId;

    /** Defaults and system properties only; used by {@code @ExtendWith(ProcessTestCoverageExtension.class)}. */
    public ProcessTestCoverageExtension() {
        this(newBuilder());
    }

    private ProcessTestCoverageExtension(Builder b) {
        this.classMinimum = b.classMinimum != null ? b.classMinimum : minimumFromSystemProperty();
        this.defaultMethodMinimum = b.defaultMethodMinimum;
        this.methodMinimums = Map.copyOf(b.methodMinimums);
        this.excludedKeys = b.excludedKeys.isEmpty() ? excludedFromSystemProperty() : Set.copyOf(b.excludedKeys);
        this.reportDirectory = b.reportDirectory != null
                ? b.reportDirectory
                : Path.of(System.getProperty(PROP_REPORT_DIRECTORY, DEFAULT_REPORT_DIRECTORY));
        this.writeReport = b.writeReport;
        this.reportWriter = new CoverageReportWriter(b.bpmnJsUrl, b.bpmnJsCssUrl);
        this.randomName = true;
        this.closeEngine = true;
        if (b.configurationResource != null) {
            this.configurationResource = b.configurationResource;
        }
        this.ensureCleanAfterTest = b.ensureCleanAfterTest;
        this.processEngineConfigurator = b.configurator;
    }

    /** Starts the configuration of an extension. */
    public static Builder newBuilder() {
        return new Builder();
    }

    private static Double minimumFromSystemProperty() {
        String value = System.getProperty(PROP_MINIMUM_COVERAGE);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return checkRatio(Double.parseDouble(value.trim()), "system property " + PROP_MINIMUM_COVERAGE);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "system property " + PROP_MINIMUM_COVERAGE + " must be a number between 0 and 1 but is '" + value
                            + "'",
                    e);
        }
    }

    private static Set<String> excludedFromSystemProperty() {
        String value = System.getProperty(PROP_EXCLUDED_KEYS);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static double checkRatio(double ratio, String what) {
        if (Double.isNaN(ratio) || ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(what + " must be between 0 and 1 but is " + ratio);
        }
        return ratio;
    }

    /** The collector of the engine; available once the engine exists. */
    public CoverageCollector getCoverageCollector() {
        if (collector == null) {
            throw new IllegalStateException("the process engine has not been initialised yet");
        }
        return collector;
    }

    @Override
    public void initializeProcessEngine() {
        Consumer<ProcessEngineConfigurationImpl> user = processEngineConfigurator;
        processEngineConfigurator = config -> {
            if (user != null) {
                user.accept(config);
            }
            installPlugin(config);
        };
        try {
            super.initializeProcessEngine();
        } finally {
            processEngineConfigurator = user;
        }
        bindCollector();
    }

    private static void installPlugin(ProcessEngineConfigurationImpl config) {
        List<ProcessEnginePlugin> plugins = config.getProcessEnginePlugins();
        if (plugins == null) {
            plugins = new ArrayList<>();
            config.setProcessEnginePlugins(plugins);
        }
        if (plugins.stream().noneMatch(ProcessTestCoveragePlugin.class::isInstance)) {
            plugins.add(new ProcessTestCoveragePlugin());
        }
    }

    private void bindCollector() {
        Optional<ProcessTestCoveragePlugin> plugin = ((ProcessEngineConfigurationImpl)
                        processEngine.getProcessEngineConfiguration())
                .getProcessEnginePlugins().stream()
                        .filter(ProcessTestCoveragePlugin.class::isInstance)
                        .map(ProcessTestCoveragePlugin.class::cast)
                        .findFirst();
        ProcessEngine engine = processEngine;
        collector = plugin.orElseThrow(() -> new IllegalStateException("process engine '" + engine.getName()
                        + "' has no ProcessTestCoveragePlugin; register it in the engine configuration or let "
                        + "ProcessTestCoverageExtension create the engine"))
                .getCollector();
        collector.setExcludedProcessKeys(excludedKeys);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        super.beforeAll(context);
        if (collector == null) {
            bindCollector();
        }
        if (suite == null && !isExcluded(context)) {
            Class<?> testClass = context.getRequiredTestClass();
            suite = collector.startSuite(testClass.getName(), testClass.getSimpleName());
            suiteOwnerContextId = context.getUniqueId();
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (suite == null && !isExcluded(context)) {
            throw new IllegalStateException("ProcessTestCoverageExtension did not see beforeAll: register it in a "
                    + "static @RegisterExtension field or with @ExtendWith");
        }
        if (!isExcluded(context)) {
            collector.startRun(
                    context.getUniqueId(), context.getRequiredTestMethod().getName(), context.getDisplayName());
        }
        try {
            super.beforeEach(context);
        } catch (TestAbortedException e) {
            discardRun(context);
            throw e;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (collector == null || suite == null) {
            return; // beforeEach rejected the registration; the failure is already reported
        }
        try {
            checkMethodCoverage(context);
        } finally {
            collector.endRun();
            super.afterEach(context);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        try {
            if (suite != null && context.getUniqueId().equals(suiteOwnerContextId)) {
                finishSuite();
            }
        } finally {
            suite = null;
            suiteOwnerContextId = null;
            collector = null;
            super.afterAll(context);
        }
    }

    private boolean isExcluded(ExtensionContext context) {
        boolean classExcluded = context.getRequiredTestClass().isAnnotationPresent(ExcludeFromProcessCoverage.class);
        boolean methodExcluded = context.getTestMethod()
                .map(m -> m.isAnnotationPresent(ExcludeFromProcessCoverage.class))
                .orElse(false);
        return classExcluded || methodExcluded;
    }

    private void discardRun(ExtensionContext context) {
        TestRun run = collector.activeRun();
        collector.endRun();
        if (run != null && suite != null) {
            suite.removeRun(run.id());
        }
    }

    private void checkMethodCoverage(ExtensionContext context) {
        TestRun run = collector.activeRun();
        if (run == null) {
            return;
        }
        Optional<Throwable> failure = context.getExecutionException();
        if (failure.isPresent()) {
            if (failure.get() instanceof TestAbortedException) {
                suite.removeRun(run.id());
            }
            return; // the test failure is the message; do not hide it behind a coverage failure
        }
        Double minimum = methodMinimums.getOrDefault(run.name(), defaultMethodMinimum);
        if (minimum == null) {
            return;
        }
        CoverageResult result = CoverageCalculator.calculate(run.events(), collector.models());
        assertAtLeast("test method " + suite.id() + "#" + run.name(), result, minimum);
    }

    private void finishSuite() {
        TestSuite finished = collector.endSuite();
        CoverageResult result = CoverageCalculator.calculate(finished.events(), collector.models());
        if (result.isEmpty()) {
            LOG.warn("{}: no process was executed, there is no coverage to report", finished.id());
        } else {
            LOG.info(
                    "{} process test coverage: {}% ({} of {} elements)",
                    finished.id(),
                    String.format(Locale.ROOT, "%.1f", result.ratio() * 100),
                    result.covered(),
                    result.total());
        }
        if (writeReport && !result.isEmpty()) {
            Path directory = reportDirectory.resolve(finished.id());
            reportWriter.write(directory, finished, collector.models());
            LOG.info(
                    "process test coverage report: {}",
                    directory.resolve("index.html").toAbsolutePath());
        }
        if (classMinimum != null) {
            assertAtLeast("test class " + finished.id(), result, classMinimum);
        }
    }

    private static void assertAtLeast(String subject, CoverageResult result, double minimum) {
        if (result.isEmpty()) {
            throw new AssertionFailedError(subject + " must reach a process coverage of at least " + percent(minimum)
                    + " but executed no process element; is the process excluded or deployed without being started?");
        }
        if (result.ratio() + 1e-9 < minimum) {
            StringBuilder detail = new StringBuilder();
            result.models()
                    .forEach(m -> detail.append(String.format(
                            Locale.ROOT,
                            "%n  %s: %s (%d of %d elements)",
                            m.processKey(),
                            percent(m.ratio()),
                            m.covered(),
                            m.total())));
            throw new AssertionFailedError(subject + " has a process coverage of " + percent(result.ratio())
                    + " but at least " + percent(minimum) + " is required:" + detail);
        }
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    /** Builds a {@link ProcessTestCoverageExtension}. */
    public static final class Builder {

        private Double classMinimum;
        private Double defaultMethodMinimum;
        private final Map<String, Double> methodMinimums = new LinkedHashMap<>();
        private final Set<String> excludedKeys = new LinkedHashSet<>();
        private Path reportDirectory;
        private boolean writeReport = true;
        private String bpmnJsUrl = CoverageReportWriter.DEFAULT_BPMN_JS_URL;
        private String bpmnJsCssUrl = CoverageReportWriter.DEFAULT_BPMN_JS_CSS_URL;
        private String configurationResource;
        private boolean ensureCleanAfterTest;
        private Consumer<ProcessEngineConfigurationImpl> configurator;

        private Builder() {}

        /** Fails the test class when its coverage, over all its tests, is below {@code ratio} (0 to 1). */
        public Builder withMinimumCoverage(double ratio) {
            this.classMinimum = checkRatio(ratio, "minimum class coverage");
            return this;
        }

        /** Fails every test method whose own coverage is below {@code ratio}. */
        public Builder withMinimumMethodCoverage(double ratio) {
            this.defaultMethodMinimum = checkRatio(ratio, "minimum method coverage");
            return this;
        }

        /** Fails the test method {@code methodName} when its own coverage is below {@code ratio}. */
        public Builder withMinimumMethodCoverage(String methodName, double ratio) {
            this.methodMinimums.put(methodName, checkRatio(ratio, "minimum coverage of " + methodName));
            return this;
        }

        /** Process definition keys that are not recorded. */
        public Builder excludeProcessDefinitionKeys(String... keys) {
            this.excludedKeys.addAll(Arrays.asList(keys));
            return this;
        }

        /** The directory the per-class report folders are written to. */
        public Builder reportDirectory(Path directory) {
            this.reportDirectory = directory;
            return this;
        }

        /** Do not write the HTML and JSON report. */
        public Builder withoutReport() {
            this.writeReport = false;
            return this;
        }

        /** Where the HTML report loads bpmn-js and its stylesheet from, for example an internal mirror. */
        public Builder bpmnJs(String scriptUrl, String cssUrl) {
            this.bpmnJsUrl = scriptUrl;
            this.bpmnJsCssUrl = cssUrl;
            return this;
        }

        /** The engine configuration resource, like {@code ProcessEngineExtension#configurationResource}. */
        public Builder configurationResource(String resource) {
            this.configurationResource = resource;
            return this;
        }

        /** Adjusts the engine configuration before the engine is built. */
        public Builder configurator(Consumer<ProcessEngineConfigurationImpl> configurator) {
            this.configurator = configurator;
            return this;
        }

        /** Fails a test that leaves data in the database, like {@code ProcessEngineExtension}. */
        public Builder ensureCleanAfterTest(boolean value) {
            this.ensureCleanAfterTest = value;
            return this;
        }

        public ProcessTestCoverageExtension build() {
            return new ProcessTestCoverageExtension(this);
        }
    }
}
