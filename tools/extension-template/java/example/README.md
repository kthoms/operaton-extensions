# __TITLE__ — example

TODO: the scenario in two sentences (who does what, and where the extension comes in).

## Run it

```bash
docker compose up -d --wait   # PostgreSQL (and the services the scenario needs)
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo)
```

## Test it

```bash
../../../mvnw verify          # Testcontainers integration tests, Docker required
```

## What to look at

TODO: the BPMN file, the configuration and the code that use the extension.
