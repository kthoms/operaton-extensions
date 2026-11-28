/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.plugin.custom-batch.*}; mapped one to one onto the plugin. */
@ConfigurationProperties(prefix = CustomBatchProperties.PREFIX)
public class CustomBatchProperties {

    public static final String PREFIX = "operaton.extensions.plugin.custom-batch";

    /** Register the plugin and the CustomBatches bean at all. */
    private boolean enabled = true;

    /**
     * How long the history of finished batches is kept, as ISO 8601 durations by batch type, for example
     * recalculate-invoice-totals=P30D. Types without an entry follow the engine's batch operation time to live.
     */
    private Map<String, String> historyTimeToLive = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getHistoryTimeToLive() {
        return historyTimeToLive;
    }

    public void setHistoryTimeToLive(Map<String, String> historyTimeToLive) {
        this.historyTimeToLive = historyTimeToLive;
    }
}
