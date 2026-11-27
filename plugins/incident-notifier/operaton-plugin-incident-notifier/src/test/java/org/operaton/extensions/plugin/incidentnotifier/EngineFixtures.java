/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.runtime.Job;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

/** Processes and helpers shared by the engine tests. */
final class EngineFixtures {

    private EngineFixtures() {}

    static String deployFailingJobProcess(ProcessEngine engine, String key) {
        BpmnModelInstance model = Bpmn.createExecutableProcess(key)
                .name("Payment " + key)
                .operatonHistoryTimeToLive(1)
                .startEvent()
                .serviceTask("charge")
                .name("Charge payment")
                .operatonAsyncBefore()
                .operatonFailedJobRetryTimeCycle("R1/PT0S")
                .operatonClass(FailingDelegate.class.getName())
                .endEvent()
                .done();
        return engine.getRepositoryService()
                .createDeployment()
                .addModelInstance(key + ".bpmn", model)
                .deploy()
                .getId();
    }

    static String deployExternalTaskProcess(ProcessEngine engine, String key) {
        BpmnModelInstance model = Bpmn.createExecutableProcess(key)
                .operatonHistoryTimeToLive(1)
                .startEvent()
                .serviceTask("shipOrder")
                .name("Ship order")
                .operatonExternalTask("shipping")
                .endEvent()
                .done();
        return engine.getRepositoryService()
                .createDeployment()
                .addModelInstance(key + ".bpmn", model)
                .deploy()
                .getId();
    }

    /** Starts the process and executes its job once, which fails and creates an incident. */
    static String startAndFail(ProcessEngine engine, String key, String businessKey, Map<String, Object> variables) {
        String instanceId = engine.getRuntimeService()
                .startProcessInstanceByKey(key, businessKey, variables)
                .getId();
        Job job = engine.getManagementService()
                .createJobQuery()
                .processInstanceId(instanceId)
                .singleResult();
        assertThatThrownBy(() -> engine.getManagementService().executeJob(job.getId()));
        return instanceId;
    }

    static void failExternalTask(ProcessEngine engine, String instanceId) {
        List<LockedExternalTask> tasks = engine.getExternalTaskService()
                .fetchAndLock(10, "worker")
                .topic("shipping", 10_000)
                .execute();
        for (LockedExternalTask task : tasks) {
            if (task.getProcessInstanceId().equals(instanceId)) {
                engine.getExternalTaskService()
                        .handleFailure(task.getId(), "worker", "carrier rejected", "details", 0, 0);
            }
        }
    }

    static void cleanUp(ProcessEngine engine) {
        engine.getRepositoryService()
                .createDeploymentQuery()
                .list()
                .forEach(d -> engine.getRepositoryService().deleteDeployment(d.getId(), true));
    }
}
