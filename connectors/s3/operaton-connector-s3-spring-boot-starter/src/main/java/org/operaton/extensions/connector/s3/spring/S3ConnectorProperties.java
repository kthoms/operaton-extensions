/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.extensions.connector.s3.S3StoreConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.connector.s3.*}; they only map onto the plugin's configuration. */
@ConfigurationProperties("operaton.extensions.connector.s3")
public class S3ConnectorProperties {

    /** Store used when a call names none; optional when exactly one store is configured. */
    private String defaultStore;

    /** The named stores. */
    private Map<String, S3StoreConfig> stores = new LinkedHashMap<>();

    public String getDefaultStore() {
        return defaultStore;
    }

    public void setDefaultStore(String defaultStore) {
        this.defaultStore = defaultStore;
    }

    public Map<String, S3StoreConfig> getStores() {
        return stores;
    }

    public void setStores(Map<String, S3StoreConfig> stores) {
        this.stores = stores;
    }
}
