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
import { readFile } from "node:fs/promises";

const base = (process.env.OPERATON_URL ?? "http://localhost:8080/engine-rest").replace(/\/+$/, "");

async function call(method: string, path: string, init: RequestInit = {}): Promise<unknown> {
  const response = await fetch(`${base}${path}`, { method, ...init });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path} -> ${response.status}: ${text}`);
  return text ? (JSON.parse(text) as unknown) : undefined;
}

const bpmn = await readFile(new URL("../order-shipping.bpmn", import.meta.url));
const form = new FormData();
form.set("deployment-name", "order-shipping");
form.set("enable-duplicate-filtering", "true");
form.set("data", new Blob([bpmn], { type: "text/xml" }), "order-shipping.bpmn");
await call("POST", "/deployment/create", { body: form });
console.log("deployed order-shipping.bpmn");

const orders = [
  { orderId: "A-1001", zip: "10115", city: "Berlin" },
  { orderId: "A-1002", zip: "2000", city: "Hamburg" }, // invalid zip: ends as "Order rejected"
];
for (const order of orders) {
  const variables = {
    orderId: { type: "String", value: order.orderId },
    address: {
      type: "Json",
      value: JSON.stringify({ street: "Main Street 1", zip: order.zip, city: order.city }),
    },
    items: { type: "Json", value: JSON.stringify([{ sku: "BOOK-1", quantity: 2 }]) },
  };
  const instance = (await call("POST", "/process-definition/key/order-shipping/start", {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ businessKey: order.orderId, variables }),
  })) as { id: string };
  console.log(`started order ${order.orderId}: process instance ${instance.id}`);
}
console.log(`watch them in Cockpit: ${base.replace(/\/engine-rest$/, "")}/operaton/app/cockpit/`);
