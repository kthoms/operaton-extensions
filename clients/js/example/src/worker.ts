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
import { randomUUID } from "node:crypto";

import { BpmnError, Client, Variables, consoleLogger } from "@kthoms/operaton-external-task-client";

interface OrderVariables {
  orderId: string;
  address: { street: string; zip: string; city: string };
  items: { sku: string; quantity: number }[];
}

const client = new Client({
  baseUrl: process.env.OPERATON_URL ?? "http://localhost:8080/engine-rest",
  workerId: "order-shipping-worker",
  maxTasks: 5,
  asyncResponseTimeout: 20_000,
  lockDuration: 30_000,
  logger: consoleLogger("info"),
  // Operaton's demo image needs no credentials; with authentication enabled use:
  // auth: { type: "basic", username: "demo", password: "demo" },
});

client.subscribe<OrderVariables>(
  "ship-order",
  async ({ task, taskService }) => {
    const orderId = task.variables.get("orderId");
    const address = task.variables.get("address");
    const items = task.variables.get("items") ?? [];
    if (!orderId || !address) {
      throw new Error(`task ${task.id}: orderId and address are required`);
    }
    if (!/^\d{5}$/.test(address.zip)) {
      // a business outcome the process models: caught by the "Address invalid" boundary event
      throw new BpmnError("INVALID_ADDRESS", `zip '${address.zip}' is not a 5-digit code`, {
        rejectionReason: "invalid zip",
      });
    }

    const parcels = items.reduce((sum, item) => sum + item.quantity, 0);
    const trackingNumber = `TRK-${randomUUID().slice(0, 8).toUpperCase()}`;
    console.log(
      `shipping order ${orderId}: ${parcels} parcel(s) to ${address.city}, ${trackingNumber}`,
    );

    await taskService.complete(Variables.from({ trackingNumber, parcels, shippedAt: new Date() }));
  },
  { variables: ["orderId", "address", "items"] },
);

client.on("poll:error", (error) => {
  console.error(
    "engine not reachable, backing off:",
    error instanceof Error ? error.message : error,
  );
});

client.start();
console.log("order-shipping worker started, waiting for tasks on topic 'ship-order'");

// Graceful shutdown: stop polling, let running handlers finish.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    console.log(`${signal}: stopping, waiting for running tasks`);
    client.stop({ timeout: 30_000 }).then(
      () => process.exit(0),
      (error: unknown) => {
        console.error(error);
        process.exit(1);
      },
    );
  });
}
