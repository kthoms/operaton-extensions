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
export type LogLevel = "debug" | "info" | "warn" | "error";

/** Pluggable logger. `console`-like: a message plus optional context values. */
export interface Logger {
  debug(message: string, ...context: unknown[]): void;
  info(message: string, ...context: unknown[]): void;
  warn(message: string, ...context: unknown[]): void;
  error(message: string, ...context: unknown[]): void;
}

const ORDER: Record<LogLevel, number> = { debug: 0, info: 1, warn: 2, error: 3 };

/** A logger writing to the console, dropping messages below `level` (default `info`). */
export function consoleLogger(level: LogLevel = "info"): Logger {
  const at = (l: LogLevel, write: (...args: unknown[]) => void) => {
    return (message: string, ...context: unknown[]) => {
      if (ORDER[l] >= ORDER[level]) {
        write(`[operaton-client] ${l.toUpperCase()} ${message}`, ...context);
      }
    };
  };
  return {
    debug: at("debug", console.debug),
    info: at("info", console.info),
    warn: at("warn", console.warn),
    error: at("error", console.error),
  };
}

/** A logger that discards everything. */
export const silentLogger: Logger = {
  debug: () => undefined,
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};
