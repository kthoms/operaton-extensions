/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

/**
 * TLS settings of the connection to OpenSearch. Everything is optional: without any setting the JVM's
 * default trust store verifies the server certificate.
 *
 * @param trustStore path of a trust store with the CA of the server certificate; {@code null} for the
 *     JVM default
 * @param trustStorePassword password of the trust store; may be {@code null}
 * @param trustStoreType store type, {@code PKCS12} when {@code null}
 * @param keyStore path of a key store with the client certificate (mutual TLS); {@code null} for none
 * @param keyStorePassword password of the key store and its key; may be {@code null}
 * @param keyStoreType store type, {@code PKCS12} when {@code null}
 * @param insecureSkipVerify do not verify the server certificate and host name at all. Only for
 *     development clusters with self-signed certificates; a warning is logged at startup
 */
public record TlsSettings(
        String trustStore,
        String trustStorePassword,
        String trustStoreType,
        String keyStore,
        String keyStorePassword,
        String keyStoreType,
        boolean insecureSkipVerify) {

    /** No TLS settings: the JVM defaults apply. */
    public static TlsSettings defaults() {
        return new TlsSettings(null, null, null, null, null, null, false);
    }

    @Override
    public String toString() {
        return "TlsSettings[trustStore=" + trustStore + ", trustStoreType=" + trustStoreType + ", keyStore=" + keyStore
                + ", keyStoreType=" + keyStoreType + ", insecureSkipVerify=" + insecureSkipVerify + ", passwords=***]";
    }
}
