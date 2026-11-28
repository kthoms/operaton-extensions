/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { randomUUID } from "node:crypto";

export const OPERATON_URL = (
  process.env.OPERATON_URL ?? "http://localhost:8080/engine-rest"
).replace(/\/+$/, "");

export async function rest<T = any>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${OPERATON_URL}${path}`, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path} -> ${response.status}: ${text}`);
  return (text ? JSON.parse(text) : undefined) as T;
}

/** A unique id fragment so tests never see each other's tasks. */
export function unique(prefix: string): string {
  return `${prefix}_${randomUUID().replace(/-/g, "").slice(0, 10)}`;
}

export interface Scenario {
  key: string;
  topic: string;
}

/**
 * Deploys `start -> external task (topic) -> end` and, with `errorCode`, a boundary error event
 * on the task leading to the end event `handled`.
 */
export async function deploy(
  name: string,
  options: { errorCode?: string } = {},
): Promise<Scenario> {
  const key = unique(name);
  const topic = unique(`${name}-topic`);
  const boundary = options.errorCode
    ? `<boundaryEvent id="catch" attachedToRef="work">
         <errorEventDefinition errorRef="err"/>
       </boundaryEvent>
       <sequenceFlow id="f3" sourceRef="catch" targetRef="handled"/>
       <endEvent id="handled"/>`
    : "";
  const error = options.errorCode ? `<error id="err" errorCode="${options.errorCode}"/>` : "";
  const bpmn = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
             targetNamespace="http://operaton.org/extensions/client-js">
  ${error}
  <process id="${key}" isExecutable="true" operaton:historyTimeToLive="1">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
    <serviceTask id="work" operaton:type="external" operaton:topic="${topic}"/>
    <sequenceFlow id="f2" sourceRef="work" targetRef="done"/>
    <endEvent id="done"/>
    ${boundary}
  </process>
</definitions>`;
  const form = new FormData();
  form.set("deployment-name", key);
  form.set("deployment-source", "client-js integration test");
  form.set("data", new Blob([bpmn], { type: "text/xml" }), `${key}.bpmn`);
  const response = await fetch(`${OPERATON_URL}/deployment/create`, { method: "POST", body: form });
  if (!response.ok)
    throw new Error(`deployment failed: ${response.status} ${await response.text()}`);
  return { key, topic };
}

export async function start(
  key: string,
  options: { variables?: Record<string, unknown>; businessKey?: string } = {},
): Promise<string> {
  const instance = await rest<{ id: string }>("POST", `/process-definition/key/${key}/start`, {
    variables: options.variables ?? {},
    ...(options.businessKey ? { businessKey: options.businessKey } : {}),
  });
  return instance.id;
}

export async function until<T>(
  what: string,
  probe: () => Promise<T | undefined | false>,
  timeout = 30_000,
): Promise<T> {
  const deadline = Date.now() + timeout;
  for (;;) {
    const result = await probe();
    if (result) return result;
    if (Date.now() > deadline) throw new Error(`timed out waiting for: ${what}`);
    await new Promise((r) => setTimeout(r, 100));
  }
}

export async function historyState(instanceId: string): Promise<string | undefined> {
  const [instance] = await rest<{ state: string }[]>(
    "GET",
    `/history/process-instance?processInstanceId=${instanceId}`,
  );
  return instance?.state;
}

export const waitForState = (instanceId: string, state: string) =>
  until(
    `instance ${instanceId} to be ${state}`,
    async () => (await historyState(instanceId)) === state,
  );

export async function historyVariables(instanceId: string): Promise<Record<string, any>> {
  const vars = await rest<any[]>(
    "GET",
    `/history/variable-instance?processInstanceId=${instanceId}&deserializeValues=false`,
  );
  return Object.fromEntries(vars.map((v) => [v.name, v]));
}

export async function finishedActivities(instanceId: string): Promise<string[]> {
  const acts = await rest<{ activityId: string }[]>(
    "GET",
    `/history/activity-instance?processInstanceId=${instanceId}&finished=true`,
  );
  return acts.map((a) => a.activityId);
}
