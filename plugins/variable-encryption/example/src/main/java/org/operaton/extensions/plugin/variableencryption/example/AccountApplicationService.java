/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.plugin.variableencryption.EncryptedVariables;
import org.springframework.stereotype.Service;

/**
 * Starts the account-opening process and reads an application back through the engine API. The
 * variables {@code iban}, {@code birthDate} and {@code applicantProfile} are encrypted because of
 * their names (see {@code application.yaml}); {@code passportNumber} because it is tagged.
 */
@Service
public class AccountApplicationService {

    private final RuntimeService runtimeService;

    public AccountApplicationService(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * Starts an application.
     *
     * @param request the submitted data
     * @return the process instance id
     */
    public String submit(ApplicationRequest request) {
        VariableMap variables = Variables.createVariables()
                .putValue("product", request.product())
                .putValue("iban", request.iban())
                .putValue(
                        "birthDate",
                        Date.from(request.birthDate().atStartOfDay().toInstant(ZoneOffset.UTC)))
                .putValueTyped(
                        "applicantProfile",
                        Variables.objectValue(new Applicant(
                                        request.name(), request.street(), request.city(), request.taxId()))
                                .serializationDataFormat(Variables.SerializationDataFormats.JSON)
                                .create());
        if (request.passportNumber() != null) {
            // not covered by a name pattern: tag the value instead
            variables.putValueTyped("passportNumber", EncryptedVariables.encrypted(request.passportNumber()));
        }
        return runtimeService
                .startProcessInstanceByKey("account-application", variables)
                .getId();
    }

    /**
     * The application as the engine API returns it: decrypted.
     *
     * @param processInstanceId the process instance
     * @return the variables by name
     */
    public Map<String, Object> view(String processInstanceId) {
        return new LinkedHashMap<>(runtimeService.getVariables(processInstanceId));
    }
}
