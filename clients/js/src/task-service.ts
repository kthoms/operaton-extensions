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
import {
  ClientError,
  MISSING_DURATION,
  MISSING_ERROR_CODE,
  MISSING_ERROR_MESSAGE,
  MISSING_TASK,
} from "./errors.js";
import type { EngineService } from "./engine-service.js";
import type { BoundTaskService, ClientEvents, TaskAction, TaskRef } from "./types.js";
import { toWireVariables, type VariablesInput } from "./variables.js";

export interface FailureOptions {
  errorMessage: string;
  /** Usually the stack trace. */
  errorDetails?: string;
  /** Retries left after this failure; the task becomes an incident at 0. Default 0. */
  retries?: number;
  /** Milliseconds before the task can be fetched again. Default 0. */
  retryTimeout?: number;
}

type Emit = <E extends keyof ClientEvents>(event: E, ...args: ClientEvents[E]) => void;

function taskId(task: TaskRef | undefined | null): string {
  const id = typeof task === "string" ? task : task?.id;
  if (!id) throw new ClientError(MISSING_TASK);
  return id;
}

/** The actions on a locked external task, addressed by task (or task id). */
export class TaskService {
  readonly #engine: EngineService;
  readonly #emit: Emit;

  constructor(engine: EngineService, emit: Emit) {
    this.#engine = engine;
    this.#emit = emit;
  }

  /** Completes the task, optionally with process variables and variables local to the execution. */
  complete(
    task: TaskRef,
    variables?: VariablesInput,
    localVariables?: VariablesInput,
  ): Promise<void> {
    return this.#run("complete", task, (id) =>
      this.#engine.complete(id, toWireVariables(variables), toWireVariables(localVariables)),
    );
  }

  /** Reports a technical failure; with `retries` 0 the engine creates an incident. */
  handleFailure(task: TaskRef, options: FailureOptions): Promise<void> {
    return this.#run("handleFailure", task, (id) => {
      if (!options.errorMessage) throw new ClientError(MISSING_ERROR_MESSAGE);
      return this.#engine.handleFailure(id, {
        errorMessage: options.errorMessage,
        ...(options.errorDetails === undefined ? {} : { errorDetails: options.errorDetails }),
        retries: options.retries ?? 0,
        retryTimeout: options.retryTimeout ?? 0,
      });
    });
  }

  /** Reports a BPMN error, caught by an error boundary or event sub process with a matching code. */
  handleBpmnError(
    task: TaskRef,
    errorCode: string,
    errorMessage?: string,
    variables?: VariablesInput,
  ): Promise<void> {
    return this.#run("handleBpmnError", task, (id) => {
      if (!errorCode) throw new ClientError(MISSING_ERROR_CODE);
      return this.#engine.handleBpmnError(id, errorCode, errorMessage, toWireVariables(variables));
    });
  }

  /** Sets the lock to `newDuration` ms from now. */
  extendLock(task: TaskRef, newDuration: number): Promise<void> {
    return this.#run("extendLock", task, (id) => {
      if (!(newDuration > 0)) throw new ClientError(MISSING_DURATION);
      return this.#engine.extendLock(id, newDuration);
    });
  }

  /** Releases the lock without completing, so any worker can fetch the task again. */
  unlock(task: TaskRef): Promise<void> {
    return this.#run("unlock", task, (id) => this.#engine.unlock(id));
  }

  /**
   * Sets variables on the task's process instance, or with `local: true` on its execution.
   * The task must be given as an object with `processInstanceId` / `executionId`.
   */
  setVariables(
    task: TaskRef & { processInstanceId?: string; executionId?: string },
    variables: VariablesInput,
    options: { local?: boolean } = {},
  ): Promise<void> {
    return this.#run("setVariables", task, () => {
      const target = options.local ? task.executionId : task.processInstanceId;
      if (typeof task === "string" || !target) {
        throw new ClientError(
          `setVariables needs the task object with ${options.local ? "executionId" : "processInstanceId"}, not just its id`,
        );
      }
      const wire = toWireVariables(variables) ?? {};
      return this.#engine.setVariables(
        options.local ? { executionId: target } : { processInstanceId: target },
        wire,
      );
    });
  }

  /** A view of this service with the task fixed; `onDone` is called after a call that ends the task. */
  bind(
    task: TaskRef & { processInstanceId?: string; executionId?: string },
    onDone?: () => void,
  ): BoundTaskService {
    const done = <A extends unknown[]>(call: (...args: A) => Promise<void>) => {
      return async (...args: A) => {
        await call(...args);
        onDone?.();
      };
    };
    return {
      complete: done((v, l) => this.complete(task, v, l)),
      handleFailure: done((o) => this.handleFailure(task, o)),
      handleBpmnError: done((c, m, v) => this.handleBpmnError(task, c, m, v)),
      unlock: done(() => this.unlock(task)),
      extendLock: (d) => this.extendLock(task, d),
      setVariables: (v, o) => this.setVariables(task, v, o),
    };
  }

  async #run(
    action: TaskAction,
    task: TaskRef | undefined | null,
    call: (id: string) => Promise<void>,
  ): Promise<void> {
    const id = taskId(task);
    try {
      await call(id);
    } catch (error) {
      this.#emit(`${action}:error`, id, error);
      throw error;
    }
    this.#emit(`${action}:success`, id);
  }
}
