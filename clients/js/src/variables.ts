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
import { ClientError } from "./errors.js";
import { OperatonFile } from "./file.js";

/** The value types of the engine's REST API. */
export type VariableType =
  | "Null"
  | "Boolean"
  | "Short"
  | "Integer"
  | "Long"
  | "Double"
  | "String"
  | "Date"
  | "Json"
  | "Xml"
  | "Bytes"
  | "Object"
  | "File";

export interface ValueInfo {
  transient?: boolean;
  objectTypeName?: string;
  serializationDataFormat?: string;
  filename?: string;
  mimetype?: string;
  encoding?: string;
  [key: string]: unknown;
}

/** A variable as it travels over the REST API. */
export interface WireValue {
  type: string;
  value: unknown;
  valueInfo?: ValueInfo;
}

const INT32_MIN = -(2 ** 31);
const INT32_MAX = 2 ** 31 - 1;
const INT16_MIN = -(2 ** 15);
const INT16_MAX = 2 ** 15 - 1;

/**
 * A value together with its engine type. Create with the {@link typed} helpers; instances are
 * what `getTyped` returns. A plain `{ type, value }` object is never mistaken for a typed value.
 */
export class TypedValue<V = unknown> {
  constructor(
    readonly type: VariableType,
    readonly value: V,
    readonly valueInfo: ValueInfo = {},
  ) {}
}

/** Explicitly typed values, for when inference from the JavaScript value is not what you want. */
export const typed = {
  string: (value: string) => new TypedValue("String", value),
  boolean: (value: boolean) => new TypedValue("Boolean", value),
  short: (value: number) => new TypedValue("Short", value),
  integer: (value: number) => new TypedValue("Integer", value),
  /** 64-bit integer; use a `bigint` for values beyond `Number.MAX_SAFE_INTEGER`. */
  long: (value: number | bigint) => new TypedValue("Long", value),
  double: (value: number) => new TypedValue("Double", value),
  date: (value: Date) => new TypedValue("Date", value),
  /** A JSON document; the value is serialized with `JSON.stringify`. */
  json: (value: unknown) => new TypedValue("Json", value),
  xml: (value: string) => new TypedValue("Xml", value),
  bytes: (value: Uint8Array) => new TypedValue("Bytes", value),
  file: (value: OperatonFile) => new TypedValue("File", value),
  /** The `null` value. */
  nil: () => new TypedValue("Null", null),
  /**
   * A serialized Java object. `value` is a string in `serializationDataFormat`
   * (default `application/json`) or, for that JSON format, any JSON-serializable value.
   */
  object: (value: unknown, objectTypeName: string, serializationDataFormat = "application/json") =>
    new TypedValue("Object", value, { objectTypeName, serializationDataFormat }),
};

/** The value type used when none is given: reading gives `unknown`, writing accepts anything. */
export type VariableMap = Record<string, unknown>;

const CANONICAL: Record<string, VariableType> = {
  null: "Null",
  boolean: "Boolean",
  short: "Short",
  integer: "Integer",
  long: "Long",
  double: "Double",
  string: "String",
  date: "Date",
  json: "Json",
  xml: "Xml",
  bytes: "Bytes",
  object: "Object",
  file: "File",
};

/** Loads the content of a file variable of the current task. */
export type FileLoader = (variableName: string) => Promise<Uint8Array>;

export interface VariablesContext {
  /** A read-only instance rejects every `set*` call (used for the variables of a fetched task). */
  readOnly?: boolean;
  fileLoader?: FileLoader;
}

function formatDate(name: string, date: Date): string {
  if (Number.isNaN(date.getTime())) {
    throw new ClientError(`Variable '${name}': invalid Date`);
  }
  return date.toISOString().replace("Z", "+0000");
}

function parseDate(name: string, text: unknown): Date {
  if (typeof text !== "string") {
    throw new ClientError(`Variable '${name}': a Date value must be a string, got ${typeof text}`);
  }
  const date = new Date(text.replace(/([+-]\d{2})(\d{2})$/, "$1:$2"));
  if (Number.isNaN(date.getTime())) {
    throw new ClientError(`Variable '${name}': cannot parse date '${text}'`);
  }
  return date;
}

function inferTyped(name: string, value: unknown): TypedValue {
  if (value instanceof TypedValue) return value;
  if (value === null) return typed.nil();
  switch (typeof value) {
    case "undefined":
      throw new ClientError(
        `Variable '${name}': undefined cannot be set; use null or typed.nil() for the null value`,
      );
    case "boolean":
      return typed.boolean(value);
    case "string":
      return typed.string(value);
    case "bigint":
      return typed.long(value);
    case "number":
      if (!Number.isFinite(value)) {
        throw new ClientError(`Variable '${name}': ${value} is not a finite number`);
      }
      if (!Number.isInteger(value)) return typed.double(value);
      if (value >= INT32_MIN && value <= INT32_MAX) return typed.integer(value);
      if (!Number.isSafeInteger(value)) {
        throw new ClientError(
          `Variable '${name}': ${value} is beyond the safe integer range; pass a bigint or typed.double()`,
        );
      }
      return typed.long(value);
    case "object":
      if (value instanceof Date) return typed.date(value);
      if (value instanceof OperatonFile) return typed.file(value);
      if (value instanceof Uint8Array) return typed.bytes(value);
      return typed.json(value);
    default:
      throw new ClientError(`Variable '${name}': a ${typeof value} cannot be a variable value`);
  }
}

function serialize(name: string, tv: TypedValue): WireValue {
  const { type, value } = tv;
  const valueInfo: ValueInfo = { ...tv.valueInfo };
  const bad = (expected: string): never => {
    throw new ClientError(`Variable '${name}': a ${type} value must be ${expected}`);
  };
  switch (type) {
    case "Null":
      return { type, value: null, valueInfo };
    case "Boolean":
      return { type, value: typeof value === "boolean" ? value : bad("a boolean"), valueInfo };
    case "Short":
      if (
        typeof value !== "number" ||
        !Number.isInteger(value) ||
        value < INT16_MIN ||
        value > INT16_MAX
      )
        bad("a 16-bit integer");
      return { type, value, valueInfo };
    case "Integer":
      if (
        typeof value !== "number" ||
        !Number.isInteger(value) ||
        value < INT32_MIN ||
        value > INT32_MAX
      )
        bad("a 32-bit integer");
      return { type, value, valueInfo };
    case "Long":
      if (typeof value === "bigint") return { type, value: value.toString(), valueInfo };
      if (typeof value !== "number" || !Number.isSafeInteger(value))
        bad("a safe integer or a bigint");
      return { type, value, valueInfo };
    case "Double":
      if (typeof value !== "number" || !Number.isFinite(value)) bad("a finite number");
      return { type, value, valueInfo };
    case "String":
    case "Xml":
      return { type, value: typeof value === "string" ? value : bad("a string"), valueInfo };
    case "Date":
      return {
        type,
        value: value instanceof Date ? formatDate(name, value) : bad("a Date"),
        valueInfo,
      };
    case "Json":
      return {
        type,
        value: typeof value === "string" ? value : JSON.stringify(value),
        valueInfo,
      };
    case "Bytes":
      return {
        type,
        value:
          value instanceof Uint8Array ? Buffer.from(value).toString("base64") : bad("a Uint8Array"),
        valueInfo,
      };
    case "File": {
      if (!(value instanceof OperatonFile)) return bad("an OperatonFile");
      valueInfo.filename = value.filename;
      if (value.mimeType) valueInfo.mimetype = value.mimeType;
      if (value.encoding) valueInfo.encoding = value.encoding;
      return { type, value: Buffer.from(value.content).toString("base64"), valueInfo };
    }
    case "Object": {
      if (!valueInfo.objectTypeName) bad("given with an objectTypeName");
      const format = valueInfo.serializationDataFormat ?? "application/json";
      valueInfo.serializationDataFormat = format;
      if (typeof value === "string") return { type, value, valueInfo };
      if (!format.includes("json")) bad(`a string in the data format ${format}`);
      return { type, value: JSON.stringify(value), valueInfo };
    }
  }
}

function deserialize(name: string, wire: WireValue, ctx: VariablesContext): TypedValue {
  const type = CANONICAL[wire.type.toLowerCase()];
  const valueInfo = wire.valueInfo ?? {};
  const value = wire.value;
  switch (type) {
    case undefined:
      // A type this client does not know: hand it over untouched instead of guessing.
      return new TypedValue(wire.type as VariableType, value, valueInfo);
    case "Null":
      return new TypedValue(type, null, valueInfo);
    case "Long":
      return new TypedValue(type, typeof value === "string" ? BigInt(value) : value, valueInfo);
    case "Date":
      return new TypedValue(type, value === null ? null : parseDate(name, value), valueInfo);
    case "Json":
      return new TypedValue(type, parseJson(name, value), valueInfo);
    case "Object": {
      const format = valueInfo.serializationDataFormat ?? "";
      return new TypedValue(
        type,
        format.includes("json") ? parseJson(name, value) : value,
        valueInfo,
      );
    }
    case "Bytes":
      return new TypedValue(
        type,
        typeof value === "string" ? new Uint8Array(Buffer.from(value, "base64")) : value,
        valueInfo,
      );
    case "File": {
      const loader = ctx.fileLoader;
      const filename = valueInfo.filename ?? name;
      const options = {
        ...(valueInfo.mimetype ? { mimeType: valueInfo.mimetype } : {}),
        ...(valueInfo.encoding ? { encoding: valueInfo.encoding } : {}),
      };
      const file =
        typeof value === "string"
          ? OperatonFile.fromBuffer(Buffer.from(value, "base64"), { filename, ...options })
          : OperatonFile.remote(
              filename,
              () => {
                if (!loader) {
                  return Promise.reject(
                    new ClientError(`Variable '${name}': no way to load the file content`),
                  );
                }
                return loader(name);
              },
              options,
            );
      return new TypedValue(type, file, valueInfo);
    }
    default:
      return new TypedValue(type, value, valueInfo);
  }
}

function parseJson(name: string, value: unknown): unknown {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value) as unknown;
  } catch (cause) {
    throw new ClientError(`Variable '${name}': the value is not valid JSON`, { cause });
  }
}

/**
 * Process variables with their engine types.
 *
 * `T` describes the variables you expect, so `get` and `set` are checked:
 * `Variables<{ orderId: string; total: number }>`.
 * The variables of a fetched task are read-only; create writable ones with {@link Variables.from}
 * or {@link Variables.create} and pass them to `complete` or `setVariables`.
 */
export class Variables<T extends VariableMap = VariableMap> {
  readonly #initial = new Map<string, WireValue>();
  readonly #dirty = new Map<string, WireValue>();
  readonly #ctx: VariablesContext;

  constructor(initial: Readonly<Record<string, WireValue>> = {}, ctx: VariablesContext = {}) {
    for (const [name, wire] of Object.entries(initial)) this.#initial.set(name, wire);
    this.#ctx = ctx;
  }

  /** Writable variables holding `values` (each type inferred, or taken from a {@link typed} value). */
  static from<T extends VariableMap>(values: T): Variables<T> {
    const variables = new Variables<T>();
    variables.setAll(values);
    return variables;
  }

  /** Empty writable variables. */
  static create<T extends VariableMap = VariableMap>(): Variables<T> {
    return new Variables();
  }

  /** The names of all variables. */
  get names(): string[] {
    return [...this.#initial.keys()];
  }

  has(name: keyof T & string): boolean {
    return this.#initial.has(name);
  }

  /** The value of a variable, or `undefined` if there is none. */
  get<K extends keyof T & string>(name: K): T[K] | undefined {
    return this.getTyped(name)?.value as T[K] | undefined;
  }

  /** The value and its engine type, or `undefined` if there is no such variable. */
  getTyped(name: keyof T & string): TypedValue | undefined {
    const wire = this.#initial.get(name);
    return wire === undefined ? undefined : deserialize(name, wire, this.#ctx);
  }

  getAll(): Partial<T> {
    const result: Record<string, unknown> = {};
    for (const [name, wire] of this.#initial)
      result[name] = deserialize(name, wire, this.#ctx).value;
    return result as Partial<T>;
  }

  getAllTyped(): Record<string, TypedValue> {
    const result: Record<string, TypedValue> = {};
    for (const [name, wire] of this.#initial) result[name] = deserialize(name, wire, this.#ctx);
    return result;
  }

  /** Sets a variable; the type is inferred from the value. Returns `this` for chaining. */
  set<K extends keyof T & string>(name: K, value: T[K]): this {
    return this.setTyped(name, inferTyped(name, value));
  }

  /** Sets a variable with an explicit type. */
  setTyped(name: keyof T & string, value: TypedValue): this {
    this.#assertWritable(name);
    const wire = serialize(name, value);
    this.#initial.set(name, wire);
    this.#dirty.set(name, wire);
    return this;
  }

  /** Sets a variable that is not persisted in history (`transient`). */
  setTransient<K extends keyof T & string>(name: K, value: T[K]): this {
    const tv = inferTyped(name, value);
    return this.setTyped(
      name,
      new TypedValue(tv.type, tv.value, { ...tv.valueInfo, transient: true }),
    );
  }

  /** Sets many variables; values may be {@link TypedValue}s. */
  setAll(values: Partial<T>): this {
    for (const [name, value] of Object.entries(values))
      this.setTyped(name, inferTyped(name, value));
    return this;
  }

  /** What changed since creation: the variables to send to the engine, in wire format. */
  getDirtyVariables(): Record<string, WireValue> {
    return Object.fromEntries(this.#dirty);
  }

  #assertWritable(name: string): void {
    if (this.#ctx.readOnly) {
      throw new ClientError(
        `Variable '${name}': the variables of a fetched task are read-only; ` +
          `create new ones with Variables.from({...}) and pass them to complete()`,
      );
    }
  }
}

/** Anything accepted where variables are sent: a {@link Variables} or a plain object. */
export type VariablesInput = Variables | VariableMap;

/** The variables to send for `input`, in wire format. */
export function toWireVariables(
  input: VariablesInput | undefined,
): Record<string, WireValue> | undefined {
  if (input === undefined) return undefined;
  const variables = input instanceof Variables ? input : Variables.from(input);
  return variables.getDirtyVariables();
}
