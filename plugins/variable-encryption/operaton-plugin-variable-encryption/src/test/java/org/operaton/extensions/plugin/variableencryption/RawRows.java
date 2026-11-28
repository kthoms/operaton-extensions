/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Reads what is really in the database, bypassing the engine. */
final class RawRows {

    private final DataSource dataSource;

    RawRows(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    List<Map<String, Object>> query(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        Object value = rs.getObject(c);
                        if (value instanceof Blob blob) {
                            value = blob.getBytes(1, (int) blob.length());
                        }
                        row.put(meta.getColumnLabel(c).toUpperCase(), value);
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    void update(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** The single runtime variable row of a name. */
    Map<String, Object> runtimeVariable(String name) {
        List<Map<String, Object>> rows = query("select * from ACT_RU_VARIABLE where NAME_ = ?", name);
        if (rows.size() != 1) {
            throw new IllegalStateException(rows.size() + " runtime rows for " + name);
        }
        return rows.get(0);
    }

    /** The newest historic variable instance row of a name. */
    Map<String, Object> historicVariable(String name) {
        List<Map<String, Object>> rows = query("select * from ACT_HI_VARINST where NAME_ = ?", name);
        if (rows.size() != 1) {
            throw new IllegalStateException(rows.size() + " ACT_HI_VARINST rows for " + name);
        }
        return rows.get(0);
    }

    byte[] bytes(Object byteArrayId) {
        List<Map<String, Object>> rows = query("select BYTES_ from ACT_GE_BYTEARRAY where ID_ = ?", byteArrayId);
        return (byte[]) rows.get(0).get("BYTES_");
    }

    /**
     * Everything the variable tables and the byte array table hold, as one string (bytes as
     * ISO-8859-1), for assertions that a plaintext appears nowhere.
     */
    String everythingStored() {
        StringBuilder all = new StringBuilder();
        for (String table : List.of("ACT_RU_VARIABLE", "ACT_HI_VARINST", "ACT_HI_DETAIL", "ACT_GE_BYTEARRAY")) {
            for (Map<String, Object> row : query("select * from " + table)) {
                row.values().forEach(value -> {
                    if (value instanceof byte[] bytes) {
                        all.append(new String(bytes, StandardCharsets.ISO_8859_1));
                    } else if (value != null) {
                        all.append(value);
                    }
                    all.append('|');
                });
                all.append('\n');
            }
        }
        return all.toString();
    }
}
