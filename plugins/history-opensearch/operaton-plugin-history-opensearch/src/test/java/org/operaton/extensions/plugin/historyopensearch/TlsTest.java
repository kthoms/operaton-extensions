/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.operaton.bpm.engine.ProcessEngine;

/** TLS settings against an HTTPS stub whose self-signed certificate is made with the JDK's keytool. */
class TlsTest {

    private static final String PASSWORD = "changeit";

    @TempDir
    Path directory;

    private Path keyStore;
    private StubOpenSearch stub;
    private ProcessEngine engine;

    @BeforeEach
    void createCertificate() throws Exception {
        keyStore = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-alias",
                        "server",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-dname",
                        "CN=127.0.0.1",
                        "-ext",
                        "san=ip:127.0.0.1",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        keyStore.toString(),
                        "-storepass",
                        PASSWORD)
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes());
        assertThat(keytool.waitFor()).as(output).isZero();
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    private SSLContext serverContext() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private HistoryOpenSearchPlugin plugin(boolean clientCertificate) throws Exception {
        stub = new StubOpenSearch(serverContext(), clientCertificate);
        HistoryOpenSearchPlugin plugin = new HistoryOpenSearchPlugin();
        plugin.setUris(List.of(stub.url()));
        plugin.setSendMode(SendMode.SYNC);
        plugin.setMaxRetries(0);
        return plugin;
    }

    @Test
    void aServerCertificateIssuedByTheConfiguredTrustStoreIsAccepted() throws Exception {
        HistoryOpenSearchPlugin plugin = plugin(false);
        plugin.setTrustStore(keyStore.toString());
        plugin.setTrustStorePassword(PASSWORD);

        engine = OpenSearchSupport.engine(plugin);
        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(stub.requests()).anyMatch(r -> r.method().equals("PUT"));
        assertThat(stub.bulkRequests()).isNotEmpty();
        assertThat(plugin.getHandler().getFailedCount()).isZero();
    }

    @Test
    void anUntrustedCertificateFailsStartupLoudly() throws Exception {
        HistoryOpenSearchPlugin plugin = plugin(false);

        assertThatThrownBy(() -> OpenSearchSupport.engine(plugin))
                .hasStackTraceContaining("cannot install the index templates")
                .hasStackTraceContaining("PKIX");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void skippingVerificationAcceptsAnyCertificate() throws Exception {
        HistoryOpenSearchPlugin plugin = plugin(false);
        plugin.setInsecureSkipVerify(true);

        engine = OpenSearchSupport.engine(plugin);
        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(stub.bulkRequests()).isNotEmpty();
    }

    @Test
    void aClientCertificateIsPresentedWhenAKeyStoreIsConfigured() throws Exception {
        HistoryOpenSearchPlugin plugin = plugin(true);
        plugin.setTrustStore(keyStore.toString());
        plugin.setTrustStorePassword(PASSWORD);
        plugin.setKeyStore(keyStore.toString());
        plugin.setKeyStorePassword(PASSWORD);

        engine = OpenSearchSupport.engine(plugin);
        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(stub.bulkRequests()).isNotEmpty();
    }

    @Test
    void withoutAClientCertificateAMutualTlsServerRefusesTheConnection() throws Exception {
        HistoryOpenSearchPlugin plugin = plugin(true);
        plugin.setTrustStore(keyStore.toString());
        plugin.setTrustStorePassword(PASSWORD);

        assertThatThrownBy(() -> OpenSearchSupport.engine(plugin))
                .hasStackTraceContaining("cannot install the index templates");
    }

    @Test
    void aMissingTrustStoreFileFailsWithAClearMessage() throws Exception {
        HistoryOpenSearchPlugin plugin = plugin(false);
        plugin.setTrustStore(directory.resolve("missing.p12").toString());

        assertThatThrownBy(() -> OpenSearchSupport.engine(plugin))
                .hasStackTraceContaining("cannot set up TLS")
                .isInstanceOf(RuntimeException.class);
    }
}
