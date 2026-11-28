/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.extensions.connector.jdbc;

import java.util.Map;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.operaton.spin.plugin.impl.SpinProcessEnginePlugin;

/** Registers the plugins an engine needs to run the JDBC connector from BPMN. */
final class EngineTestSupport {

    private EngineTestSupport() {}

    static JdbcConnectorPlugin install(
            ProcessEngineConfigurationImpl configuration, Map<String, JdbcDataSourceProperties> dataSources) {
        JdbcConnectorPlugin plugin = new JdbcConnectorPlugin();
        plugin.setDataSources(dataSources);
        configuration.getProcessEnginePlugins().add(new ConnectProcessEnginePlugin());
        configuration.getProcessEnginePlugins().add(new SpinProcessEnginePlugin());
        configuration.getProcessEnginePlugins().add(plugin);
        return plugin;
    }
}
