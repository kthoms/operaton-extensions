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
import { describe, expect, expectTypeOf, it } from "vitest";

import { ClientError, OperatonFile, TypedValue, Variables, typed } from "../../src/index.js";

describe("type inference on set", () => {
  const cases: [string, unknown, string, unknown][] = [
    ["string", "a", "String", "a"],
    ["boolean", true, "Boolean", true],
    ["small integer", 42, "Integer", 42],
    ["int32 max", 2147483647, "Integer", 2147483647],
    ["beyond int32", 2147483648, "Long", 2147483648],
    ["negative beyond int32", -2147483649, "Long", -2147483649],
    ["bigint", 9007199254740993n, "Long", "9007199254740993"],
    ["fraction", 1.5, "Double", 1.5],
    ["null", null, "Null", null],
    ["json object", { a: [1, 2] }, "Json", '{"a":[1,2]}'],
    ["json array", [1, "x"], "Json", '[1,"x"]'],
    ["date", new Date("2024-03-05T10:20:30.123Z"), "Date", "2024-03-05T10:20:30.123+0000"],
    ["bytes", new Uint8Array([1, 2, 3]), "Bytes", "AQID"],
  ];
  it.each(cases)("%s", (_name, value, type, wire) => {
    const dirty = Variables.from({ v: value }).getDirtyVariables();
    expect(dirty.v).toMatchObject({ type, value: wire });
  });

  it("file", () => {
    const file = OperatonFile.fromBuffer("hello", { filename: "a.txt", mimeType: "text/plain" });
    expect(Variables.from({ f: file }).getDirtyVariables().f).toEqual({
      type: "File",
      value: "aGVsbG8=",
      valueInfo: { filename: "a.txt", mimetype: "text/plain" },
    });
  });

  it("rejects undefined, NaN and unsafe integers loudly", () => {
    expect(() => Variables.from({ v: undefined })).toThrow(/undefined/);
    expect(() => Variables.from({ v: NaN })).toThrow(/finite/);
    expect(() => Variables.from({ v: 2 ** 60 })).toThrow(/bigint/);
    expect(() => Variables.from({ v: () => 1 })).toThrow(ClientError);
  });
});

describe("explicit types", () => {
  it("typed helpers serialize to the wire format", () => {
    const v = Variables.create()
      .setTyped("l", typed.long(5))
      .setTyped("d", typed.double(3))
      .setTyped("n", typed.nil())
      .setTyped("j", typed.json("plain"))
      .setTyped("o", typed.object({ x: 1 }, "com.acme.Order"))
      .setTyped("s", typed.short(7));
    expect(v.getDirtyVariables()).toEqual({
      l: { type: "Long", value: 5, valueInfo: {} },
      d: { type: "Double", value: 3, valueInfo: {} },
      n: { type: "Null", value: null, valueInfo: {} },
      j: { type: "Json", value: "plain", valueInfo: {} },
      o: {
        type: "Object",
        value: '{"x":1}',
        valueInfo: {
          objectTypeName: "com.acme.Order",
          serializationDataFormat: "application/json",
        },
      },
      s: { type: "Short", value: 7, valueInfo: {} },
    });
  });

  it("checks the value against the declared type", () => {
    expect(() => Variables.create().setTyped("x", new TypedValue("Integer", 1.5))).toThrow(
      /32-bit/,
    );
    expect(() => Variables.create().setTyped("x", new TypedValue("String", 1))).toThrow(/string/);
    expect(() => Variables.create().setTyped("x", typed.integer(2 ** 31))).toThrow(/32-bit/);
    expect(() => Variables.create().setTyped("x", typed.date(new Date("nope")))).toThrow(
      /invalid Date/,
    );
  });

  it("marks transient variables", () => {
    const v = Variables.create().setTransient("t", "x");
    expect(v.getDirtyVariables().t).toEqual({
      type: "String",
      value: "x",
      valueInfo: { transient: true },
    });
  });

  it("takes typed values from plain objects but not look-alikes", () => {
    const v = Variables.from({ a: typed.long(1), b: { type: "Long", value: 1 } });
    expect(v.getDirtyVariables().a?.type).toBe("Long");
    expect(v.getDirtyVariables().b?.type).toBe("Json");
  });
});

describe("reading what the engine sent", () => {
  const wire = {
    s: { type: "String", value: "x", valueInfo: {} },
    i: { type: "Integer", value: 3, valueInfo: {} },
    b: { type: "Boolean", value: false, valueInfo: {} },
    l: { type: "Long", value: 123456789012, valueInfo: {} },
    d: { type: "Double", value: 0.5, valueInfo: {} },
    dt: { type: "Date", value: "2024-03-05T10:20:30.123+0100", valueInfo: {} },
    j: { type: "Json", value: '{"a":1}', valueInfo: {} },
    o: {
      type: "Object",
      value: '{"id":9}',
      valueInfo: { objectTypeName: "x.Y", serializationDataFormat: "application/json" },
    },
    ox: {
      type: "Object",
      value: "<a/>",
      valueInfo: { objectTypeName: "x.Y", serializationDataFormat: "application/xml" },
    },
    n: { type: "Null", value: null, valueInfo: {} },
    by: { type: "Bytes", value: "AQID", valueInfo: {} },
    f: { type: "File", value: null, valueInfo: { filename: "r.txt", mimetype: "text/plain" } },
    unknown: { type: "Future", value: { a: 1 }, valueInfo: {} },
  };

  it("deserializes by type", () => {
    const v = new Variables(wire, { readOnly: true });
    expect(v.get("s")).toBe("x");
    expect(v.get("i")).toBe(3);
    expect(v.get("b")).toBe(false);
    expect(v.get("l")).toBe(123456789012);
    expect(v.get("d")).toBe(0.5);
    expect((v.get("dt") as Date).toISOString()).toBe("2024-03-05T09:20:30.123Z");
    expect(v.get("j")).toEqual({ a: 1 });
    expect(v.get("o")).toEqual({ id: 9 });
    expect(v.get("ox")).toBe("<a/>");
    expect(v.get("n")).toBeNull();
    expect([...(v.get("by") as Uint8Array)]).toEqual([1, 2, 3]);
    expect(v.getTyped("unknown")).toMatchObject({ type: "Future", value: { a: 1 } });
    expect(v.get("missing")).toBeUndefined();
    expect(v.has("s")).toBe(true);
    expect(v.names).toContain("dt");
    expect(Object.keys(v.getAll())).toHaveLength(Object.keys(wire).length);
    expect(v.getAllTyped().s?.type).toBe("String");
  });

  it("is case-insensitive about the type name", () => {
    const v = new Variables({ x: { type: "string", value: "y" } });
    expect(v.getTyped("x")?.type).toBe("String");
  });

  it("loads remote files lazily and fails loudly before loading", async () => {
    const v = new Variables(wire, {
      readOnly: true,
      fileLoader: (name) => Promise.resolve(new TextEncoder().encode(`data of ${name}`)),
    });
    const file = v.get("f") as OperatonFile;
    expect(file.filename).toBe("r.txt");
    expect(file.mimeType).toBe("text/plain");
    expect(() => file.content).toThrow(/load\(\)/);
    await file.load();
    expect(new TextDecoder().decode(file.content)).toBe("data of f");
  });

  it("rejects invalid JSON with the variable name", () => {
    const v = new Variables({ j: { type: "Json", value: "{" } });
    expect(() => v.get("j")).toThrow(/Variable 'j'/);
  });

  it("is read-only when asked", () => {
    const v = new Variables(wire, { readOnly: true });
    expect(() => v.set("s", "y")).toThrow(/read-only/);
    expect(v.getDirtyVariables()).toEqual({});
  });
});

describe("writing", () => {
  it("reads back what it set and tracks only changes as dirty", () => {
    const v = new Variables({ a: { type: "String", value: "old" } });
    v.set("b", 1).set("a", "new");
    expect(v.get("a")).toBe("new");
    expect(Object.keys(v.getDirtyVariables()).sort()).toEqual(["a", "b"]);
    expect(new Variables({ a: { type: "String", value: "old" } }).getDirtyVariables()).toEqual({});
  });

  it("round-trips a date and a bigint", () => {
    const v = Variables.from({ d: new Date("2020-01-02T03:04:05.000Z"), l: 12345678901234567n });
    expect(v.get("d")).toEqual(new Date("2020-01-02T03:04:05.000Z"));
    expect(v.get("l")).toBe(12345678901234567n);
  });
});

describe("static typing", () => {
  it("infers value types from the variable map", () => {
    const v = new Variables<{ orderId: string; total: number }>();
    expectTypeOf(v.get("orderId")).toEqualTypeOf<string | undefined>();
    expectTypeOf(v.get("total")).toEqualTypeOf<number | undefined>();
    v.set("total", 12);
    expect(v.get("total")).toBe(12);
    // type errors only (never executed)
    const compileTimeOnly = () => {
      // @ts-expect-error wrong value type
      v.set("total", "12");
      // @ts-expect-error unknown variable
      v.get("nope");
    };
    expect(compileTimeOnly).toBeTypeOf("function");
  });
});

describe("OperatonFile", () => {
  it("reads from a path", async () => {
    const file = await OperatonFile.fromPath(new URL(import.meta.url).pathname);
    expect(file.filename).toBe("variables.test.ts");
    expect(file.isLoaded).toBe(true);
    expect(file.content.length).toBeGreaterThan(0);
  });
});
