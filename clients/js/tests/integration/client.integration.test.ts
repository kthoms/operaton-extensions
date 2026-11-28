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
import { afterEach, describe, expect, it } from "vitest";

import {
  BpmnError,
  Client,
  EngineError,
  OperatonFile,
  Variables,
  silentLogger,
  typed,
} from "../../src/index.js";
import {
  OPERATON_URL,
  deploy,
  finishedActivities,
  historyVariables,
  rest,
  start,
  unique,
  until,
  waitForState,
} from "./engine.js";

const clients: Client[] = [];

function newClient(options: Partial<ConstructorParameters<typeof Client>[0]> = {}): Client {
  const client = new Client({
    baseUrl: OPERATON_URL,
    workerId: unique("it-worker"),
    asyncResponseTimeout: 5000,
    interval: 50,
    retryTimeout: 0,
    logger: silentLogger,
    ...options,
  });
  clients.push(client);
  return client;
}

afterEach(async () => {
  await Promise.all(clients.splice(0).map((c) => c.stop()));
});

describe("against a real engine", () => {
  it("processes a task end to end and completes with typed variables", async () => {
    const { key, topic } = await deploy("complete");
    const client = newClient();
    let seen: Record<string, unknown> = {};
    client.subscribe<{ orderId: string; quantity: number; meta: { express: boolean } }>(
      topic,
      async ({ task, taskService }) => {
        seen = {
          orderId: task.variables.get("orderId"),
          quantity: task.variables.get("quantity"),
          meta: task.variables.get("meta"),
          activityId: task.activityId,
          processDefinitionKey: task.processDefinitionKey,
          topicName: task.topicName,
        };
        await taskService.complete(
          Variables.from({
            shipped: true,
            count: 3,
            big: 3_000_000_000,
            ratio: 0.25,
            at: new Date("2024-05-06T07:08:09.000Z"),
            details: { carrier: "ACME", parcels: [1, 2] },
            nothing: null,
            forced: typed.long(7),
          }),
        );
      },
    );
    client.start();

    const instanceId = await start(key, {
      variables: {
        orderId: { value: "o-1", type: "String" },
        quantity: { value: 5, type: "Integer" },
        meta: { value: JSON.stringify({ express: true }), type: "Json" },
      },
    });
    await waitForState(instanceId, "COMPLETED");

    expect(seen).toEqual({
      orderId: "o-1",
      quantity: 5,
      meta: { express: true },
      activityId: "work",
      processDefinitionKey: key,
      topicName: topic,
    });
    const vars = await historyVariables(instanceId);
    expect(vars.shipped).toMatchObject({ type: "Boolean", value: true });
    expect(vars.count).toMatchObject({ type: "Integer", value: 3 });
    expect(vars.big).toMatchObject({ type: "Long", value: 3_000_000_000 });
    expect(vars.ratio).toMatchObject({ type: "Double", value: 0.25 });
    expect(vars.at).toMatchObject({ type: "Date" });
    expect(new Date(vars.at.value).toISOString()).toBe("2024-05-06T07:08:09.000Z");
    expect(vars.details.type).toBe("Json");
    expect(JSON.parse(vars.details.value)).toEqual({ carrier: "ACME", parcels: [1, 2] });
    expect(vars.nothing).toMatchObject({ type: "Null", value: null });
    expect(vars.forced).toMatchObject({ type: "Long", value: 7 });
  });

  it("long polls: a task created while the client waits is picked up", async () => {
    const { key, topic } = await deploy("longpoll");
    const client = newClient({ asyncResponseTimeout: 20_000 });
    client.subscribe(topic, ({ taskService }) => taskService.complete());
    client.start();
    await new Promise((r) => setTimeout(r, 1500)); // the poll is now parked in the engine
    const instanceId = await start(key);
    await waitForState(instanceId, "COMPLETED");
  });

  it("retries a failing handler and opens an incident when the retries are used up", async () => {
    const { key, topic } = await deploy("failure");
    const client = newClient({ maxAttempts: 3 });
    const attempts: (number | null)[] = [];
    client.subscribe(topic, ({ task }) => {
      attempts.push(task.retries);
      throw new Error("payment gateway down");
    });
    client.start();

    const instanceId = await start(key);
    const incident = await until("the incident", async () => {
      const incidents = await rest<any[]>("GET", `/incident?processInstanceId=${instanceId}`);
      return incidents[0];
    });
    await client.stop();

    expect(attempts).toEqual([null, 2, 1]);
    expect(incident.incidentType).toBe("failedExternalTask");
    expect(incident.incidentMessage).toContain("payment gateway down");
    const [externalTask] = await rest<any[]>(
      "GET",
      `/external-task?processInstanceId=${instanceId}`,
    );
    expect(externalTask.retries).toBe(0);
    expect(externalTask.errorMessage).toContain("payment gateway down");
    const details = await fetch(`${OPERATON_URL}/external-task/${externalTask.id}/errorDetails`);
    expect(await details.text()).toContain("Error: payment gateway down");
    expect(await historyStateOf(instanceId)).toBe("ACTIVE");
  });

  it("reports an explicit failure with its own retries", async () => {
    const { key, topic } = await deploy("explicitfail");
    const client = newClient();
    client.subscribe(topic, ({ taskService }) =>
      taskService.handleFailure({
        errorMessage: "try later",
        errorDetails: "details",
        retries: 5,
        retryTimeout: 600_000,
      }),
    );
    client.start();
    const instanceId = await start(key);
    const externalTask = await until("failed task", async () => {
      const [t] = await rest<any[]>("GET", `/external-task?processInstanceId=${instanceId}`);
      return t?.retries === 5 ? t : undefined;
    });
    expect(externalTask.errorMessage).toBe("try later");
    expect(externalTask.lockExpirationTime).not.toBeNull(); // retryTimeout keeps it locked for a while
  });

  it("routes a BPMN error to the error boundary event", async () => {
    const { key, topic } = await deploy("bpmnerror", { errorCode: "OUT_OF_STOCK" });
    const client = newClient();
    client.subscribe(topic, ({ taskService }) =>
      taskService.handleBpmnError("OUT_OF_STOCK", "nothing left", { sku: "A-1" }),
    );
    client.start();
    const instanceId = await start(key);
    await waitForState(instanceId, "COMPLETED");
    const activities = await finishedActivities(instanceId);
    expect(activities).toContain("handled");
    expect(activities).not.toContain("done");
    expect((await historyVariables(instanceId)).sku).toMatchObject({
      type: "String",
      value: "A-1",
    });
  });

  it("turns a thrown BpmnError into a BPMN error", async () => {
    const { key, topic } = await deploy("thrownerror", { errorCode: "E42" });
    const client = newClient();
    client.subscribe(topic, () => {
      throw new BpmnError("E42", "declined", { reason: "card" });
    });
    client.start();
    const instanceId = await start(key);
    await waitForState(instanceId, "COMPLETED");
    expect(await finishedActivities(instanceId)).toContain("handled");
  });

  it("extends the lock, sets variables mid-way and unlocks", async () => {
    const { key, topic } = await deploy("locks");
    const client = newClient();
    let lockBefore = "";
    let lockAfter = "";
    let unlocked = false;
    client.subscribe(
      topic,
      async ({ task, taskService }) => {
        if (!unlocked) {
          unlocked = true;
          await taskService.unlock();
          return;
        }
        lockBefore = task.lockExpirationTime ?? "";
        await taskService.extendLock(600_000);
        const [current] = await rest<any[]>("GET", `/external-task?externalTaskId=${task.id}`);
        lockAfter = current.lockExpirationTime;
        await taskService.setVariables({ progress: 50 });
        await taskService.setVariables({ stepLocal: "x" }, { local: true });
        await taskService.complete();
      },
      { lockDuration: 30_000 },
    );
    client.start();
    const instanceId = await start(key);
    await waitForState(instanceId, "COMPLETED");
    expect(new Date(lockAfter).getTime()).toBeGreaterThan(new Date(lockBefore).getTime() + 300_000);
    const vars = await historyVariables(instanceId);
    expect(vars.progress).toMatchObject({ type: "Integer", value: 50 });
    expect(vars.stepLocal).toMatchObject({ value: "x" });
  });

  it("filters by business key and fetches only the requested variables", async () => {
    const { key, topic } = await deploy("filters");
    const client = newClient();
    const seen: { businessKey: string | null; names: string[] }[] = [];
    client.subscribe(
      topic,
      async ({ task, taskService }) => {
        seen.push({ businessKey: task.businessKey, names: task.variables.names });
        await taskService.complete();
      },
      { businessKey: "wanted", variables: ["a"] },
    );
    client.start();
    const other = await start(key, { businessKey: "other", variables: { a: { value: 1 } } });
    const wanted = await start(key, {
      businessKey: "wanted",
      variables: { a: { value: 1 }, b: { value: 2 } },
    });
    await waitForState(wanted, "COMPLETED");
    expect(seen).toEqual([{ businessKey: "wanted", names: ["a"] }]);
    expect(await rest<any[]>("GET", `/external-task?processInstanceId=${other}`)).toHaveLength(1);
  });

  it("reads and writes file variables", async () => {
    const { key, topic } = await deploy("files");
    const client = newClient();
    client.subscribe(topic, async ({ task, taskService }) => {
      const file = await (task.variables.get("input") as OperatonFile).load();
      const text = Buffer.from(file.content).toString("utf-8");
      await taskService.complete({
        output: OperatonFile.fromBuffer(text.toUpperCase(), {
          filename: "out.txt",
          mimeType: "text/plain",
        }),
      });
    });
    client.start();
    const instanceId = await start(key, {
      variables: {
        input: {
          type: "File",
          value: Buffer.from("hello engine").toString("base64"),
          valueInfo: { filename: "in.txt", mimetype: "text/plain" },
        },
      },
    });
    await waitForState(instanceId, "COMPLETED");
    const vars = await historyVariables(instanceId);
    expect(vars.output.type).toBe("File");
    const [row] = await rest<{ id: string }[]>(
      "GET",
      `/history/variable-instance?variableName=output&processInstanceId=${instanceId}`,
    );
    const data = await fetch(`${OPERATON_URL}/history/variable-instance/${row!.id}/data`);
    expect(await data.text()).toBe("HELLO ENGINE");
  });

  it("stops gracefully: a running handler finishes and completes its task", async () => {
    const { key, topic } = await deploy("graceful");
    const client = newClient();
    let started = false;
    client.subscribe(topic, async ({ taskService }) => {
      started = true;
      await new Promise((r) => setTimeout(r, 1500));
      await taskService.complete({ finishedAfterStop: true });
    });
    client.start();
    const instanceId = await start(key);
    await until("handler start", async () => started);
    await client.stop();
    expect(client.activeTasks).toBe(0);
    await waitForState(instanceId, "COMPLETED");
    expect((await historyVariables(instanceId)).finishedAfterStop).toMatchObject({ value: true });
  });

  it("emits poll and handler events", async () => {
    const { key, topic } = await deploy("events");
    const client = newClient();
    const events: string[] = [];
    for (const name of [
      "subscribe",
      "poll:start",
      "poll:success",
      "complete:success",
      "handler:success",
    ] as const) {
      client.on(name, () => events.push(name));
    }
    client.subscribe(topic, ({ taskService }) => taskService.complete());
    client.start();
    await waitForState(await start(key), "COMPLETED");
    await until("handler success", async () => events.includes("handler:success"));
    expect(events).toEqual(
      expect.arrayContaining([
        "subscribe",
        "poll:start",
        "poll:success",
        "complete:success",
        "handler:success",
      ]),
    );
  });

  it("surfaces engine errors as EngineError", async () => {
    const client = newClient();
    const error = await client.taskService.complete("does-not-exist").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(EngineError);
    expect((error as EngineError).status).toBeGreaterThanOrEqual(400);
    expect((error as EngineError).message).toContain("does-not-exist");
  });
});

async function historyStateOf(instanceId: string): Promise<string | undefined> {
  const [instance] = await rest<{ state: string }[]>(
    "GET",
    `/history/process-instance?processInstanceId=${instanceId}`,
  );
  return instance?.state;
}
