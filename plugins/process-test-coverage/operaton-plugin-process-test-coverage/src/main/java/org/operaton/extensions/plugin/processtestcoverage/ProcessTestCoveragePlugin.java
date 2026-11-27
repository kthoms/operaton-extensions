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

import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.event.EventHandler;
import org.operaton.extensions.plugin.processtestcoverage.internal.CompensationCoverageHandler;
import org.operaton.extensions.plugin.processtestcoverage.internal.CoverageParseListener;

/**
 * Process engine plugin that records which flow nodes and sequence flows of the deployed processes
 * the engine executes.
 *
 * <p>The plugin only records. Which test a recording belongs to, and the assertions and reports, are
 * handled by {@link ProcessTestCoverageExtension}, which installs this plugin itself. Register the
 * plugin by hand (for example in {@code operaton.cfg.xml}) when the engine is created by something
 * else; events are dropped while no test run is active, so it is harmless on an engine that is also
 * used outside tests.
 *
 * <p>One instance belongs to one engine: it holds that engine's {@link CoverageCollector}. It is
 * thread-safe once the engine runs.
 */
public class ProcessTestCoveragePlugin extends AbstractProcessEnginePlugin {

    private final CoverageCollector collector = new CoverageCollector();

    /** The collector that receives the events of the engine this plugin is registered in. */
    public CoverageCollector getCollector() {
        return collector;
    }

    /**
     * Process definition keys that are never recorded, for example helper processes that tests do not
     * aim to cover. Property {@code excludedProcessDefinitionKeys} of the plugin bean.
     */
    public void setExcludedProcessDefinitionKeys(List<String> keys) {
        collector.setExcludedProcessKeys(keys);
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        List<BpmnParseListener> postListeners = configuration.getCustomPostBPMNParseListeners();
        if (postListeners == null) {
            postListeners = new ArrayList<>();
            configuration.setCustomPostBPMNParseListeners(postListeners);
        }
        postListeners.add(new CoverageParseListener(collector));

        List<EventHandler> eventHandlers = configuration.getCustomEventHandlers();
        if (eventHandlers == null) {
            eventHandlers = new ArrayList<>();
            configuration.setCustomEventHandlers(eventHandlers);
        }
        eventHandlers.add(new CompensationCoverageHandler(collector));
    }
}
