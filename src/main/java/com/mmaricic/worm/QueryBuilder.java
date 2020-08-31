package com.mmaricic.worm;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;

class QueryBuilder {
    static String buildInsertQuery(String tableName, Map<String, Object> entityElements) {
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();

        Iterator<Map.Entry<String, Object>> it = entityElements.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> pair = it.next();
            columns.append(pair.getKey());
            values.append(objToString(pair.getValue()));
            if (it.hasNext()) {
                columns.append(", ");
                values.append(", ");
            }
        }

        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(tableName);
        query.append(" (").append(columns.toString()).append(") ");
        query.append("VALUES (").append(values.toString()).append(");");

        return query.toString();
    }

    static String buildDeleteQuery(String tableName, String idColumn, Object idValue) {
        StringBuilder query = new StringBuilder("DELETE FROM ");
        query.append(tableName);
        query.append(" WHERE ");
        query.append(idColumn).append("=").append(objToString(idValue)).append(";");

        return query.toString();
    }


    static String objToString(Object obj) {
        if (obj == null)
            return "NULL";
        String res = obj.toString();
        if (Number.class.isAssignableFrom(obj.getClass())) {
            return res;
        }
        if (obj instanceof Date) {
            res = new java.sql.Date(((Date) obj).getTime()).toString();
        }
        return "'" + res + "'";
    }

    static String buildUpdateQuery(
            String tableName, Map<String, Object> entityElements, String idColumn, Object idValue) {
        StringBuilder query = new StringBuilder("UPDATE ");
        query.append(tableName);
        query.append(" SET ");

        Iterator<Map.Entry<String, Object>> it = entityElements.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> pair = it.next();
            query.append(pair.getKey());
            query.append("=");
            query.append(objToString(pair.getValue()));
            if (it.hasNext()) {
                query.append(", ");
            }
        }

        query.append(" WHERE ");
        query.append(idColumn).append("=").append(objToString(idValue)).append(";");

        return query.toString();
    }

    static String buildFindByIdQuery(String tableName, String idColumn, Object idValue) {
        StringBuilder query = new StringBuilder("SELECT * FROM ");
        query.append(tableName);
        query.append(" WHERE ");
        query.append(idColumn).append("=").append(objToString(idValue)).append(";");

        return query.toString();
    }

    static String buildFindQuery(String tableName) {
        StringBuilder query = new StringBuilder("SELECT * FROM ");
        query.append(tableName);
        return query.toString();
    }
}
