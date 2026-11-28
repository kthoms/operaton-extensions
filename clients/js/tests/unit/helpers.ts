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
export interface Call {
  method: string;
  url: string;
  path: string;
  headers: Record<string, string>;
  body: any;
  signal: AbortSignal | undefined;
}

export type Reply = {
  status?: number;
  json?: unknown;
  text?: string;
  headers?: Record<string, string>;
};

export type Route = (call: Call) => Reply | Promise<Reply>;

export const BASE = "http://engine.test/engine-rest";

/** A fetch whose answers come from `route`; every request is recorded in `calls`. */
export function fakeFetch(route: Route) {
  const calls: Call[] = [];
  const fn = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const call: Call = {
      method: init?.method ?? "GET",
      url,
      path: url.slice(BASE.length),
      headers: { ...(init?.headers as Record<string, string> | undefined) },
      body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
      signal: init?.signal ?? undefined,
    };
    calls.push(call);
    const reply = await route(call);
    const status =
      reply.status ?? (reply.json === undefined && reply.text === undefined ? 204 : 200);
    const body = reply.text ?? (reply.json === undefined ? null : JSON.stringify(reply.json));
    return new Response(status === 204 ? null : body, {
      status,
      headers: { "content-type": "application/json", ...reply.headers },
    });
  };
  return { fetch: fn, calls };
}

/** Resolves with the abort reason when `signal` aborts (to simulate a long poll). */
export function untilAborted(signal: AbortSignal | undefined): Promise<never> {
  return new Promise((_resolve, reject) => {
    signal?.addEventListener("abort", () => {
      reject(signal.reason as Error);
    });
  });
}

export function task(overrides: Record<string, unknown> = {}) {
  return {
    id: "t1",
    topicName: "topic",
    workerId: "w",
    retries: null,
    priority: 0,
    lockExpirationTime: null,
    activityId: "a",
    activityInstanceId: "ai",
    executionId: "ex1",
    processDefinitionId: "pd:1",
    processDefinitionKey: "pd",
    processDefinitionVersionTag: null,
    processInstanceId: "pi1",
    tenantId: null,
    businessKey: null,
    errorMessage: null,
    suspended: false,
    variables: {},
    ...overrides,
  };
}

export async function waitFor(condition: () => boolean, timeout = 2000): Promise<void> {
  const deadline = Date.now() + timeout;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("waitFor: condition not met in time");
    await new Promise((r) => setTimeout(r, 5));
  }
}
