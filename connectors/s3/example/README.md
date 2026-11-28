# S3 Connector — example

A document review process: an author submits a document, it is stored in S3, a reviewer gets a task with a presigned download link, and the decision archives (moves to `archive/`) or deletes the document.

## What it shows

- `s3-put`, `s3-presign` and `s3-copy` (move) as connectors in a BPMN process, `s3-delete` through its delegate bean (`${s3DeleteDelegate}`)
- a file variable uploaded to S3, a presigned URL in the task description, objects moved between prefixes
- the Spring Boot starter with a named store in `application.yaml`
- an incident when the call cannot succeed (no document variable)

## Process model

`src/main/resources/bpmn/document-review.bpmn`: Document submitted, Store document (async), Create review link, Review document (candidate group `reviewers`), Approved?, then Archive document or Delete rejected document. Open the file in bpmn.io or Cockpit to see it.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
chmod +x mvnw
docker compose up -d --wait   # PostgreSQL on 5432, LocalStack S3 on 4566 (credentials test/test, bucket "documents")
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo; bob/bob reviews, alice/alice submits)
```

## Walkthrough

Start a review from code, for example a REST controller or a test, with `DocumentReviewService.submit("contract.txt", "text/plain", bytes, "alice")`.

1. Happy path: the object appears under `incoming/<id>/contract.txt`. Bob finds "Review document" in Tasklist; the description holds the presigned link (valid 24 hours). He completes it with `approved = true`; the object moves to `archive/<id>/contract.txt`.
2. Rejection: `approved = false`; the object is deleted.
3. Failure: a process started without the `document` variable fails in "Store document" and shows an incident in Cockpit.

Inspect the bucket with `docker compose exec s3 awslocal s3 ls s3://documents --recursive`.

## How it works

- `document-review.bpmn`: connector and delegate configuration per task.
- `application.yaml`: the `documents` store (endpoint, credentials, limits).
- `DocumentReviewService`: starts the process with a file variable.
- `DemoUsers`: seeds alice, bob and the group `reviewers`.

## Run the tests

```bash
../../../mvnw verify   # DocumentReviewIT: PostgreSQL and LocalStack via Testcontainers, Docker required
```
