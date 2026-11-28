# Go External Task Client - example

A loan request goes through a credit check: the Go worker `credit-check` looks the customer up at a
simulated credit bureau and the process grants or declines the loan.

## What it shows

- subscribing to a topic with a variable filter and a process definition filter
- reading typed variables (`String`, `Long`) and completing with typed variables (`Integer`, `Long`,
  `Boolean`, `Date`)
- a business outcome as BPMN error (`UNKNOWN_CUSTOMER`, caught by a boundary event)
- a transient failure retried by the engine (`flaky` customer fails twice, then succeeds)
- graceful shutdown on Ctrl-C

## Process model

`credit-check.bpmn`: Loan requested - Check credit (external task, topic `credit-check`) -
Credit approved? - Loan granted / Loan declined; the boundary error event "Customer unknown" leads to
Request rejected. Open the file in Cockpit's viewer or [bpmn.io](https://bpmn.io) to see it.

## Prerequisites

Go 1.23+ and Docker.

## Run it

All commands run from `clients/go`.

```bash
cd example && docker compose up -d --wait && cd ..   # Operaton on http://localhost:8080 (demo / demo)
go run ./example deploy                              # deploy credit-check.bpmn
go run ./example worker                              # run the worker (Ctrl-C stops it)
```

In a second terminal:

```bash
go run ./example start alice 5000     # score 780, limit 90000: loan granted
go run ./example start alice 500000   # above the limit: loan declined
go run ./example start bob 100        # score 540: loan declined
go run ./example start zed 100        # no record: BPMN error, request rejected
go run ./example start flaky 1000     # fails twice (retried after 2s), then granted
```

Each `start` prints the REST URLs for the instance's history and variables. Cockpit is at
http://localhost:8080/operaton/app/cockpit (demo / demo). The worker honours `OPERATON_URL`,
`OPERATON_USER` and `OPERATON_PASSWORD`.

Stop the engine with `docker compose down` in `example/`.

## How it works

| Model element | Code in `main.go` |
|---|---|
| service task `Check credit`, topic `credit-check` | `checkCredit`, subscribed in `runWorker` |
| boundary error event `UNKNOWN_CUSTOMER` | `*operaton.BPMNError` returned for unknown customers |
| gateway condition `${approved}` | `approved` variable set in `svc.Complete` |
| retries | plain `error` return; `Config.Retries` and `RetryTimeout` in `runWorker` |
