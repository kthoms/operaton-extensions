# TypeScript External Task Client: order-shipping worker

An order process hands each order to a worker that ships it: the process waits at the external
task `Ship order` (topic `ship-order`), the Node.js worker books the shipment and completes the
task with a tracking number, or reports the BPMN error `INVALID_ADDRESS` for an unusable zip code,
which the process routes to `Order rejected`.

## Run it

Needs Node 22+ and Docker. From this folder:

```bash
docker compose up -d --wait   # Operaton on http://localhost:8080 (demo / demo)
npm install                   # the example uses the client from the folder above
npm run build --prefix ..     # build it once (the example imports its dist/)
npm run deploy                # deploys order-shipping.bpmn and starts two orders
npm run worker                # processes them; Ctrl+C stops gracefully
```

If port 8080 is taken, change the port mapping in `compose.yaml` and set
`OPERATON_URL=http://localhost:<port>/engine-rest` for both npm scripts.

Expected result: order `A-1001` ends in `Order shipped` with the variables `trackingNumber`,
`parcels` and `shippedAt`; order `A-1002` (zip `2000`) ends in `Order rejected`. Look at both in
Cockpit (http://localhost:8080/operaton/app/cockpit/, demo / demo) under the history of
`Order shipping`.

Clean up with `docker compose down`.

## What to read

- [`src/worker.ts`](src/worker.ts): the worker (typed variables, `BpmnError`, graceful shutdown).
- [`order-shipping.bpmn`](order-shipping.bpmn): the process.
- [`scripts/deploy-and-start.ts`](scripts/deploy-and-start.ts): deployment and starting instances over REST.
