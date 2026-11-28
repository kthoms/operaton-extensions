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

import { Client, EngineError, Variables, silentLogger } from "../../src/index.js";
import { BASE, fakeFetch, type Route } from "./helpers.js";

function setup(route: Route = () => ({})) {
  const f = fakeFetch(route);
  const client = new Client({
    baseUrl: BASE,
    workerId: "w1",
    fetch: f.fetch,
    logger: silentLogger,
  });
  return { ...f, client, ts: client.taskService };
}

describe("TaskService requests", () => {
  it("complete sends variables and local variables", async () => {
    const { ts, calls } = setup();
    await ts.complete("t 1", { a: 1, b: "x" }, Variables.from({ c: true }));
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ method: "POST", path: "/external-task/t%201/complete" });
    expect(calls[0]?.body).toEqual({
      workerId: "w1",
      variables: {
        a: { type: "Integer", value: 1, valueInfo: {} },
        b: { type: "String", value: "x", valueInfo: {} },
      },
      localVariables: { c: { type: "Boolean", value: true, valueInfo: {} } },
    });
  });

  it("complete without variables sends none", async () => {
    const { ts, calls } = setup();
    await ts.complete({ id: "t1" });
    expect(calls[0]?.body).toEqual({ workerId: "w1" });
  });

  it("handleFailure sends message, details, retries and retry timeout", async () => {
    const { ts, calls } = setup();
    await ts.handleFailure("t1", {
      errorMessage: "boom",
      errorDetails: "stack",
      retries: 2,
      retryTimeout: 1000,
    });
    expect(calls[0]).toMatchObject({ path: "/external-task/t1/failure" });
    expect(calls[0]?.body).toEqual({
      workerId: "w1",
      errorMessage: "boom",
      errorDetails: "stack",
      retries: 2,
      retryTimeout: 1000,
    });
  });

  it("handleFailure defaults retries and timeout to 0", async () => {
    const { ts, calls } = setup();
    await ts.handleFailure("t1", { errorMessage: "boom" });
    expect(calls[0]?.body).toEqual({
      workerId: "w1",
      errorMessage: "boom",
      retries: 0,
      retryTimeout: 0,
    });
  });

  it("handleBpmnError sends code, message and variables", async () => {
    const { ts, calls } = setup();
    await ts.handleBpmnError("t1", "OUT_OF_STOCK", "none left", { sku: "x" });
    expect(calls[0]).toMatchObject({ path: "/external-task/t1/bpmnError" });
    expect(calls[0]?.body).toEqual({
      workerId: "w1",
      errorCode: "OUT_OF_STOCK",
      errorMessage: "none left",
      variables: { sku: { type: "String", value: "x", valueInfo: {} } },
    });
  });

  it("extendLock and unlock", async () => {
    const { ts, calls } = setup();
    await ts.extendLock("t1", 5000);
    await ts.unlock("t1");
    expect(calls[0]).toMatchObject({ path: "/external-task/t1/extendLock" });
    expect(calls[0]?.body).toEqual({ workerId: "w1", newDuration: 5000 });
    expect(calls[1]).toMatchObject({ path: "/external-task/t1/unlock" });
  });

  it("setVariables targets the process instance, or the execution when local", async () => {
    const { ts, calls } = setup();
    const t = { id: "t1", processInstanceId: "pi 1", executionId: "ex1" };
    await ts.setVariables(t, { a: 1 });
    await ts.setVariables(t, { b: 2 }, { local: true });
    expect(calls[0]).toMatchObject({ path: "/process-instance/pi%201/variables" });
    expect(calls[0]?.body).toEqual({
      modifications: { a: { type: "Integer", value: 1, valueInfo: {} } },
    });
    expect(calls[1]).toMatchObject({ path: "/execution/ex1/localVariables" });
  });
});

describe("TaskService validation", () => {
  it("fails loudly on missing input without calling the engine", async () => {
    const { ts, calls } = setup();
    await expect(ts.complete(undefined as never)).rejects.toThrow(/Task or task id/);
    await expect(ts.handleBpmnError("t1", "")).rejects.toThrow(/error code/);
    await expect(ts.handleFailure("t1", { errorMessage: "" })).rejects.toThrow(/error message/);
    await expect(ts.extendLock("t1", 0)).rejects.toThrow(/duration/);
    await expect(ts.setVariables("t1", { a: 1 })).rejects.toThrow(/processInstanceId/);
    expect(calls).toHaveLength(0);
  });
});

describe("TaskService events and errors", () => {
  it("emits success and error events", async () => {
    const { client, ts } = setup((call) =>
      call.path.includes("/bad/")
        ? { status: 404, json: { type: "NotFoundException", message: "gone" } }
        : {},
    );
    const ok = vi.fn();
    const bad = vi.fn();
    client.on("complete:success", ok);
    client.on("complete:error", bad);
    await ts.complete("good");
    await expect(ts.complete("bad")).rejects.toBeInstanceOf(EngineError);
    expect(ok).toHaveBeenCalledWith("good");
    expect(bad).toHaveBeenCalledWith("bad", expect.any(EngineError));
  });

  it("maps engine errors with status, type and message", async () => {
    const { ts } = setup(() => ({
      status: 400,
      json: { type: "InvalidRequestException", message: "Cannot complete: wrong worker", code: 42 },
    }));
    const error: any = await ts.complete("t1").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(EngineError);
    expect(error).toMatchObject({ status: 400, type: "InvalidRequestException", code: 42 });
    expect(error.message).toContain("wrong worker");
    expect(error.message).toContain("/external-task/t1/complete");
  });

  it("keeps non-JSON error bodies as the message", async () => {
    const { ts } = setup(() => ({ status: 502, text: "Bad gateway" }));
    await expect(ts.unlock("t1")).rejects.toThrow(/502.*Bad gateway/);
  });

  it("wraps network failures with the request in the message and the cause attached", async () => {
    const cause = new TypeError("fetch failed");
    const client = new Client({
      baseUrl: BASE,
      fetch: () => Promise.reject(cause),
      logger: silentLogger,
    });
    const error: any = await client.taskService.unlock("t1").catch((e: unknown) => e);
    expect(error.message).toContain(
      "POST http://engine.test/engine-rest/external-task/t1/unlock failed",
    );
    expect(error.cause).toBe(cause);
  });
});
