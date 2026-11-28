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
export { Client, type ClientOptions } from "./client.js";
export { TaskService, type FailureOptions } from "./task-service.js";
export {
  Variables,
  TypedValue,
  typed,
  toWireVariables,
  type VariableMap,
  type VariableType,
  type VariablesInput,
  type ValueInfo,
  type WireValue,
} from "./variables.js";
export { OperatonFile, type FileOptions } from "./file.js";
export { BpmnError, ClientError, EngineError } from "./errors.js";
export { consoleLogger, silentLogger, type Logger, type LogLevel } from "./logger.js";
export {
  basicAuth,
  bearerAuth,
  type Auth,
  type BasicAuth,
  type BearerAuth,
  type RequestConfig,
  type RequestInterceptor,
} from "./auth.js";
export type {
  BoundTaskService,
  ClientEvents,
  ExternalTask,
  ExternalTaskInfo,
  Subscription,
  SubscriptionOptions,
  TaskAction,
  TaskContext,
  TaskHandler,
  TaskRef,
} from "./types.js";
