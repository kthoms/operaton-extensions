# Variable Encryption Plugin — example

An online bank processes account applications in Operaton. The applicant's IBAN, birth date and profile (name, address,
tax id) are encrypted in the database, in the runtime tables and in the history, while the process, its
delegate and the clerk keep working with the plain values.

## What it shows

* The Spring Boot starter configuring the plugin (`application.yaml`): a demo key `demo-2026-01` and the names
  `iban`, `birthDate`, `applicantProfile` (a JSON object variable) to encrypt. `passportNumber` is encrypted because
  the code tags it with `EncryptedVariables.encrypted(...)`; `product` and `ibanValid` stay plain.
* The raw database rows next to the API view: `GET /api/applications/{id}/stored` shows what `ACT_RU_VARIABLE`,
  `ACT_HI_VARINST` and `ACT_HI_DETAIL` really hold (marker with key id, envelope bytes), while
  `GET /api/applications/{id}` returns the same variables decrypted.
* A delegate (`IbanValidator`) that reads the plain IBAN: encryption is transparent to process code.
* Tampering with the stored ciphertext is detected: reading the variable fails with an integrity error.

Process model: [`account-application.bpmn`](src/main/resources/bpmn/account-application.bpmn) (PNG not rendered
yet; open it in Cockpit or bpmn.io): application received, validate IBAN, then reject (invalid IBAN) or review by a clerk
(group `clerks`, user `alice`), who approves (account opened) or rejects.

## Prerequisites

JDK 21, Docker.

## Run it

```bash
docker compose up -d --wait   # PostgreSQL on 5432 (throwaway credentials operaton/operaton)
chmod +x ../../../mvnw        # if needed
../../../mvnw spring-boot:run
# Cockpit / Tasklist: http://localhost:8080 (demo / demo; clerk: alice / alice)
```

The demo key in `application.yaml` is for the example only; set `APP_ENCRYPTION_KEY` (Base64 of 32 random
bytes, `openssl rand -base64 32`) to use your own, and never commit a real key.

## Walkthrough

Submit an application:

```bash
curl -s -X POST localhost:8080/api/applications -H 'Content-Type: application/json' -d '{
  "name":"Ada Lovelace","street":"12 Analytical Street","city":"10115 Berlin","taxId":"TAXID-12-345-678",
  "iban":"DE89370400440532013000","birthDate":"1990-12-10","passportNumber":"C01X00T47","product":"Giro account"}'
# {"processInstanceId":"..."}
curl -s localhost:8080/api/applications/<id>          # decrypted, as the engine API returns it
curl -s localhost:8080/api/applications/<id>/stored   # the same variables as stored: ciphertext
```

In `psql` (`docker compose exec postgres psql -U operaton`): `select "NAME_", "TEXT_", "LONG_" from act_ru_variable;` shows
the marker `$operaton-ve$1$demo-2026-01` for the encrypted variables and plain text for `product`.

* A valid IBAN (`DE89370400440532013000`): a task "Review application" for the group `clerks`; open it in Tasklist
  as `alice`: the variables are readable. Complete it with `approved=true|false`.
* An invalid IBAN (`DE00370400440532013000`): the process ends in "Application rejected".
* Modify a byte of `act_ge_bytearray.bytes_` of an encrypted variable and read the variable: it fails with
  "Integrity check failed for variable 'iban'", it never returns garbage or plaintext.

## How it works

* `application.yaml`: the key and the variable names (`operaton.extensions.plugin.variable-encryption.*`).
* `AccountApplicationService`: sets the variables; `iban`, `birthDate` and `applicantProfile` are encrypted by
  name, `passportNumber` by tag.
* `IbanValidator`: reads `iban` as if nothing were encrypted.
* `StoredRows`: reads the raw rows with `JdbcTemplate`, bypassing the engine.

## Run the tests

```bash
../../../mvnw verify   # Testcontainers integration test on PostgreSQL, Docker required
```

`AccountApplicationIT` runs the process and asserts through JDBC that no plaintext of the IBAN, name, address, tax
id, birth date or passport number appears in any variable row, history row or byte array, that the engine API
returns the plain values (also from the history after the instance ended), and that a tampered ciphertext fails.
