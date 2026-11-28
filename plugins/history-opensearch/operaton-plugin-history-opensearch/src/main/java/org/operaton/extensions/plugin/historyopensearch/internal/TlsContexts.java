/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchException;
import org.operaton.extensions.plugin.historyopensearch.TlsSettings;

/** Builds the {@link SSLContext} for the TLS settings. Stateless. */
final class TlsContexts {

    private TlsContexts() {}

    static SSLContext create(TlsSettings tls) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            KeyManager[] keyManagers = null;
            if (tls.keyStore() != null) {
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] password = chars(tls.keyStorePassword());
                factory.init(load(tls.keyStore(), tls.keyStoreType(), password), password);
                keyManagers = factory.getKeyManagers();
            }
            TrustManager[] trustManagers = null;
            if (tls.insecureSkipVerify()) {
                trustManagers = new TrustManager[] {new TrustEverything()};
            } else if (tls.trustStore() != null) {
                TrustManagerFactory factory =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(load(tls.trustStore(), tls.trustStoreType(), chars(tls.trustStorePassword())));
                trustManagers = factory.getTrustManagers();
            }
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new HistoryOpenSearchException("history-opensearch: cannot set up TLS: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(String path, String type, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, password);
        }
        return store;
    }

    private static char[] chars(String value) {
        return value == null ? null : value.toCharArray();
    }

    /** Accepts any certificate and host name; only used when the user opted in to {@code insecureSkipVerify}. */
    private static final class TrustEverything extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
