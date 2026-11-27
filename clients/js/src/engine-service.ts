/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda/camunda-external-task-client-js (Apache-2.0).
 * Modified for Operaton: rewritten in TypeScript on top of fetch; Copyright 2026 the Operaton
 * Extensions contributors.
 */
import { type RequestConfig, type RequestInterceptor } from "./auth.js";
import { ClientError, EngineError } from "./errors.js";
import type { ExternalTaskInfo, SubscriptionOptions } from "./types.js";
import type { WireValue } from "./variables.js";

export interface EngineServiceOptions {
  baseUrl: string;
  workerId: string;
  fetch: typeof fetch;
  interceptors: RequestInterceptor[];
  /** Timeout in ms for a request (fetch-and-lock adds its long-polling time). */
  requestTimeout: number;
}

export interface RequestOptions {
  json?: unknown;
  /** Extra time in ms on top of the request timeout (long polling). */
  extraTimeout?: number;
  signal?: AbortSignal;
}

export interface FetchTopic {
  topicName: string;
  lockDuration: number;
  variables?: string[];
  localVariables?: boolean;
  businessKey?: string;
  processDefinitionId?: string;
  processDefinitionIdIn?: string[];
  processDefinitionKey?: string;
  processDefinitionKeyIn?: string[];
  processDefinitionVersionTag?: string;
  processVariables?: SubscriptionOptions["processVariables"];
  tenantIdIn?: string[];
  withoutTenantId?: boolean;
  includeExtensionProperties?: boolean;
}

export interface FetchAndLockRequest {
  maxTasks: number;
  usePriority: boolean;
  asyncResponseTimeout?: number;
  sorting?: { sortBy: string; sortOrder: string }[];
  topics: FetchTopic[];
}

export type FetchedTask = ExternalTaskInfo & { variables?: Record<string, WireValue> };

/** The REST calls of the external task API, over `fetch`. */
export class EngineService {
  readonly workerId: string;
  readonly #baseUrl: string;
  readonly #fetch: typeof fetch;
  readonly #interceptors: RequestInterceptor[];
  readonly #requestTimeout: number;

  constructor(options: EngineServiceOptions) {
    this.workerId = options.workerId;
    this.#baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.#fetch = options.fetch;
    this.#interceptors = options.interceptors;
    this.#requestTimeout = options.requestTimeout;
  }

  /** Sends a request and returns the parsed JSON body (`undefined` for an empty body). */
  async request<T = unknown>(
    method: string,
    path: string,
    options: RequestOptions = {},
  ): Promise<T> {
    const response = await this.send(method, path, options);
    const text = await response.text();
    if (text === "") return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch (cause) {
      throw new ClientError(`${method} ${this.#baseUrl}${path}: the response is not JSON`, {
        cause,
      });
    }
  }

  /** Sends a request and returns the raw body. */
  async requestBytes(method: string, path: string): Promise<Uint8Array> {
    const response = await this.send(method, path, {});
    return new Uint8Array(await response.arrayBuffer());
  }

  private async send(method: string, path: string, options: RequestOptions): Promise<Response> {
    const describe = `${method} ${this.#baseUrl}${path}`;
    let request: RequestConfig = {
      url: `${this.#baseUrl}${path}`,
      method,
      headers: { Accept: "application/json" },
    };
    if (options.json !== undefined) {
      request.headers["Content-Type"] = "application/json";
      request.body = JSON.stringify(options.json);
    }
    for (const interceptor of this.#interceptors) {
      try {
        request = await interceptor(request);
      } catch (cause) {
        throw new ClientError(`${describe}: a request interceptor failed`, { cause });
      }
    }

    const timeout = AbortSignal.timeout(this.#requestTimeout + (options.extraTimeout ?? 0));
    const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout;
    let response: Response;
    try {
      response = await this.#fetch(request.url, {
        method: request.method,
        headers: request.headers,
        ...(request.body === undefined ? {} : { body: request.body }),
        signal,
      });
    } catch (cause) {
      if (options.signal?.aborted) throw cause;
      const reason = timeout.aborted ? "timed out" : "failed";
      throw new ClientError(`${describe} ${reason}`, { cause });
    }

    if (!response.ok) {
      throw await this.toEngineError(describe, response);
    }
    return response;
  }

  private async toEngineError(describe: string, response: Response): Promise<EngineError> {
    const text = await response.text().catch(() => "");
    let type: string | undefined;
    let code: number | undefined;
    let message = text || response.statusText;
    try {
      const body = JSON.parse(text) as { type?: string; message?: string; code?: number };
      type = body.type;
      code = body.code;
      message = body.message ?? message;
    } catch {
      // not JSON: keep the raw text as the message
    }
    return new EngineError(
      `${describe} -> ${response.status}${type ? ` ${type}` : ""}: ${message}`,
      response.status,
      type,
      code,
      describe,
    );
  }

  fetchAndLock(request: FetchAndLockRequest, signal: AbortSignal): Promise<FetchedTask[]> {
    return this.request<FetchedTask[]>("POST", "/external-task/fetchAndLock", {
      json: { ...request, workerId: this.workerId },
      extraTimeout: request.asyncResponseTimeout ?? 0,
      signal,
    });
  }

  complete(
    id: string,
    variables?: Record<string, WireValue>,
    localVariables?: Record<string, WireValue>,
  ): Promise<void> {
    return this.request("POST", `/external-task/${enc(id)}/complete`, {
      json: { workerId: this.workerId, variables, localVariables },
    });
  }

  handleFailure(
    id: string,
    failure: { errorMessage: string; errorDetails?: string; retries: number; retryTimeout: number },
  ): Promise<void> {
    return this.request("POST", `/external-task/${enc(id)}/failure`, {
      json: { ...failure, workerId: this.workerId },
    });
  }

  handleBpmnError(
    id: string,
    errorCode: string,
    errorMessage?: string,
    variables?: Record<string, WireValue>,
  ): Promise<void> {
    return this.request("POST", `/external-task/${enc(id)}/bpmnError`, {
      json: { workerId: this.workerId, errorCode, errorMessage, variables },
    });
  }

  extendLock(id: string, newDuration: number): Promise<void> {
    return this.request("POST", `/external-task/${enc(id)}/extendLock`, {
      json: { workerId: this.workerId, newDuration },
    });
  }

  unlock(id: string): Promise<void> {
    return this.request("POST", `/external-task/${enc(id)}/unlock`, {});
  }

  /** Sets variables on the process instance, or on the task's execution when `executionId` is given. */
  setVariables(
    target: { processInstanceId: string } | { executionId: string },
    modifications: Record<string, WireValue>,
  ): Promise<void> {
    const path =
      "executionId" in target
        ? `/execution/${enc(target.executionId)}/localVariables`
        : `/process-instance/${enc(target.processInstanceId)}/variables`;
    return this.request("POST", path, { json: { modifications } });
  }

  /** The content of a file variable of a process instance; falls back to the execution's local variable. */
  async fileContent(
    task: { processInstanceId: string; executionId: string },
    name: string,
  ): Promise<Uint8Array> {
    try {
      return await this.requestBytes(
        "GET",
        `/process-instance/${enc(task.processInstanceId)}/variables/${enc(name)}/data`,
      );
    } catch (error) {
      if (!(error instanceof EngineError) || error.status !== 404) throw error;
      return this.requestBytes(
        "GET",
        `/execution/${enc(task.executionId)}/localVariables/${enc(name)}/data`,
      );
    }
  }
}

function enc(segment: string): string {
  return encodeURIComponent(segment);
}
