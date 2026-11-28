/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Submit an application, read it back decrypted, and look at the raw database rows. */
@RestController
public class AccountApplicationController {

    private final AccountApplicationService service;
    private final StoredRows storedRows;

    public AccountApplicationController(AccountApplicationService service, StoredRows storedRows) {
        this.service = service;
        this.storedRows = storedRows;
    }

    @PostMapping("/api/applications")
    public Map<String, String> submit(@RequestBody ApplicationRequest request) {
        return Map.of("processInstanceId", service.submit(request));
    }

    /** The variables as the engine API returns them (decrypted). */
    @GetMapping("/api/applications/{id}")
    public Map<String, Object> view(@PathVariable String id) {
        return service.view(id);
    }

    /** The same variables as they are stored in the database (ciphertext for the personal data). */
    @GetMapping("/api/applications/{id}/stored")
    public List<StoredRows.Row> stored(@PathVariable String id) {
        return storedRows.of(id);
    }
}
