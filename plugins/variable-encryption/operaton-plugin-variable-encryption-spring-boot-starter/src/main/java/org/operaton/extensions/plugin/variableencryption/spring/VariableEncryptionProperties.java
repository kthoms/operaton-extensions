/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.plugin.variable-encryption.*}; mapped one to one onto the plugin. */
@ConfigurationProperties("operaton.extensions.plugin.variable-encryption")
public class VariableEncryptionProperties {

    /** Whether the plugin is registered. */
    private boolean enabled = true;
    /** Static keys: key id to Base64 encoded 32 byte key, or env:NAME. */
    private Map<String, String> keys = new LinkedHashMap<>();
    /** Id of the key for new encryptions (static keys and key store). */
    private String activeKeyId;
    /** Globs or regex: patterns of the variable names to encrypt. */
    private List<String> variableNames = new ArrayList<>();
    /** Encrypt values tagged with EncryptedVariables.encrypted(...). */
    private boolean encryptMarkedValues = true;
    /** Allow reading designated variables that are stored in plaintext (migration). */
    private boolean allowPlaintextRead = false;
    /** JCE key store as key source. */
    private KeyStore keyStore = new KeyStore();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    public List<String> getVariableNames() {
        return variableNames;
    }

    public void setVariableNames(List<String> variableNames) {
        this.variableNames = variableNames;
    }

    public boolean isEncryptMarkedValues() {
        return encryptMarkedValues;
    }

    public void setEncryptMarkedValues(boolean encryptMarkedValues) {
        this.encryptMarkedValues = encryptMarkedValues;
    }

    public boolean isAllowPlaintextRead() {
        return allowPlaintextRead;
    }

    public void setAllowPlaintextRead(boolean allowPlaintextRead) {
        this.allowPlaintextRead = allowPlaintextRead;
    }

    public KeyStore getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /** The {@code key-store.*} group. */
    public static class KeyStore {

        /** Path of the key store file. */
        private String path;
        /** Key store type. */
        private String type = "PKCS12";
        /** Key store and key password, or env:NAME. */
        private String password;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
