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
import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";

import { type Auth, type RequestInterceptor, authInterceptor } from "./auth.js";
import {
  EngineService,
  type FetchAndLockRequest,
  type FetchTopic,
  type FetchedTask,
} from "./engine-service.js";
import {
  ALREADY_SUBSCRIBED,
  BpmnError,
  ClientError,
  MISSING_BASE_URL,
  MISSING_HANDLER,
} from "./errors.js";
import { type Logger, consoleLogger } from "./logger.js";
import { TaskService } from "./task-service.js";
import type {
  ClientEvents,
  ExternalTask,
  ExternalTaskInfo,
  Subscription,
  SubscriptionOptions,
  TaskHandler,
} from "./types.js";
import { type VariableMap, Variables } from "./variables.js";

export interface ClientOptions {
  /** Base URL of the engine's REST API, for example `http://localhost:8080/engine-rest`. */
  baseUrl: string;
  /** Identifies this worker in locks. Default: a random id per client. */
  workerId?: string;
  /** Maximum number of tasks per fetch-and-lock request. Default 10. */
  maxTasks?: number;
  /** Maximum number of handlers running at the same time. Default: no limit besides `maxTasks`. */
  maxParallelExecutions?: number;
  /** Long polling: how long the engine holds a fetch request open, in ms. Default 30000; 0 disables. */
  asyncResponseTimeout?: number;
  /** Default lock duration of a topic in ms. Default 50000. */
  lockDuration?: number;
  /** Pause between polls in ms (not applied right after a poll that returned tasks). Default 300. */
  interval?: number;
  /** Back-off after failed polls: the pause grows by `factor` per consecutive failure up to `maxDelay`. */
  backoff?: { factor?: number; maxDelay?: number };
  /** Fetch tasks by priority. Default true. */
  usePriority?: boolean;
  sorting?: { sortBy: "createTime"; sortOrder: "asc" | "desc" }[];
  /** Basic or bearer authentication. */
  auth?: Auth;
  /** Run in order on every request, after `auth`. */
  interceptors?: RequestInterceptor[];
  /** Default: a console logger at level `info`. Use `silentLogger` to log nothing. */
  logger?: Logger;
  /** Timeout of a single request in ms (long polling time comes on top for fetch-and-lock). Default 30000. */
  requestTimeout?: number;
  /**
   * When a handler throws, the task is reported as failed. The first failure sets the retries to
   * `maxAttempts - 1`, every further one decrements them; at 0 the engine opens an incident.
   * Default 3.
   */
  maxAttempts?: number;
  /** Milliseconds before a failed task is fetched again. Default 5000. */
  retryTimeout?: number;
  /** Start polling right away. Default false: call `start()`. */
  autoStart?: boolean;
  /** A `fetch` implementation. Default: the global one. */
  fetch?: typeof fetch;
}

interface SubscriptionEntry {
  topic: string;
  handler: TaskHandler<never>;
  options: SubscriptionOptions;
}

const DEFAULTS = {
  maxTasks: 10,
  asyncResponseTimeout: 30_000,
  lockDuration: 50_000,
  interval: 300,
  usePriority: true,
  requestTimeout: 30_000,
  maxAttempts: 3,
  retryTimeout: 5_000,
  backoffFactor: 2,
  backoffMaxDelay: 60_000,
};

function positive(name: string, value: number | undefined, fallback: number): number {
  const v = value ?? fallback;
  if (!Number.isFinite(v) || v <= 0) {
    throw new ClientError(`Option ${name} must be a positive number, got ${String(value)}`);
  }
  return v;
}

function nonNegative(name: string, value: number | undefined, fallback: number): number {
  const v = value ?? fallback;
  if (!Number.isFinite(v) || v < 0) {
    throw new ClientError(`Option ${name} must be zero or positive, got ${String(value)}`);
  }
  return v;
}

/**
 * An external task client: polls the engine with fetch-and-lock, runs the handler subscribed to
 * each task's topic and reports the result. Emits the events of {@link ClientEvents}.
 */
export class Client extends EventEmitter<ClientEvents> {
  /** The task actions, for use outside of a handler (task given by object or id). */
  readonly taskService: TaskService;
  readonly workerId: string;

  readonly #engine: EngineService;
  readonly #logger: Logger;
  readonly #subscriptions = new Map<string, SubscriptionEntry>();
  readonly #inFlight = new Set<Promise<void>>();
  readonly #config: Required<
    Pick<
      ClientOptions,
      | "maxTasks"
      | "asyncResponseTimeout"
      | "lockDuration"
      | "interval"
      | "usePriority"
      | "maxAttempts"
      | "retryTimeout"
    >
  > &
    Pick<ClientOptions, "maxParallelExecutions" | "sorting"> & {
      backoffFactor: number;
      backoffMaxDelay: number;
    };

  #running = false;
  #loop: Promise<void> | undefined;
  #stopping: Promise<void> | undefined;
  #pollAbort: AbortController | undefined;
  #wake: (() => void) | undefined;

  /** @throws ClientError for a missing base URL or an invalid option. */
  constructor(options: ClientOptions) {
    super();
    if (!options.baseUrl) throw new ClientError(MISSING_BASE_URL);
    this.workerId = options.workerId ?? `operaton-js-${randomUUID()}`;
    this.#logger = options.logger ?? consoleLogger();
    this.#config = {
      maxTasks: positive("maxTasks", options.maxTasks, DEFAULTS.maxTasks),
      asyncResponseTimeout: nonNegative(
        "asyncResponseTimeout",
        options.asyncResponseTimeout,
        DEFAULTS.asyncResponseTimeout,
      ),
      lockDuration: positive("lockDuration", options.lockDuration, DEFAULTS.lockDuration),
      interval: nonNegative("interval", options.interval, DEFAULTS.interval),
      usePriority: options.usePriority ?? DEFAULTS.usePriority,
      maxAttempts: positive("maxAttempts", options.maxAttempts, DEFAULTS.maxAttempts),
      retryTimeout: nonNegative("retryTimeout", options.retryTimeout, DEFAULTS.retryTimeout),
      backoffFactor: positive("backoff.factor", options.backoff?.factor, DEFAULTS.backoffFactor),
      backoffMaxDelay: positive(
        "backoff.maxDelay",
        options.backoff?.maxDelay,
        DEFAULTS.backoffMaxDelay,
      ),
      ...(options.maxParallelExecutions === undefined
        ? {}
        : {
            maxParallelExecutions: positive(
              "maxParallelExecutions",
              options.maxParallelExecutions,
              1,
            ),
          }),
      ...(options.sorting === undefined ? {} : { sorting: options.sorting }),
    };
    positive("requestTimeout", options.requestTimeout, DEFAULTS.requestTimeout);

    const interceptors = [
      ...(options.auth ? [authInterceptor(options.auth)] : []),
      ...(options.interceptors ?? []),
    ];
    this.#engine = new EngineService({
      baseUrl: options.baseUrl,
      workerId: this.workerId,
      fetch: options.fetch ?? globalThis.fetch.bind(globalThis),
      interceptors,
      requestTimeout: options.requestTimeout ?? DEFAULTS.requestTimeout,
    });
    this.taskService = new TaskService(this.#engine, (event, ...args) => {
      (this.emit as (event: string, ...args: unknown[]) => boolean)(event, ...args);
    });
    if (options.autoStart) this.start();
  }

  /**
   * Subscribes `handler` to the tasks of `topic`. Returns a handle to unsubscribe.
   * The handler must end the task with one of `complete`, `handleFailure`, `handleBpmnError`
   * or `unlock`. If it throws, the task is reported as failed (a thrown {@link BpmnError}
   * becomes a BPMN error); if it returns without ending the task, the task is unlocked.
   * @throws ClientError if the topic already has a handler.
   */
  subscribe<V extends VariableMap = VariableMap>(
    topic: string,
    handler: TaskHandler<V>,
    options: SubscriptionOptions = {},
  ): Subscription {
    if (!topic) throw new ClientError("Couldn't subscribe: a topic name is required");
    if (typeof handler !== "function") throw new ClientError(MISSING_HANDLER);
    if (this.#subscriptions.has(topic)) throw new ClientError(`${ALREADY_SUBSCRIBED}: ${topic}`);
    if (options.lockDuration !== undefined) positive("lockDuration", options.lockDuration, 1);
    const entry: SubscriptionEntry = { topic, handler, options };
    this.#subscriptions.set(topic, entry);
    this.emit("subscribe", topic);
    return {
      topic,
      unsubscribe: () => {
        if (this.#subscriptions.get(topic) === entry) {
          this.#subscriptions.delete(topic);
          this.emit("unsubscribe", topic);
        }
      },
    };
  }

  get isRunning(): boolean {
    return this.#running;
  }

  /** Number of handlers running right now. */
  get activeTasks(): number {
    return this.#inFlight.size;
  }

  /** Starts polling. Does nothing when already started. */
  start(): void {
    if (this.#running) return;
    if (this.#stopping) {
      throw new ClientError("The client is still stopping; await stop() before start()");
    }
    this.#running = true;
    this.#loop = this.#pollLoop();
  }

  /**
   * Stops polling and waits for running handlers to finish. A long poll in progress is cancelled;
   * tasks the engine locked at that very moment are released when their lock expires.
   * @param options.timeout give up waiting for handlers after this many ms (rejects with ClientError;
   *   handlers keep running).
   */
  stop(options: { timeout?: number } = {}): Promise<void> {
    const stopping = (this.#stopping ??= this.#doStop());
    if (options.timeout === undefined) return stopping;
    const timeout = options.timeout;
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(
          new ClientError(
            `stop(): ${this.#inFlight.size} handler(s) still running after ${timeout} ms`,
          ),
        );
      }, timeout);
      stopping.then(resolve, reject).finally(() => {
        clearTimeout(timer);
      });
    });
  }

  async #doStop(): Promise<void> {
    this.#running = false;
    this.#pollAbort?.abort();
    this.#wake?.();
    try {
      await this.#loop;
      while (this.#inFlight.size > 0) {
        await Promise.allSettled([...this.#inFlight]);
      }
    } finally {
      this.#loop = undefined;
      this.#stopping = undefined;
    }
  }

  async #pollLoop(): Promise<void> {
    let failures = 0;
    const { interval, backoffFactor, backoffMaxDelay } = this.#config;
    while (this.#active()) {
      let delay = interval;
      try {
        this.emit("poll:start");
        const received = await this.#pollOnce();
        failures = 0;
        if (received > 0) delay = 0;
      } catch (error) {
        if (!this.#active()) break;
        failures++;
        delay = Math.min(Math.max(interval, 1) * backoffFactor ** failures, backoffMaxDelay);
        this.#logger.error(`poll failed (${failures} in a row), next poll in ${delay} ms`, error);
        this.emit("poll:error", error);
      }
      if (this.#active() && delay > 0) await this.#sleep(delay);
    }
    this.emit("poll:stop");
  }

  #active(): boolean {
    return this.#running;
  }

  #sleep(ms: number): Promise<void> {
    return new Promise<void>((resolve) => {
      const done = () => {
        clearTimeout(timer);
        this.#wake = undefined;
        resolve();
      };
      const timer = setTimeout(done, ms);
      this.#wake = done;
    });
  }

  /** One fetch-and-lock round; returns the number of tasks received (0 when it did not fetch). */
  async #pollOnce(): Promise<number> {
    const { maxTasks, maxParallelExecutions } = this.#config;
    const free =
      maxParallelExecutions === undefined ? maxTasks : maxParallelExecutions - this.#inFlight.size;
    const count = Math.min(maxTasks, free);
    if (count <= 0 || this.#subscriptions.size === 0) return 0;

    const { usePriority, asyncResponseTimeout, sorting } = this.#config;
    const request: FetchAndLockRequest = {
      maxTasks: count,
      usePriority,
      ...(asyncResponseTimeout > 0 ? { asyncResponseTimeout } : {}),
      ...(sorting ? { sorting } : {}),
      topics: [...this.#subscriptions.values()].map((s) => this.#topic(s)),
    };

    this.#pollAbort = new AbortController();
    let tasks: FetchedTask[];
    try {
      tasks = await this.#engine.fetchAndLock(request, this.#pollAbort.signal);
    } finally {
      this.#pollAbort = undefined;
    }
    this.emit("poll:success", tasks);
    for (const task of tasks) this.#dispatch(task);
    return tasks.length;
  }

  #topic(entry: SubscriptionEntry): FetchTopic {
    const o = entry.options;
    const topic: FetchTopic = {
      topicName: entry.topic,
      lockDuration: o.lockDuration ?? this.#config.lockDuration,
    };
    if (o.variables !== undefined) topic.variables = o.variables;
    if (o.localVariables !== undefined) topic.localVariables = o.localVariables;
    if (o.businessKey !== undefined) topic.businessKey = o.businessKey;
    if (o.processDefinitionId !== undefined) topic.processDefinitionId = o.processDefinitionId;
    if (o.processDefinitionIds !== undefined) topic.processDefinitionIdIn = o.processDefinitionIds;
    if (o.processDefinitionKey !== undefined) topic.processDefinitionKey = o.processDefinitionKey;
    if (o.processDefinitionKeys !== undefined)
      topic.processDefinitionKeyIn = o.processDefinitionKeys;
    if (o.processDefinitionVersionTag !== undefined)
      topic.processDefinitionVersionTag = o.processDefinitionVersionTag;
    if (o.processVariables !== undefined) topic.processVariables = o.processVariables;
    if (o.tenantIds !== undefined || o.tenantId !== undefined)
      topic.tenantIdIn = [
        ...(o.tenantIds ?? []),
        ...(o.tenantId === undefined ? [] : [o.tenantId]),
      ];
    if (o.withoutTenantId !== undefined) topic.withoutTenantId = o.withoutTenantId;
    if (o.includeExtensionProperties !== undefined)
      topic.includeExtensionProperties = o.includeExtensionProperties;
    return topic;
  }

  #dispatch(task: FetchedTask): void {
    const entry = this.#subscriptions.get(task.topicName);
    let work: Promise<void>;
    if (!this.isRunning || !entry) {
      // Stopped or unsubscribed between the request and its answer: give the lock back.
      const why = entry
        ? "the client is stopping"
        : `topic '${task.topicName}' is no longer subscribed`;
      work = this.#release(task, why);
    } else {
      work = this.#execute(task, entry);
    }
    const tracked = work.finally(() => {
      this.#inFlight.delete(tracked);
    });
    this.#inFlight.add(tracked);
  }

  async #release(task: ExternalTaskInfo, why: string): Promise<void> {
    this.#logger.warn(`releasing task ${task.id}: ${why}`);
    try {
      await this.taskService.unlock(task);
    } catch (error) {
      this.#logger.error(`could not unlock task ${task.id}; its lock expires on its own`, error);
    }
  }

  async #execute(fetched: FetchedTask, entry: SubscriptionEntry): Promise<void> {
    const { variables: wire, ...info } = fetched;
    const state = { ended: false };
    const variables = new Variables(wire ?? {}, {
      readOnly: true,
      fileLoader: (name) => this.#engine.fileContent(info, name),
    });
    const task: ExternalTask = { ...info, variables };
    const taskService = this.taskService.bind(info, () => {
      state.ended = true;
    });

    try {
      await entry.handler({ task, taskService } as never);
    } catch (error) {
      if (error instanceof BpmnError) {
        this.#logger.info(
          `handler for topic '${entry.topic}' raised BPMN error ${error.code} on task ${info.id}`,
        );
      } else {
        this.#logger.error(`handler for topic '${entry.topic}' failed on task ${info.id}`, error);
      }
      this.emit("handler:error", error, info);
      if (state.ended) return; // the task was already ended; nothing left to report
      try {
        if (error instanceof BpmnError) {
          await taskService.handleBpmnError(error.code, error.message, error.variables);
        } else {
          await taskService.handleFailure(this.#failure(info, error));
        }
      } catch (reportError) {
        this.#logger.error(
          `could not report the failure of task ${info.id}; its lock expires on its own`,
          reportError,
        );
      }
      return;
    }

    if (!state.ended) {
      this.#logger.warn(
        `handler for topic '${entry.topic}' returned without ending task ${info.id}; unlocking it`,
      );
      await this.#release(info, "the handler did not complete, fail or unlock it");
    }
    this.emit("handler:success", info);
  }

  #failure(task: ExternalTaskInfo, error: unknown) {
    const err = error instanceof Error ? error : new Error(String(error));
    const { maxAttempts, retryTimeout } = this.#config;
    const retries = task.retries === null ? maxAttempts - 1 : task.retries - 1;
    return {
      errorMessage: err.message || err.name,
      errorDetails: err.stack ?? err.message,
      retries: Math.max(retries, 0),
      retryTimeout,
    };
  }
}
