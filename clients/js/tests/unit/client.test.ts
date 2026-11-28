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
import { describe, expect, it, vi } from "vitest";

import {
  BpmnError,
  Client,
  ClientError,
  type ClientOptions,
  Variables,
  basicAuth,
  silentLogger,
} from "../../src/index.js";
import {
  BASE,
  type Call,
  type Reply,
  type Route,
  fakeFetch,
  task,
  untilAborted,
  waitFor,
} from "./helpers.js";

/** A route that hands out `queue` once for fetchAndLock, then long-polls forever. */
function engine(queue: unknown[][], rest: Route = () => ({})): Route {
  const batches = [...queue];
  return (call) => {
    if (call.path === "/external-task/fetchAndLock") {
      const next = batches.shift();
      return next ? { json: next } : untilAborted(call.signal);
    }
    return rest(call);
  };
}

function make(route: Route, options: Partial<ClientOptions> = {}) {
  const f = fakeFetch(route);
  const client = new Client({
    baseUrl: BASE,
    workerId: "w1",
    fetch: f.fetch,
    logger: silentLogger,
    interval: 1,
    ...options,
  });
  return { ...f, client };
}

const actions = (calls: Call[]) => calls.filter((c) => !c.path.endsWith("fetchAndLock"));

describe("configuration", () => {
  it("requires a base URL and valid numbers", () => {
    expect(() => new Client({ baseUrl: "" })).toThrow(/baseUrl/);
    expect(() => new Client({ baseUrl: BASE, maxTasks: 0 })).toThrow(ClientError);
    expect(() => new Client({ baseUrl: BASE, lockDuration: -1 })).toThrow(/lockDuration/);
    expect(() => new Client({ baseUrl: BASE, asyncResponseTimeout: -5 })).toThrow(ClientError);
  });

  it("uses a random worker id by default", () => {
    const a = new Client({ baseUrl: BASE });
    const b = new Client({ baseUrl: BASE });
    expect(a.workerId).toMatch(/^operaton-js-/);
    expect(a.workerId).not.toBe(b.workerId);
  });

  it("refuses a second handler for a topic and a missing handler", () => {
    const { client } = make(engine([]));
    client.subscribe("t", () => undefined);
    expect(() => client.subscribe("t", () => undefined)).toThrow(/already subscribed/);
    expect(() => client.subscribe("x", undefined as never)).toThrow(/handler/);
    expect(() => client.subscribe("", () => undefined)).toThrow(/topic/);
  });

  it("unsubscribe frees the topic and emits events", () => {
    const { client } = make(engine([]));
    const sub = vi.fn();
    const unsub = vi.fn();
    client.on("subscribe", sub);
    client.on("unsubscribe", unsub);
    const handle = client.subscribe("t", () => undefined);
    handle.unsubscribe();
    handle.unsubscribe();
    client.subscribe("t", () => undefined);
    expect(sub).toHaveBeenCalledTimes(2);
    expect(unsub).toHaveBeenCalledTimes(1);
  });
});

describe("fetch-and-lock request", () => {
  it("sends client settings and topic filters", async () => {
    const { client, calls } = make(engine([]), {
      maxTasks: 3,
      asyncResponseTimeout: 12345,
      lockDuration: 777,
      usePriority: false,
      sorting: [{ sortBy: "createTime", sortOrder: "desc" }],
    });
    client.subscribe("plain", () => undefined);
    client.subscribe("filtered", () => undefined, {
      lockDuration: 99,
      variables: ["a", "b"],
      localVariables: true,
      businessKey: "bk",
      processDefinitionKey: "pk",
      processDefinitionKeys: ["k1", "k2"],
      processDefinitionId: "pd:1",
      processDefinitionIds: ["pd:2"],
      processDefinitionVersionTag: "v1",
      processVariables: { region: "EU" },
      tenantId: "t1",
      tenantIds: ["t2"],
      includeExtensionProperties: true,
    });
    client.start();
    await waitFor(() => calls.length > 0);
    await client.stop();

    expect(calls[0]).toMatchObject({ method: "POST", url: `${BASE}/external-task/fetchAndLock` });
    expect(calls[0]?.body).toEqual({
      workerId: "w1",
      maxTasks: 3,
      usePriority: false,
      asyncResponseTimeout: 12345,
      sorting: [{ sortBy: "createTime", sortOrder: "desc" }],
      topics: [
        { topicName: "plain", lockDuration: 777 },
        {
          topicName: "filtered",
          lockDuration: 99,
          variables: ["a", "b"],
          localVariables: true,
          businessKey: "bk",
          processDefinitionKey: "pk",
          processDefinitionKeyIn: ["k1", "k2"],
          processDefinitionId: "pd:1",
          processDefinitionIdIn: ["pd:2"],
          processDefinitionVersionTag: "v1",
          processVariables: { region: "EU" },
          tenantIdIn: ["t2", "t1"],
          includeExtensionProperties: true,
        },
      ],
    });
  });

  it("omits asyncResponseTimeout when long polling is disabled", async () => {
    const { client, calls } = make(engine([]), { asyncResponseTimeout: 0 });
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => calls.length > 0);
    await client.stop();
    expect(calls[0]?.body).not.toHaveProperty("asyncResponseTimeout");
  });

  it("does not poll without subscriptions", async () => {
    const { client, calls } = make(engine([]));
    client.start();
    await new Promise((r) => setTimeout(r, 30));
    await client.stop();
    expect(calls).toHaveLength(0);
  });
});

describe("authentication", () => {
  it("sends basic credentials", async () => {
    const { client, calls } = make(engine([]), {
      auth: { type: "basic", username: "demo", password: "p:w" },
    });
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => calls.length > 0);
    await client.stop();
    expect(calls[0]?.headers.Authorization).toBe(
      `Basic ${Buffer.from("demo:p:w").toString("base64")}`,
    );
  });

  it("calls a bearer token function for every request", async () => {
    let n = 0;
    const { client, calls } = make(engine([[task({ topicName: "t" })]]), {
      auth: { type: "bearer", token: () => Promise.resolve(`tok${++n}`) },
    });
    client.subscribe("t", ({ taskService }) => taskService.complete());
    client.start();
    await waitFor(() => actions(calls).length > 0);
    await client.stop();
    expect(calls[0]?.headers.Authorization).toBe("Bearer tok1");
    expect(actions(calls)[0]?.headers.Authorization).toBe("Bearer tok2");
  });

  it("runs interceptors in order after auth, and may be async", async () => {
    const { client, calls } = make(engine([]), {
      auth: { type: "bearer", token: "abc" },
      interceptors: [
        (r) => ({ ...r, headers: { ...r.headers, "X-One": "1" } }),
        (r) =>
          Promise.resolve({ ...r, headers: { ...r.headers, "X-Two": r.headers["X-One"] ?? "?" } }),
        basicAuth("u", "p"),
      ],
    });
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => calls.length > 0);
    await client.stop();
    expect(calls[0]?.headers).toMatchObject({ "X-One": "1", "X-Two": "1" });
    expect(calls[0]?.headers.Authorization).toMatch(/^Basic /);
  });

  it("reports a failing interceptor as a poll error", async () => {
    const { client } = make(engine([]), {
      interceptors: [
        () => {
          throw new Error("no token");
        },
      ],
    });
    const errors: unknown[] = [];
    client.on("poll:error", (e) => errors.push(e));
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => errors.length > 0);
    await client.stop();
    expect(errors[0]).toBeInstanceOf(ClientError);
    expect((errors[0] as Error).message).toContain("interceptor");
  });
});

describe("running tasks", () => {
  it("passes typed task variables and completes through the bound task service", async () => {
    const t = task({
      topicName: "ship",
      variables: {
        orderId: { type: "String", value: "o-1", valueInfo: {} },
        items: { type: "Json", value: '[{"sku":"a"}]', valueInfo: {} },
      },
    });
    const { client, calls } = make(engine([[t]]));
    const seen: unknown[] = [];
    client.subscribe<{ orderId: string; items: { sku: string }[] }>(
      "ship",
      async ({ task, taskService }) => {
        seen.push(
          task.variables.get("orderId"),
          task.variables.get("items"),
          task.id,
          task.processInstanceId,
        );
        await taskService.complete({ shipped: true }, { note: "local" });
      },
    );
    const events: string[] = [];
    client.on("poll:success", () => events.push("poll:success"));
    client.on("complete:success", () => events.push("complete:success"));
    client.on("handler:success", () => events.push("handler:success"));
    client.start();
    await waitFor(() => events.includes("handler:success"));
    await client.stop();

    expect(seen).toEqual(["o-1", [{ sku: "a" }], "t1", "pi1"]);
    expect(events).toEqual(["poll:success", "complete:success", "handler:success"]);
    expect(actions(calls)[0]?.path).toBe("/external-task/t1/complete");
    expect(actions(calls)[0]?.body.variables.shipped.value).toBe(true);
    expect(actions(calls)[0]?.body.localVariables.note.value).toBe("local");
  });

  it("task variables are read-only", async () => {
    const { client, calls } = make(engine([[task({ topicName: "t" })]]), {
      maxAttempts: 2,
      retryTimeout: 0,
    });
    client.subscribe("t", ({ task }) => {
      task.variables.set("x", 1);
    });
    client.start();
    await waitFor(() => actions(calls).length > 0);
    await client.stop();
    expect(actions(calls)[0]?.path).toBe("/external-task/t1/failure");
    expect(actions(calls)[0]?.body.errorMessage).toMatch(/read-only/);
  });

  it("reports a throwing handler with message, details and counts retries down", async () => {
    const batch = [
      task({ id: "a", topicName: "t", retries: null }),
      task({ id: "b", topicName: "t", retries: 2 }),
      task({ id: "c", topicName: "t", retries: 1 }),
      task({ id: "d", topicName: "t", retries: 0 }),
    ];
    const { client, calls } = make(engine([batch]), { maxAttempts: 4, retryTimeout: 250 });
    const errors: unknown[] = [];
    client.on("handler:error", (e) => errors.push(e));
    client.subscribe("t", () => {
      throw new Error("boom");
    });
    client.start();
    await waitFor(() => actions(calls).length === 4);
    await client.stop();

    const byId = Object.fromEntries(actions(calls).map((c) => [c.path.split("/")[2], c.body]));
    expect(byId.a).toMatchObject({ errorMessage: "boom", retries: 3, retryTimeout: 250 });
    expect(byId.a.errorDetails).toContain("Error: boom");
    expect(byId.b.retries).toBe(1);
    expect(byId.c.retries).toBe(0);
    expect(byId.d.retries).toBe(0);
    expect(errors).toHaveLength(4);
  });

  it("handles a non-Error rejection", async () => {
    const { client, calls } = make(engine([[task({ topicName: "t" })]]));
    client.subscribe("t", () => Promise.reject("just a string"));
    client.start();
    await waitFor(() => actions(calls).length > 0);
    await client.stop();
    expect(actions(calls)[0]?.body.errorMessage).toBe("just a string");
  });

  it("turns a thrown BpmnError into a BPMN error", async () => {
    const { client, calls } = make(engine([[task({ topicName: "t" })]]));
    client.subscribe("t", () => {
      throw new BpmnError("NO_STOCK", "sold out", { sku: "a" });
    });
    client.start();
    await waitFor(() => actions(calls).length > 0);
    await client.stop();
    expect(actions(calls)[0]?.path).toBe("/external-task/t1/bpmnError");
    expect(actions(calls)[0]?.body).toMatchObject({
      errorCode: "NO_STOCK",
      errorMessage: "sold out",
    });
    expect(actions(calls)[0]?.body.variables.sku.value).toBe("a");
  });

  it("does not report a failure twice when the handler throws after ending the task", async () => {
    const { client, calls } = make(engine([[task({ topicName: "t" })]]));
    const errors: unknown[] = [];
    client.on("handler:error", (e) => errors.push(e));
    client.subscribe("t", async ({ taskService }) => {
      await taskService.complete();
      throw new Error("late");
    });
    client.start();
    await waitFor(() => errors.length > 0);
    await client.stop();
    expect(actions(calls).map((c) => c.path)).toEqual(["/external-task/t1/complete"]);
  });

  it("unlocks a task the handler left alone", async () => {
    const { client, calls } = make(engine([[task({ topicName: "t" })]]));
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => actions(calls).length > 0);
    await client.stop();
    expect(actions(calls).map((c) => c.path)).toEqual(["/external-task/t1/unlock"]);
  });

  it("does not drop the task when completing fails: the handler error becomes a failure", async () => {
    const { client, calls } = make(
      engine([[task({ topicName: "t" })]], (call) =>
        call.path.endsWith("/complete")
          ? { status: 400, json: { type: "X", message: "lock expired" } }
          : {},
      ),
    );
    client.subscribe("t", ({ taskService }) => taskService.complete());
    client.start();
    await waitFor(() => actions(calls).length >= 2);
    await client.stop();
    expect(actions(calls).map((c) => c.path)).toEqual([
      "/external-task/t1/complete",
      "/external-task/t1/failure",
    ]);
    expect(actions(calls)[1]?.body.errorMessage).toContain("lock expired");
  });

  it("unlocks tasks for a topic that was unsubscribed meanwhile", async () => {
    let release: (r: Reply) => void = () => undefined;
    const pending = new Promise<Reply>((resolve) => (release = resolve));
    const { client, calls } = make((call) =>
      call.path === "/external-task/fetchAndLock" ? pending : {},
    );
    const sub = client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => calls.length > 0);
    sub.unsubscribe();
    release({ json: [task({ topicName: "t" })] });
    await waitFor(() => actions(calls).length > 0);
    await client.stop();
    expect(actions(calls).map((c) => c.path)).toEqual(["/external-task/t1/unlock"]);
  });

  it("limits parallel handlers with maxParallelExecutions", async () => {
    const batch = [1, 2, 3].map((n) => task({ id: `t${n}`, topicName: "t" }));
    const { client, calls } = make(engine([batch.slice(0, 2), batch.slice(2)]), {
      maxParallelExecutions: 2,
    });
    const gates: (() => void)[] = [];
    let running = 0;
    let peak = 0;
    client.subscribe("t", async ({ taskService }) => {
      running++;
      peak = Math.max(peak, running);
      await new Promise<void>((resolve) => gates.push(resolve));
      running--;
      await taskService.complete();
    });
    client.start();
    await waitFor(() => gates.length === 2);
    await new Promise((r) => setTimeout(r, 30));
    // no further fetch while two handlers occupy both slots
    expect(calls.filter((c) => c.path.endsWith("fetchAndLock"))).toHaveLength(1);
    gates.shift()?.();
    await waitFor(() => gates.length === 2);
    gates.splice(0).forEach((g) => g());
    await waitFor(() => actions(calls).length === 3);
    await client.stop();
    expect(peak).toBeLessThanOrEqual(2);
  });
});

describe("failure handling of the poll loop", () => {
  it("backs off after errors and recovers", async () => {
    let fetches = 0;
    const times: number[] = [];
    const { client } = make(
      (call) => {
        if (call.path !== "/external-task/fetchAndLock") return {};
        times.push(Date.now());
        fetches++;
        if (fetches <= 3) return { status: 500, json: { message: "down" } };
        return untilAborted(call.signal);
      },
      { interval: 20, backoff: { factor: 2, maxDelay: 100 } },
    );
    const errors: unknown[] = [];
    client.on("poll:error", (e) => errors.push(e));
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => fetches === 4, 3000);
    await client.stop();
    expect(errors).toHaveLength(3);
    const gaps = [times[1]! - times[0]!, times[2]! - times[1]!, times[3]! - times[2]!];
    expect(gaps[0]).toBeGreaterThanOrEqual(35); // 20 * 2^1
    expect(gaps[1]).toBeGreaterThanOrEqual(75); // 20 * 2^2
    expect(gaps[2]).toBeGreaterThanOrEqual(95); // capped at 100
    expect(gaps[2]).toBeLessThan(400);
  });
});

describe("stop", () => {
  it("cancels a long poll and emits poll:stop", async () => {
    const { client, calls } = make(engine([]));
    const stopped = vi.fn();
    client.on("poll:stop", stopped);
    client.subscribe("t", () => undefined);
    client.start();
    await waitFor(() => calls.length > 0);
    await client.stop();
    expect(calls[0]?.signal?.aborted).toBe(true);
    expect(stopped).toHaveBeenCalledOnce();
    expect(client.isRunning).toBe(false);
  });

  it("waits for running handlers", async () => {
    const { client, calls } = make(engine([[task({ topicName: "t" })]]));
    let finish: () => void = () => undefined;
    let handlerStarted = false;
    client.subscribe("t", async ({ taskService }) => {
      handlerStarted = true;
      await new Promise<void>((resolve) => (finish = resolve));
      await taskService.complete();
    });
    client.start();
    await waitFor(() => handlerStarted);
    let stopped = false;
    const stopping = client.stop().then(() => (stopped = true));
    await new Promise((r) => setTimeout(r, 30));
    expect(stopped).toBe(false);
    expect(client.activeTasks).toBe(1);
    finish();
    await stopping;
    expect(actions(calls).map((c) => c.path)).toEqual(["/external-task/t1/complete"]);
    expect(client.activeTasks).toBe(0);
  });

  it("gives up waiting after the timeout but lets handlers finish", async () => {
    const { client } = make(engine([[task({ topicName: "t" })]]));
    let finish: () => void = () => undefined;
    let started = false;
    client.subscribe("t", async ({ taskService }) => {
      started = true;
      await new Promise<void>((resolve) => (finish = resolve));
      await taskService.complete();
    });
    client.start();
    await waitFor(() => started);
    await expect(client.stop({ timeout: 20 })).rejects.toThrow(/still running/);
    finish();
    await client.stop();
  });

  it("can be started again, is idempotent and unlocks tasks that arrive after stop", async () => {
    let release: (r: Reply) => void = () => undefined;
    const late = new Promise<Reply>((resolve) => (release = resolve));
    // a server that ignores the abort and answers anyway
    const route: Route = (call) => (call.path === "/external-task/fetchAndLock" ? late : {});
    const { client, calls } = make(route);
    const handler = vi.fn();
    client.subscribe("t", handler);
    client.start();
    client.start();
    await waitFor(() => calls.length > 0);
    const stopping = client.stop();
    release({ json: [task({ topicName: "t" })] });
    await stopping;
    expect(handler).not.toHaveBeenCalled();
    expect(actions(calls).map((c) => c.path)).toEqual(["/external-task/t1/unlock"]);
    await client.stop();
  });

  it("autoStart begins polling", async () => {
    const { client, calls } = make(engine([]), { autoStart: true });
    client.subscribe("t", () => undefined);
    await waitFor(() => calls.length > 0);
    await client.stop();
  });
});

describe("file variables", () => {
  it("loads file content through the engine", async () => {
    const t = task({
      topicName: "t",
      variables: { doc: { type: "File", value: null, valueInfo: { filename: "d.txt" } } },
    });
    const { client, calls } = make(
      engine([[t]], (call) => (call.path.endsWith("/data") ? { text: "file body" } : {})),
    );
    let content = "";
    client.subscribe("t", async ({ task, taskService }) => {
      const file = await (
        task.variables.get("doc") as import("../../src/index.js").OperatonFile
      ).load();
      content = new TextDecoder().decode(file.content);
      await taskService.complete(Variables.create());
    });
    client.start();
    await waitFor(() => content !== "");
    await client.stop();
    expect(content).toBe("file body");
    expect(calls.some((c) => c.path === "/process-instance/pi1/variables/doc/data")).toBe(true);
  });
});
