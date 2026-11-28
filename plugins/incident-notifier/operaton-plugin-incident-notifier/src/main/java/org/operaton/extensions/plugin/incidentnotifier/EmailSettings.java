/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

/**
 * SMTP connection and addressing settings of the email channel.
 *
 * @param host SMTP host
 * @param port SMTP port
 * @param username user for SMTP authentication, {@code null} for none
 * @param password password for SMTP authentication
 * @param from sender address
 * @param to default recipients, used when a process instance sets no recipient variable
 * @param cc default cc recipients, may be {@code null}
 * @param startTls use STARTTLS when the server offers it
 * @param startTlsRequired fail when the server does not offer STARTTLS
 * @param ssl connect over implicit TLS (SMTPS)
 * @param timeoutMillis connect, read and write timeout
 */
record EmailSettings(
        String host,
        int port,
        String username,
        String password,
        String from,
        String to,
        String cc,
        boolean startTls,
        boolean startTlsRequired,
        boolean ssl,
        int timeoutMillis) {

    @Override
    public String toString() {
        return "EmailSettings[host=" + host + ", port=" + port + ", username=" + username + ", password=***, from="
                + from + ", to=" + to + ", cc=" + cc + ", startTls=" + startTls + ", startTlsRequired="
                + startTlsRequired + ", ssl=" + ssl + "]";
    }
}
