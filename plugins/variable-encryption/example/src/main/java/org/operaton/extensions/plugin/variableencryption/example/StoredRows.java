/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.example;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shows what the database really holds for a process instance, bypassing the engine: the variable
 * rows of the runtime table, the history tables and their byte arrays.
 */
@Component
public class StoredRows {

    /**
     * One variable row.
     *
     * @param table the table
     * @param variable the variable name
     * @param serializer the stored serializer (type)
     * @param text the text column
     * @param longValue the long column
     * @param bytes the length of the stored byte array, or {@code null}
     * @param bytesHex the first bytes of the byte array in hex, or {@code null}
     * @param content the complete byte array (not part of the JSON view)
     */
    public record Row(
            String table,
            String variable,
            String serializer,
            String text,
            Long longValue,
            Integer bytes,
            String bytesHex,
            @JsonIgnore byte[] content) {}

    private static final Map<String, String> QUERIES = Map.of(
            "ACT_RU_VARIABLE",
            "select NAME_, TYPE_, TEXT_, LONG_, BYTEARRAY_ID_ from ACT_RU_VARIABLE where PROC_INST_ID_ = ?",
            "ACT_HI_VARINST",
            "select NAME_, VAR_TYPE_, TEXT_, LONG_, BYTEARRAY_ID_ from ACT_HI_VARINST where PROC_INST_ID_ = ?",
            "ACT_HI_DETAIL",
            "select NAME_, VAR_TYPE_, TEXT_, LONG_, BYTEARRAY_ID_ from ACT_HI_DETAIL"
                    + " where PROC_INST_ID_ = ? and TYPE_ = 'VariableUpdate'");

    private final JdbcTemplate jdbc;

    public StoredRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The rows of a process instance.
     *
     * @param processInstanceId the process instance
     * @return the variable rows of the three tables
     */
    public List<Row> of(String processInstanceId) {
        List<Row> rows = new ArrayList<>();
        for (String table : List.of("ACT_RU_VARIABLE", "ACT_HI_VARINST", "ACT_HI_DETAIL")) {
            jdbc.query(
                    QUERIES.get(table),
                    rs -> {
                        String byteArrayId = rs.getString(5);
                        byte[] content = byteArrayId == null
                                ? null
                                : jdbc.queryForObject(
                                        "select BYTES_ from ACT_GE_BYTEARRAY where ID_ = ?", byte[].class, byteArrayId);
                        long longColumn = rs.getLong(4);
                        Long longValue = rs.wasNull() ? null : longColumn;
                        rows.add(new Row(
                                table,
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                longValue,
                                content == null ? null : content.length,
                                content == null
                                        ? null
                                        : HexFormat.of().formatHex(content, 0, Math.min(content.length, 24)),
                                content));
                    },
                    processInstanceId);
        }
        return rows;
    }
}
