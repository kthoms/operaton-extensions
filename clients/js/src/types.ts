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
import type { Variables, VariableMap } from "./variables.js";
import type { TaskService } from "./task-service.js";

/** An external task as the engine reports it, without its variables. */
export interface ExternalTaskInfo {
  id: string;
  topicName: string;
  workerId: string;
  /** Number of retries left; `null` until the first failure was reported. */
  retries: number | null;
  priority: number;
  lockExpirationTime: string | null;
  activityId: string;
  activityInstanceId: string;
  executionId: string;
  processDefinitionId: string;
  processDefinitionKey: string;
  processDefinitionVersionTag: string | null;
  processInstanceId: string;
  tenantId: string | null;
  businessKey: string | null;
  errorMessage: string | null;
  errorDetails?: string | null;
  suspended: boolean;
  createTime?: string;
  /** Extension properties of the BPMN element; only fetched with `includeExtensionProperties`. */
  extensionProperties?: Record<string, string>;
}

/** The task a handler receives: the engine's data plus read-only, typed variables. */
export interface ExternalTask<V extends VariableMap = VariableMap> extends ExternalTaskInfo {
  variables: Variables<V>;
}

/** What `complete`, `handleFailure` and the other task actions accept to identify a task. */
export type TaskRef = string | { id: string };

/** The task and a task service bound to it: what a handler is called with. */
export interface TaskContext<V extends VariableMap = VariableMap> {
  task: ExternalTask<V>;
  taskService: BoundTaskService;
}

export type TaskHandler<V extends VariableMap = VariableMap> = (
  context: TaskContext<V>,
) => void | Promise<void>;

/** {@link TaskService} with the task fixed. A handler must end with exactly one of the first four. */
export interface BoundTaskService {
  complete: (...args: DropFirst<Parameters<TaskService["complete"]>>) => Promise<void>;
  handleFailure: (...args: DropFirst<Parameters<TaskService["handleFailure"]>>) => Promise<void>;
  handleBpmnError: (
    ...args: DropFirst<Parameters<TaskService["handleBpmnError"]>>
  ) => Promise<void>;
  unlock: () => Promise<void>;
  extendLock: (...args: DropFirst<Parameters<TaskService["extendLock"]>>) => Promise<void>;
  setVariables: (...args: DropFirst<Parameters<TaskService["setVariables"]>>) => Promise<void>;
}

type DropFirst<T extends unknown[]> = T extends [unknown, ...infer R] ? R : never;

export interface SubscriptionOptions {
  /** Lock duration in ms for this topic; defaults to the client's `lockDuration`. */
  lockDuration?: number;
  /** Names of the variables to fetch; all variables when omitted. */
  variables?: string[];
  /** Also fetch local variables of the task's execution. */
  localVariables?: boolean;
  businessKey?: string;
  processDefinitionId?: string;
  processDefinitionIds?: string[];
  processDefinitionKey?: string;
  processDefinitionKeys?: string[];
  processDefinitionVersionTag?: string;
  /** Only tasks of instances having these process variable values. */
  processVariables?: Record<string, string | number | boolean>;
  tenantId?: string;
  tenantIds?: string[];
  withoutTenantId?: boolean;
  includeExtensionProperties?: boolean;
}

/** The handle `subscribe` returns. */
export interface Subscription {
  readonly topic: string;
  /** Stops fetching tasks for the topic; handlers already running are not interrupted. */
  unsubscribe(): void;
}

export type TaskAction =
  "complete" | "handleFailure" | "handleBpmnError" | "extendLock" | "unlock" | "setVariables";

type TaskActionEvents = {
  [A in TaskAction as `${A}:success`]: [taskId: string];
} & {
  [A in TaskAction as `${A}:error`]: [taskId: string, error: unknown];
};

export type ClientEvents = TaskActionEvents & {
  subscribe: [topic: string];
  unsubscribe: [topic: string];
  "poll:start": [];
  "poll:success": [tasks: ExternalTaskInfo[]];
  "poll:error": [error: unknown];
  "poll:stop": [];
  "handler:success": [task: ExternalTaskInfo];
  "handler:error": [error: unknown, task: ExternalTaskInfo];
};
