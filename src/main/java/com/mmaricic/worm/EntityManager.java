package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.QueryException;
import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.*;
import java.util.*;

public class EntityManager {
    private final BasicDataSource dbSource;
    private final EntityParser entityParser;

    public EntityManager(BasicDataSource dbSource) {
        this.dbSource = dbSource;
        entityParser = new EntityParser();
    }

    // TODO: srediti povratnu vrednost
    public int save(Object entity) {
        String tableName = entityParser.extactTableName(entity.getClass());
        Map<String, Object> entityElements = entityParser.parse(entity, true);
        String sql = QueryBuilder.buildInsertQuery(tableName, entityElements);
        try (Connection conn = dbSource.getConnection();
             Statement stm = conn.createStatement()) {
            int res = stm.executeUpdate(sql);
            return res;
        } catch (SQLException e) {
            throw new QueryException(
                    String.format("An error occurred while trying to save an entity of class %s. Error: %s",
                            entity.getClass().getSimpleName(), e.getMessage()));
        }
    }

    public boolean delete(Object entity) {
        String tableName = entityParser.extactTableName(entity.getClass());
        AbstractMap.SimpleEntry<String, Object> id = entityParser.extractId(entity);
        String sql = QueryBuilder.buildDeleteQuery(tableName, id.getKey(), id.getValue());
        try (Connection conn = dbSource.getConnection();
             Statement stm = conn.createStatement()) {
            int res = stm.executeUpdate(sql);
            return res != 0;
        } catch (SQLException e) {
            throw new QueryException(
                    String.format("An error occurred while trying to delete an entity of class %s. Error: %s",
                            entity.getClass().getSimpleName(), e.getMessage()));
        }
    }

    public boolean update(Object entity) {
        String tableName = entityParser.extactTableName(entity.getClass());
        AbstractMap.SimpleEntry<String, Object> id = entityParser.extractId(entity);
        Map<String, Object> entityElements = entityParser.parse(entity, false);
        String sql = QueryBuilder.buildUpdateQuery(tableName, entityElements, id.getKey(), id.getValue());
        try (Connection conn = dbSource.getConnection();
             Statement stm = conn.createStatement()) {
            int res = stm.executeUpdate(sql);
            return res != 0;
        } catch (SQLException e) {
            throw new QueryException(
                    String.format("An error occurred while trying to update an entity of class %s. Error: %s",
                            entity.getClass().getSimpleName(), e.getMessage()));
        }
    }

    public <T> T find(Class<T> entityClass, Object id) {
        String idColumn = entityParser.extractIdColumnName(entityClass);
        String tableName = entityParser.extactTableName(entityClass);
        String sql = QueryBuilder.buildFindByIdQuery(tableName, idColumn, id);

        try (Connection conn = dbSource.getConnection();
             Statement stm = conn.createStatement()) {
            ResultSet resultSet = stm.executeQuery(sql);
            List<T> result = convertResultSetToEntity(resultSet, entityClass);

            if (result.size() > 0)
                return result.get(0);
        } catch (SQLException e) {
            throw new QueryException(String.format("An error occurred while searching for an entity of class: %s with an id: %s. Error: %s",
                    entityClass.getSimpleName(), id.toString(), e.getMessage()));
        }
        return null;


    }

    private <T> List<T> convertResultSetToEntity(ResultSet resultSet, Class<T> entityClass) throws SQLException {
        List<T> result = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> entityElements = convertRowToMap(resultSet);
            result.add(entityParser.convertRowToEntity(entityClass, entityElements, null));
        }
        return result;
    }


    private Map<String, Object> convertRowToMap(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int colCount = meta.getColumnCount();
        Map<String, Object> mappedRow = new HashMap<>();
        for (int i = 1; i <= colCount; i++) {
            String colName = meta.getColumnLabel(i);
            Object colValue = resultSet.getObject(i);
            mappedRow.put(colName, colValue);
        }
        return mappedRow;
    }

    public <T> LazyList<T> find(Class<T> entityClass) {
        String tableName = entityParser.extactTableName(entityClass);
        String sql = QueryBuilder.buildFindQuery(tableName);
        return new LazyList<>(sql, entityClass, this);
    }

    public List<Map<String, Object>> query(String sql) {
        try (Connection conn = dbSource.getConnection();
             Statement stm = conn.createStatement()) {
            ResultSet resultSet = stm.executeQuery(sql);

            List<Map<String, Object>> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(convertRowToMap(resultSet));
            }
            return result;
        } catch (SQLException e) {
            throw new QueryException(String.format("An error occurred while executing query:'%s'. Error: %s",
                    sql, e.getMessage()));
        }
    }

}
