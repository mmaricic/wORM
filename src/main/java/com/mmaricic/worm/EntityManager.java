package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.*;
import com.mmaricic.worm.exceptions.EntityIdException.EntityIdExceptionType;
import org.apache.commons.dbcp2.BasicDataSource;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

public class EntityManager {
    private final BasicDataSource dbSource;
    private final EntityParser entityParser;
    private final AssociationHandler associationHandler;
    private Connection activeConn;
    private Map<Class<?>, Map<Object, Object>> cache = null;

    EntityManager(BasicDataSource dbSource) {
        this.dbSource = dbSource;
        entityParser = new EntityParser();
        associationHandler = new AssociationHandler(this);
    }

    public boolean save(Object entity)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        entityParser.verifyItsEntityClass(entity.getClass());
        return save(entity, null);
    }

    public boolean update(Object entity)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        entityParser.verifyItsEntityClass(entity.getClass());
        return update(entity, null);
    }

    public boolean delete(Object entity)
            throws EntityIdException, EntityException, AnnotationException, QueryException {
        entityParser.verifyItsEntityClass(entity.getClass());
        if (entity == null)
            return false;

        associationHandler.deleteAssociations(entity);

        String tableName = entityParser.extractTableName(entity.getClass());
        AbstractMap.SimpleEntry<String, Object> id = entityParser.extractId(entity);
        String sql = QueryBuilder.buildDeleteQuery(tableName, id.getKey());
        PreparedStatement stm = null;
        boolean createdConn = false;

        try {
            createdConn = openConnection();
            stm = activeConn.prepareStatement(sql);
            stm.setObject(1, id.getValue());
            int res = stm.executeUpdate();
            Field idField = entityParser.getIdField(entity.getClass());
            idField.setAccessible(true);
            idField.set(entity, null);
            return res != 0;

        } catch (SQLException e) {
            throw new QueryException(
                    String.format("An error occurred while trying to delete an entity of class %s. Error: %s",
                            entity.getClass().getSimpleName(), e.getMessage()));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new EntityException(String.format(
                    "An error occurred while trying to reset ID for object type class %s to null. Error: %s",
                    entity.getClass().getSimpleName(), e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }
    }

    boolean save(Object entity, Map<String, Object> parentsIds)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        if (entity == null)
            return false;

        boolean autogeneratedId = entityParser.isIdAutoGenerated(entity.getClass());
        AbstractMap.SimpleEntry<String, Object> entityId = entityParser.extractId(entity);
        if (autogeneratedId && entityId.getValue() != null)
            throw new EntityIdException(entity.getClass().getSimpleName(), EntityIdExceptionType.AUTO_GENERATED_ID);
        if (!autogeneratedId && entityId.getValue() == null)
            throw new EntityIdException(entity.getClass().getSimpleName(), EntityIdExceptionType.MISSING_ID_VALUE);

        if (parentsIds == null)
            parentsIds = new LinkedHashMap<>();
        associationHandler.getAssociatedParentsIds(entity).forEach(parentsIds::putIfAbsent);

        String tableName = entityParser.extractTableName(entity.getClass());
        Map<String, Object> entityElements = entityParser.parse(entity, !autogeneratedId);
        entityElements.putAll(parentsIds);
        String sql = QueryBuilder.buildInsertQuery(tableName, entityElements);

        PreparedStatement stm = null;
        boolean createdConn = false;
        try {
            createdConn = openConnection();
            stm = activeConn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Object val : entityElements.values()) {
                stm.setObject(i++, val);
            }

            int res = stm.executeUpdate();
            if (!autogeneratedId)
                return res != 0;

            ResultSet generatedKeys = stm.getGeneratedKeys();
            if (generatedKeys.next()) {
                Field idField = entityParser.getIdField(entity.getClass());
                idField.setAccessible(true);
                idField.set(entity, extractIdFromResultSet(generatedKeys, idField.getType()));

                return associationHandler.saveAssociations(entity);
            } else
                return false;

        } catch (SQLException e) {
            throw new QueryException(
                    String.format("An error occurred while trying to save an entity of class %s. Error: %s",
                            entity.getClass().getSimpleName(), e.getMessage()));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new EntityException(String.format(
                    "An error occurred while trying to set auto-generated ID for object type class %s. Error: %s",
                    entity.getClass().getSimpleName(), e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }
    }

    boolean update(Object entity, Map<String, Object> parentIds)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        if (entity == null)
            return false;

        if (parentIds == null)
            parentIds = new LinkedHashMap<>();
        associationHandler.getAssociatedParentsIds(entity).forEach(parentIds::putIfAbsent);

        String tableName = entityParser.extractTableName(entity.getClass());
        AbstractMap.SimpleEntry<String, Object> id = entityParser.extractId(entity);
        Map<String, Object> entityElements = entityParser.parse(entity, false);
        entityElements.putAll(parentIds);

        String sql = QueryBuilder.buildUpdateQuery(tableName, entityElements, id.getKey());
        PreparedStatement stm = null;
        boolean createdConn = false;
        try {
            createdConn = openConnection();
            stm = activeConn.prepareStatement(sql);
            int i = 1;
            for (Object val : entityElements.values()) {
                stm.setObject(i++, val);
            }
            stm.setObject(i, id.getValue());

            int res = stm.executeUpdate();

            if (res == 0)
                return false;
            if (!associationHandler.saveAssociations(entity))
                return false;
            associationHandler.removeOldAssociationLinks(entity);
            return true;

        } catch (SQLException e) {
            throw new QueryException(
                    String.format("An error occurred while trying to update an entity of class %s. Error: %s",
                            entity.getClass().getSimpleName(), e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }
    }

    private Object extractIdFromResultSet(ResultSet generatedKeys, Class<?> type)
            throws SQLException, AnnotationException {
        if (type.isAssignableFrom(Integer.class))
            return generatedKeys.getInt(1);
        if (type.isAssignableFrom(Long.class))
            return generatedKeys.getLong(1);
        return generatedKeys.getObject(1);
    }

    public <T> T find(Class<T> entityClass, Object id)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        entityParser.verifyItsEntityClass(entityClass);
        T entity = (T) getFromCache(entityClass, id);
        if (entity != null)
            return entity;

        String idColumn = entityParser.extractIdColumnName(entityClass);
        String tableName = entityParser.extractTableName(entityClass);
        String sql = QueryBuilder.buildFindByIdQuery(tableName, idColumn, id);

        Statement stm = null;
        boolean createdConn = false;
        try {
            createdConn = openConnection();
            stm = activeConn.createStatement();
            ResultSet resultSet = stm.executeQuery(sql);

            List<Map<String, Object>> entityMaps = convertResultSetToListOfMaps(resultSet);
            List<T> result = convertListOfMapsToListOfEntities(entityClass, entityMaps);

            if (result.size() > 0)
                return result.get(0);

        } catch (SQLException e) {
            throw new QueryException(String.format(
                    "An error occurred while searching for an entity of class: %s with an id: %s. Error: %s",
                    entityClass.getSimpleName(), id.toString(), e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }
        return null;
    }

    public <T> LazyList<T> find(Class<T> entityClass) throws AnnotationException {
        entityParser.verifyItsEntityClass(entityClass);
        String tableName = entityParser.extractTableName(entityClass);
        String sql = "SELECT * FROM " + tableName;
        boolean whereAdded = false;
        if (entityClass.getSuperclass() != Object.class) {
            sql = sql + " WHERE " +
                    entityParser.getDiscriminatorColumnName(entityClass)
                    + "=" + QueryBuilder.objToString(entityParser.getDiscriminatorValue(entityClass));
            whereAdded = true;
        }
        return new LazyList<>(sql, entityClass, this, false, whereAdded);
    }

    public List<Map<String, Object>> query(String sql) throws QueryException {
        Statement stm = null;
        boolean createdConn = false;
        try {
            createdConn = openConnection();
            stm = activeConn.createStatement();
            ResultSet resultSet = stm.executeQuery(sql);
            return convertResultSetToListOfMaps(resultSet);

        } catch (SQLException e) {
            throw new QueryException(String.format("An error occurred while executing query:'%s'. Error: %s",
                    sql, e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }
    }

    public <T> List<T> query(String sql, Class<T> entityClass)
            throws AnnotationException, EntityLoaderException, EntityIdException, EntityException, QueryException {
        entityParser.verifyItsEntityClass(entityClass);
        List<Map<String, Object>> rows = query(sql);
        return convertListOfMapsToListOfEntities(entityClass, rows);
    }

    public List<Map<String, Object>> preparedQuery(String sql, Object... args) throws QueryException {
        PreparedStatement stm = null;
        boolean createdConn = false;
        try {
            createdConn = openConnection();
            stm = activeConn.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                stm.setObject(i + 1, args[i]);
            }
            ResultSet resultSet = stm.executeQuery();
            return convertResultSetToListOfMaps(resultSet);

        } catch (SQLException e) {
            throw new QueryException(String.format("An error occurred while executing query:'%s'. Error: %s",
                    sql, e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }
    }

    public <T> List<T> preparedQuery(Class<T> entityClass, String sql, Object... args)
            throws AnnotationException, EntityLoaderException, EntityIdException, EntityException, QueryException {
        entityParser.verifyItsEntityClass(entityClass);
        List<Map<String, Object>> rows = preparedQuery(sql, args);
        return convertListOfMapsToListOfEntities(entityClass, rows);
    }

    void executeUpdate(String sql, Object... args) throws QueryException {
        PreparedStatement stm = null;
        boolean createdConn = false;
        try {
            createdConn = openConnection();
            stm = activeConn.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) {
                stm.setObject(i + 1, args[i]);
            }
            stm.executeUpdate();

        } catch (SQLException e) {
            throw new QueryException(String.format("An error occurred while executing query:'%s'. Error: %s",
                    sql, e.getMessage()));
        } finally {
            commitAndClose(createdConn, stm);
        }

    }

    private boolean openConnection() throws SQLException {
        if (activeConn == null) {
            activeConn = dbSource.getConnection();
            activeConn.setAutoCommit(false);
            return true;
        }
        return false;
    }

    private void commitAndClose(boolean closeConn, Statement stm) {
        try {
            if (closeConn && activeConn != null)
                activeConn.commit();
            if (stm != null) stm.close();
            if (closeConn && activeConn != null) {
                activeConn.close();
                activeConn = null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private List<Map<String, Object>> convertResultSetToListOfMaps(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> res = new ArrayList<>();
        while (resultSet.next()) {
            ResultSetMetaData meta = resultSet.getMetaData();
            int colCount = meta.getColumnCount();
            Map<String, Object> mappedRow = new HashMap<>();
            for (int i = 1; i <= colCount; i++) {
                String colName = meta.getColumnLabel(i);
                Object colValue = resultSet.getObject(i);
                mappedRow.put(colName, colValue);
            }
            res.add(mappedRow);
        }
        return res;
    }

    private <T> List<T> convertListOfMapsToListOfEntities(Class<T> entityClass, List<Map<String, Object>> entityMaps)
            throws EntityLoaderException, EntityIdException, EntityException {
        boolean cacheInit = false;
        if (cache == null) {
            cacheInit = true;
            cache = new HashMap<>();
        }

        Map<T, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> entityMap : entityMaps) {
            T entity = entityParser.convertRowToEntity(entityClass, entityMap, null);
            addToCache(entity);
            result.put(entity, entityMap);
        }

        for (Map.Entry<T, Map<String, Object>> resultEntry : result.entrySet()) {
            associationHandler.fetchAssociations(resultEntry.getKey(), resultEntry.getValue());
        }

        if (cacheInit)
            cache = null;

        return new ArrayList<>(result.keySet());
    }

    private void addToCache(Object entity) throws EntityIdException, EntityException {
        Map<Object, Object> classCache = cache.computeIfAbsent(entity.getClass(), k -> new HashMap<>());
        classCache.put(entityParser.extractId(entity).getValue(), entity);
    }

    Object getFromCache(Class<?> entityClass, Object id) {
        if (cache == null)
            return null;
        Map<Object, Object> classCache = cache.get(entityClass);
        if (classCache == null)
            return null;
        return classCache.get(id);
    }
}
