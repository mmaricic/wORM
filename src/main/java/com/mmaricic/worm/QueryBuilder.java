package com.mmaricic.worm;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;

class QueryBuilder {
    static String buildInsertQuery(String tableName, Set<String> columns) {
        StringBuilder cols = new StringBuilder();
        StringBuilder values = new StringBuilder();

        Iterator<String> it = columns.iterator();
        while (it.hasNext()) {
            String col = it.next();
            cols.append(col);
            values.append("?");
            if (it.hasNext()) {
                cols.append(", ");
                values.append(", ");
            }
        }

        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(tableName);
        query.append(" (").append(cols.toString()).append(") ");
        query.append("VALUES (").append(values.toString()).append(");");

        return query.toString();
    }

    static String buildDeleteQuery(String tableName, String idColumn) {
        StringBuilder query = new StringBuilder("DELETE FROM ");
        query.append(tableName);
        query.append(" WHERE ");
        query.append(idColumn).append("=").append("?").append(";");

        return query.toString();
    }

    static String buildUpdateQuery(
            String tableName, Set<String> columnsToUpdate, String idColumn) {
        StringBuilder query = new StringBuilder("UPDATE ");
        query.append(tableName);
        query.append(" SET ");

        Iterator<String> it = columnsToUpdate.iterator();
        while (it.hasNext()) {
            String col = it.next();
            query.append(col);
            query.append("=");
            query.append("?");
            if (it.hasNext()) {
                query.append(", ");
            }
        }

        query.append(" WHERE ");
        query.append(idColumn).append("=").append("?").append(";");

        return query.toString();
    }

    static String buildFindByIdQuery(String tableName, String idColumn, Object idValue) {
        StringBuilder query = new StringBuilder("SELECT * FROM ");
        query.append(tableName);
        query.append(" WHERE ");
        query.append(idColumn).append("=").append(objToString(idValue)).append(";");

        return query.toString();
    }

    static String objToString(Object obj) {
        if (obj == null)
            return "NULL";

        String res = obj.toString();
        if (Number.class.isAssignableFrom(obj.getClass()))
            return res;

        if (obj instanceof Boolean)
            return (boolean) obj ? "1" : "0";

        if (obj instanceof Date)
            res = new java.sql.Date(((Date) obj).getTime()).toString();

        return "'" + res + "'";
    }
}
