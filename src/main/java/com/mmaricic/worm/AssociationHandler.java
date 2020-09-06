package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.*;
import net.sf.cglib.proxy.Enhancer;

import javax.persistence.*;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class AssociationHandler {
    private final EntityManager em;
    private final EntityParser ep;

    AssociationHandler(EntityManager entityManager) {
        em = entityManager;
        ep = new EntityParser();
    }

    Map<String, Object> getAssociatedParentsIds(Object entity) throws EntityException, EntityIdException {
        Map<String, Object> ids = new HashMap<>();
        try {
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(this::isParentAssociation)
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    assocField.setAccessible(true);
                    Object parent = assocField.get(entity);
                    AbstractMap.SimpleEntry<String, Object> parentId =
                            getParentId(parent, assocField, assocField.getType(), entity.getClass());
                    ids.put(parentId.getKey(), parentId.getValue());
                }
            } else {
                List<Method> associations = Stream
                        .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                        .map(PropertyDescriptor::getReadMethod)
                        .filter(this::isParentAssociation)
                        .collect(Collectors.toList());

                for (Method getMethod : associations) {
                    Object parent = getMethod.invoke(entity);
                    AbstractMap.SimpleEntry<String, Object> parentId =
                            getParentId(parent, getMethod, getMethod.getReturnType(), entity.getClass());
                    ids.put(parentId.getKey(), parentId.getValue());
                }
            }
        } catch (IllegalAccessException | IntrospectionException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return ids;
    }

    private AbstractMap.SimpleEntry<String, Object> getParentId(
            Object parent, AnnotatedElement parentAnnotation, Class<?> parentClass, Class<?> entityClass) {
        Object value = null;
        String key = extractForeignKeyColumnName(parentAnnotation, parentClass, entityClass);
        if (parent != null) {
            value = extractForeignKeyValue(parent, entityClass);
        }
        return new AbstractMap.SimpleEntry<>(key, value);
    }

    private boolean isParentAssociation(AnnotatedElement ae) {
        return ae != null
                && (ae.getAnnotation(ManyToOne.class) != null
                || (ae.getAnnotation(OneToOne.class) != null
                && ae.getAnnotation(OneToOne.class).mappedBy().equals("")));
    }

    private String extractForeignKeyColumnName(AnnotatedElement ae, Class<?> annotatedElementClass, Class<?> entityClass) {
        if (ae.getAnnotation(JoinColumn.class) != null)
            return ae.getAnnotation(JoinColumn.class).name();

        String mappedBy = getMappedBy(ae);
        if (!mappedBy.isEmpty()) {
            Field field;
            try {
                field = annotatedElementClass.getDeclaredField(mappedBy);
            } catch (NoSuchFieldException e) {
                throw new EntityException(e.getMessage());
            }
            if (field.getAnnotation(JoinColumn.class) == null) {
                try {
                    PropertyDescriptor descriptor = new PropertyDescriptor(mappedBy, annotatedElementClass);
                    Method getMethod = descriptor.getReadMethod();
                    if (getMethod != null && getMethod.getAnnotation(JoinColumn.class) != null) {
                        return getMethod.getAnnotation(JoinColumn.class).name();
                    }
                } catch (IntrospectionException ignored) {
                }
            }
            return ep.extractTableName(entityClass) + "_id";
        }

        if (ae.getAnnotation(OneToMany.class) != null)
            return ep.extractTableName(entityClass) + "_id";

        return ep.extractTableName(annotatedElementClass) + "_id";
    }

    private Object extractForeignKeyValue(Object relatedEntity, Class<?> entityClass)
            throws EntityIdException, EntityException {
        Object id = ep.extractId(relatedEntity).getValue();
        if (id == null) {
            throw new EntityException(String.format(
                    "Entity of class %s referenced by foreign key in class %s is not inserted in to the " +
                            "database. Upade or insert of weak entity will not insert the strong one. " +
                            "Please insert it first.",
                    relatedEntity.getClass().getSimpleName(), entityClass.getSimpleName()));
        }
        return id;
    }

    boolean saveAssociations(Object entity) throws EntityIdException {
        try {
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(this::isChildAssociation)
                        .collect(Collectors.toList());
                for (Field assocField : associations) {
                    assocField.setAccessible(true);
                    Object fieldValue = assocField.get(entity);

                    if (!saveSingleAssociation(fieldValue, assocField, entity))
                        return false;
                }
                return true;
            }

            List<Method> associations = Stream
                    .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .map(PropertyDescriptor::getReadMethod)
                    .filter(this::isChildAssociation)
                    .collect(Collectors.toList());
            for (Method getMethod : associations) {
                Object fieldValue = getMethod.invoke(entity);

                if (!saveSingleAssociation(fieldValue, getMethod, entity))
                    return false;
            }
            return true;

        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean saveSingleAssociation(Object fieldValue, AnnotatedElement fieldAnnot, Object entity) {
        Set<CascadeType> cascades = getCascadeTypes(fieldAnnot);
        if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.PERSIST))
            return true;

        if (fieldValue == null)
            return true;

        if (Collection.class.isAssignableFrom(fieldValue.getClass()))
            return saveCollection(entity, fieldValue, fieldAnnot);

        String key = extractForeignKeyColumnName(fieldAnnot, fieldValue.getClass(), entity.getClass());
        Object value = extractForeignKeyValue(entity, entity.getClass());
        return updateChild(fieldValue, new HashMap<>(Map.of(key, value)));
    }

    private boolean saveCollection(Object entity, Object fieldValue, AnnotatedElement fieldAnnot) {
        if (fieldAnnot.getAnnotation(ManyToMany.class) != null) {
            for (Object child : (Collection) fieldValue) {
                if (ep.extractId(child).getValue() == null) {
                    em.save(child);
                }

                ManyToManyTableId association = getManyToManyTableAndCols(entity.getClass(), child.getClass(), fieldAnnot);
                Object entityColValue = ep.extractId(entity).getValue();
                Object childColValue = ep.extractId(child).getValue();
                List<Map<String, Object>> res = em.query(String.format("SELECT * FROM %s WHERE %s=%s AND %s=%s;",
                        association.tableName,
                        association.entityCol, entityColValue,
                        association.childCol, childColValue));

                if (res.isEmpty()) {
                    LinkedHashMap<String, Object> vals = new LinkedHashMap<>();
                    vals.put(association.entityCol, entityColValue);
                    vals.put(association.childCol, childColValue);
                    String query = QueryBuilder.buildInsertQuery(association.tableName, vals);
                    em.executeUpdate(query, entityColValue, childColValue);
                }
            }
            return true;
        }

        for (Object child : (Collection) fieldValue) {
            String key = extractForeignKeyColumnName(fieldAnnot, child.getClass(), entity.getClass());
            Object value = extractForeignKeyValue(entity, entity.getClass());
            if (!updateChild(child, new HashMap<>(Map.of(key, value))))
                return false;
        }
        return true;
    }

    private ManyToManyTableId getManyToManyTableAndCols(Class<?> entityClass, Class<?> childClass, AnnotatedElement ae)
            throws EntityIdException, EntityException {
        ManyToManyTableId ids = new ManyToManyTableId();
        ids.tableName = ep.extractTableName(entityClass) + "_" + ep.extractTableName(childClass);
        ids.entityCol = ep.extractTableName(entityClass) + "_id";
        ids.childCol = ep.extractTableName(childClass) + "_id";

        JoinTable jt = ae.getAnnotation(JoinTable.class);
        String mappedBy = ae.getAnnotation(ManyToMany.class).mappedBy();
        if (jt != null) {
            if (!jt.name().isEmpty()) {
                ids.tableName = jt.name();
            }
            for (JoinColumn jc : jt.joinColumns()) {
                ids.entityCol = jc.name();
            }
            for (JoinColumn jc : jt.inverseJoinColumns()) {
                ids.childCol = jc.name();
            }
        }

        if (jt == null && !mappedBy.isEmpty()) {
            ids.tableName = ep.extractTableName(childClass) + "_" + ep.extractTableName(entityClass);
            Field field;
            try {
                field = childClass.getDeclaredField(mappedBy);
            } catch (NoSuchFieldException e) {
                throw new EntityException(e.getMessage());
            }
            if (field.getAnnotation(JoinTable.class) == null) {
                try {
                    PropertyDescriptor descriptor = new PropertyDescriptor(mappedBy, childClass);
                    Method getMethod = descriptor.getReadMethod();
                    if (getMethod != null && getMethod.getAnnotation(JoinTable.class) != null) {
                        jt = getMethod.getAnnotation(JoinTable.class);
                    }
                } catch (IntrospectionException ignored) {
                }
            } else {
                jt = field.getAnnotation(JoinTable.class);
            }
            if (jt != null) {
                if (!jt.name().isEmpty()) {
                    ids.tableName = jt.name();
                }
                for (JoinColumn jc : jt.joinColumns()) {
                    ids.childCol = jc.name();
                }
                for (JoinColumn jc : jt.inverseJoinColumns()) {
                    ids.entityCol = jc.name();
                }
            }
        }
        return ids;
    }

    private String getMappedBy(AnnotatedElement ae) {
        OneToMany oneToMany = ae.getAnnotation(OneToMany.class);
        if (oneToMany != null)
            return oneToMany.mappedBy();
        OneToOne oneToOne = ae.getAnnotation(OneToOne.class);
        if (oneToOne != null)
            return oneToOne.mappedBy();
        ManyToMany manyToMany = ae.getAnnotation(ManyToMany.class);
        if (manyToMany != null)
            return manyToMany.mappedBy();
        return "";
    }

    private Set<CascadeType> getCascadeTypes(AnnotatedElement ae) {
        OneToMany oneToMany = ae.getAnnotation(OneToMany.class);
        if (oneToMany != null)
            return new HashSet<>(Arrays.asList(oneToMany.cascade()));
        OneToOne oneToOne = ae.getAnnotation(OneToOne.class);
        if (oneToOne != null)
            return new HashSet<>(Arrays.asList(oneToOne.cascade()));

        return new HashSet<>(Arrays.asList(ae.getAnnotation(ManyToMany.class).cascade()));

    }

    private boolean updateChild(Object child, Map<String, Object> associationId)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        if (child == null) {
            return true;
        }
        AbstractMap.SimpleEntry<String, Object> childId = ep.extractId(child);
        if (childId.getValue() != null) {
            return em.update(child, associationId);
        }
        return em.save(child, associationId);
    }

    void deleteAssociations(Object entity) throws EntityIdException {
        try {
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(this::isChildAssociation)
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    assocField.setAccessible(true);
                    Object fieldValue = assocField.get(entity);
                    if (fieldValue == null)
                        continue;
                    if (Collection.class.isAssignableFrom(fieldValue.getClass()))
                        for (Object child : (Collection) fieldValue)
                            deleteSingleAssociation(child, assocField, entity);
                    else
                        deleteSingleAssociation(fieldValue, assocField, entity);
                }
                return;
            }
            List<Method> associations = Stream
                    .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .map(PropertyDescriptor::getReadMethod)
                    .filter(this::isChildAssociation)
                    .collect(Collectors.toList());

            for (Method getMethod : associations) {
                Object fieldValue = getMethod.invoke(entity);
                if (fieldValue == null)
                    continue;
                if (Collection.class.isAssignableFrom(getMethod.getReturnType()))
                    for (Object child : (Collection) fieldValue)
                        deleteSingleAssociation(child, getMethod, entity);
                else
                    deleteSingleAssociation(fieldValue, getMethod, entity);
            }
        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            e.printStackTrace();
        }
    }

    private void deleteSingleAssociation(Object fieldValue, AnnotatedElement fieldAnnot, Object entity) {
        Set<CascadeType> cascades = getCascadeTypes(fieldAnnot);
        if (cascades.contains(CascadeType.ALL)
                || cascades.contains(CascadeType.REMOVE)
                || removeOrphans(fieldAnnot))
            em.delete(fieldValue);

        if (fieldAnnot.getAnnotation(ManyToMany.class) != null) {
            Object entityColValue = ep.extractId(entity).getValue();
            ManyToManyTableId association = getManyToManyTableAndCols(entity.getClass(), fieldValue.getClass(), fieldAnnot);
            em.executeUpdate(QueryBuilder.buildDeleteQuery(
                    association.tableName, association.entityCol), entityColValue);
        }
    }

    private boolean removeOrphans(AnnotatedElement ae) {
        OneToMany oneToMany = ae.getAnnotation(OneToMany.class);
        if (oneToMany != null)
            return oneToMany.orphanRemoval();
        OneToOne oneToOne = ae.getAnnotation(OneToOne.class);
        if (oneToOne != null)
            return oneToOne.orphanRemoval();

        return false;
    }


    void fetchAssociations(Object entity, Map<String, Object> entityMap) throws EntityIdException {
        if (ep.isIddAnnotationOnField(entity.getClass())) {
            List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                    .filter(this::isAssociation)
                    .collect(Collectors.toList());
            for (Field assocField : associations) {
                try {
                    assocField.setAccessible(true);

                    if (assocField.getAnnotation(OneToMany.class) != null) {
                        ParameterizedType collectionType = (ParameterizedType) assocField.getGenericType();
                        Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                        assocField.set(entity, fetchOneToMany(entity, assocField, childrenType));
                    }
                    if (assocField.getAnnotation(ManyToOne.class) != null
                            || assocField.getAnnotation(OneToOne.class) != null
                            && assocField.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                        assocField.set(entity, fetchParent(entity, assocField, assocField.getType(), entityMap));
                    }
                    if (assocField.getAnnotation(OneToOne.class) != null
                            && !assocField.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                        assocField.set(entity, fetchOneToOne(entity, assocField, assocField.getType()));
                    }
                    if (assocField.getAnnotation(ManyToMany.class) != null) {
                        ParameterizedType collectionType = (ParameterizedType) assocField.getGenericType();
                        Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                        assocField.set(entity, fetchManyToMany(entity, childrenType, assocField));
                    }
                } catch (IllegalAccessException e) {
                    throw new EntityLoaderException(String.format("Field %s in class %s is inaccessible.",
                            assocField.getName(), entity.getClass().getSimpleName()));
                }
            }
            return;
        }
        try {
            List<PropertyDescriptor> associations = Stream
                    .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .filter(descriptor -> isAssociation(descriptor.getReadMethod()))
                    .collect(Collectors.toList());

            for (PropertyDescriptor descriptor : associations) {
                Method getMethod = descriptor.getReadMethod();
                Method setMehod = descriptor.getWriteMethod();
                if (getMethod == null)
                    continue;
                Object value = null;

                if (getMethod.getAnnotation(OneToMany.class) != null) {
                    ParameterizedType collectionType = (ParameterizedType) getMethod.getGenericReturnType();
                    Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                    value = fetchOneToMany(entity, getMethod, childrenType);
                }
                if (getMethod.getAnnotation(ManyToOne.class) != null
                        || getMethod.getAnnotation(OneToOne.class) != null
                        && getMethod.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                    value = fetchParent(entity, getMethod, getMethod.getReturnType(), entityMap);
                }
                if (getMethod.getAnnotation(OneToOne.class) != null
                        && !getMethod.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                    value = fetchOneToOne(entity, getMethod, getMethod.getReturnType());
                }
                if (getMethod.getAnnotation(ManyToMany.class) != null) {
                    ParameterizedType collectionType = (ParameterizedType) getMethod.getGenericReturnType();
                    Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                    value = fetchManyToMany(entity, childrenType, getMethod);
                }
                if (setMehod != null)
                    setMehod.invoke(entity, value);
                else {
                    Field f = entity.getClass().getDeclaredField(descriptor.getName());
                    f.setAccessible(true);
                    f.set(entity, value);
                }
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException | NoSuchFieldException e) {
            throw new EntityLoaderException("An error occurred while trying to set association fields for "
                    + entity.getClass().getSimpleName() + "entity: " + e.getMessage());
        }
    }

    private List<?> fetchManyToMany(Object entity, Class<?> manyToManyType, AnnotatedElement manyToManyAnnot) {
        ManyToManyTableId manyToManyTableId = getManyToManyTableAndCols(entity.getClass(), manyToManyType, manyToManyAnnot);
        Object entityId = ep.extractId(entity).getValue();
        String childTable = ep.extractTableName(manyToManyType);
        String childIdInTable = ep.extractIdColumnName(manyToManyType);
        String selectAllQuery = String.format(
                "SELECT %1$s.* FROM %1$s INNER JOIN %2$s ON %1$s.%3$s=%2$s.%4$s WHERE %2$s.%5$s=%6$s",
                childTable, manyToManyTableId.tableName, childIdInTable, manyToManyTableId.childCol,
                manyToManyTableId.entityCol, QueryBuilder.objToString(entityId));
        if (manyToManyAnnot.getAnnotation(ManyToMany.class).fetch() == FetchType.LAZY) {
            return new LazyList<>(selectAllQuery + ";", manyToManyType, em, true, false);
        }
        String selectIds = String.format("SELECT %s FROM %s WHERE %s=%s;",
                manyToManyTableId.childCol,
                manyToManyTableId.tableName,
                manyToManyTableId.entityCol,
                QueryBuilder.objToString(entityId));
        List<Map<String, Object>> idQueryRes = em.query(selectIds);
        List<Object> existingEntites = new ArrayList<>();
        StringJoiner idsNotToFetch = new StringJoiner(" ");
        for (Map<String, Object> childIdMap : idQueryRes) {
            Object childId = childIdMap.get(manyToManyTableId.childCol);
            Object child = em.getFromCache(manyToManyType, childId);
            if (child != null) {
                existingEntites.add(child);
                idsNotToFetch.add("AND");
                idsNotToFetch.add(manyToManyTableId.childCol).add("<>").add(QueryBuilder.objToString(childId));
            }
        }
        if (existingEntites.size() < idQueryRes.size()) {
            List<?> res = em.query(selectAllQuery + idsNotToFetch.toString() + ";", manyToManyType);
            existingEntites.addAll(res);
        }
        return existingEntites;
    }

    private <T> Object fetchParent(T entity, AnnotatedElement ae, Class<?> oneType, Map<String, Object> entityMap)
            throws EntityIdException, EntityException, AnnotationException, EntityLoaderException, QueryException {
        String oneTableName = ep.extractTableName(oneType);
        String foreignKeyName = extractForeignKeyColumnName(ae, oneType, entity.getClass());
        Object idValue = entityMap.get(foreignKeyName);
        if (idValue == null)
            return null;

        if (ae.getAnnotation(ManyToOne.class) != null
                && ae.getAnnotation(ManyToOne.class).fetch() == FetchType.EAGER
                || ae.getAnnotation(OneToOne.class) != null
                && ae.getAnnotation(OneToOne.class).fetch() == FetchType.EAGER) {
            return em.find(oneType, idValue);
        } else {
            String idColumn = ep.extractIdColumnName(oneType);
            String query = QueryBuilder.buildFindByIdQuery(oneTableName, idColumn, idValue);
            EntityProxy llp = new EntityProxy<>(oneType, em, query);
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(oneType);
            enhancer.setCallback(llp);
            return enhancer.create();
        }
    }

    private <T> String composeFetchChildrenQuery(T entity, AnnotatedElement ae, Class<?> childrenType)
            throws EntityIdException, EntityException {
        String queryTemplate = "SELECT * FROM %1$s WHERE %1$s.%2$s=%3$s";

        String childTableName = ep.extractTableName(childrenType);
        String parentForeignKeyIdCol = extractForeignKeyColumnName(ae, childrenType, entity.getClass());
        AbstractMap.SimpleEntry<String, Object> parentId = ep.extractId(entity);

        return String.format(queryTemplate,
                childTableName, parentForeignKeyIdCol, parentId.getValue());
    }

    private List<?> fetchOneToMany(Object entity, AnnotatedElement childAnnot, Class<?> childrenType)
            throws AnnotationException, EntityLoaderException, EntityIdException, EntityException, QueryException {
        String query = composeFetchChildrenQuery(entity, childAnnot, childrenType);
        if (childAnnot.getAnnotation(OneToMany.class).fetch() == FetchType.EAGER) {
            return em.query(query, childrenType);
        }
        return new LazyList<>(query, childrenType, em, true, false);
    }

    private Object fetchOneToOne(Object entity, AnnotatedElement childAnnot, Class<?> childrenType)
            throws AnnotationException, EntityLoaderException, EntityIdException, EntityException, QueryException {
        String query = composeFetchChildrenQuery(entity, childAnnot, childrenType);
        if (childAnnot.getAnnotation(OneToOne.class).fetch() == FetchType.EAGER) {
            List<?> res = em.query(query, childrenType);
            if (res.size() == 0)
                return null;
            return res.get(0);
        }

        EntityProxy llp = new EntityProxy<>(childrenType, em, query);
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(childrenType);
        enhancer.setCallback(llp);
        return enhancer.create();
    }

    private boolean isChildAssociation(AnnotatedElement ae) {
        return ae != null
                && (ae.getAnnotation(OneToMany.class) != null
                || ae.getAnnotation(ManyToMany.class) != null
                || (ae.getAnnotation(OneToOne.class) != null
                && !ae.getAnnotation(OneToOne.class).mappedBy().equals("")));
    }

    private boolean isAssociation(AnnotatedElement ae) {
        return ae != null
                && (ae.getAnnotation(OneToMany.class) != null
                || ae.getAnnotation(ManyToOne.class) != null
                || ae.getAnnotation(ManyToMany.class) != null
                || ae.getAnnotation(OneToOne.class) != null);

    }

    void removeOldAssociationLinks(Object entity) throws EntityIdException {
        try {
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(this::isAssociation)
                        .collect(Collectors.toList());
                for (Field field : associations) {
                    field.setAccessible(true);
                    if (field.getAnnotation(OneToMany.class) != null) {
                        ParameterizedType collectionType = (ParameterizedType) field.getGenericType();
                        Class<?> childType = (Class<?>) collectionType.getActualTypeArguments()[0];
                        Object children = field.get(entity);
                        removeLinksForChildren(entity, field, childType, children);
                    }
                    if (field.getAnnotation(OneToOne.class) != null &&
                            !field.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                        Object child = field.get(entity);
                        removeLinksForChildren(entity, field, field.getType(),
                                child == null ? null : Collections.singletonList(child));
                    }
                    if (field.getAnnotation(ManyToMany.class) != null) {
                        ParameterizedType collectionType = (ParameterizedType) field.getGenericType();
                        Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                        Object children = field.get(entity);
                        removeOldAssociationTableEntries(entity, children, childrenType, field);
                    }
                }
                return;
            }
            List<Method> associations = Stream.
                    of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .map(PropertyDescriptor::getReadMethod)
                    .filter(this::isAssociation)
                    .collect(Collectors.toList());
            for (Method method : associations) {
                if (method.getAnnotation(OneToMany.class) != null) {
                    ParameterizedType collectionType = (ParameterizedType) method.getGenericReturnType();
                    Class<?> childType = (Class<?>) collectionType.getActualTypeArguments()[0];
                    Object children = method.invoke(entity);
                    removeLinksForChildren(entity, method, childType, children);
                }
                if (method.getAnnotation(OneToOne.class) != null &&
                        !method.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                    Object child = method.invoke(entity);
                    removeLinksForChildren(entity, method, method.getReturnType(),
                            child == null ? null : Collections.singletonList(child));
                }
                if (method.getAnnotation(ManyToMany.class) != null) {
                    ParameterizedType collectionType = (ParameterizedType) method.getGenericReturnType();
                    Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                    removeOldAssociationTableEntries(entity, method.invoke(entity), childrenType, method);
                }
            }
        } catch (IllegalAccessException | IntrospectionException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    private void removeOldAssociationTableEntries(
            Object entity, Object relatedEntites, Class<?> relatedEntityClass, AnnotatedElement relatedEntityAnnot) {
        ManyToManyTableId manyToManyTableId = getManyToManyTableAndCols(
                entity.getClass(), relatedEntityClass, relatedEntityAnnot);
        String sql = String.format("DELETE FROM %1$S WHERE %2$s=%3$s ",
                manyToManyTableId.tableName,
                manyToManyTableId.entityCol,
                QueryBuilder.objToString(ep.extractId(entity).getValue()));
        if (relatedEntites != null) {
            StringJoiner where = new StringJoiner(" ");
            for (Object child : (Collection) relatedEntites) {
                where.add("AND");
                Object id = ep.extractId(child).getValue();
                where.add(manyToManyTableId.childCol).add("<>").add(QueryBuilder.objToString(id));
            }
            sql += where.toString();
        }
        sql += ";";
        em.executeUpdate(sql);
    }

    private void removeLinksForChildren(Object entity, AnnotatedElement ae, Class<?> childType, Object children)
            throws EntityIdException, EntityException, QueryException {
        String queryTemplate = "UPDATE %1$s SET %2$s=null WHERE %2$s=%3$s ";
        if (ae.getAnnotation(OneToMany.class) != null
                && ae.getAnnotation(OneToMany.class).orphanRemoval()
                || ae.getAnnotation(OneToOne.class) != null
                && ae.getAnnotation(OneToOne.class).orphanRemoval()) {
            queryTemplate = "DELETE FROM %1$S WHERE %2$s=%3$s ";
        }
        String sql = String.format(queryTemplate, ep.extractTableName(childType),
                extractForeignKeyColumnName(ae, childType, entity.getClass()),
                QueryBuilder.objToString(ep.extractId(entity).getValue()));
        if (children != null) {
            StringJoiner where = new StringJoiner(" ");
            for (Object child : (Collection) children) {
                where.add("AND");
                AbstractMap.SimpleEntry<String, Object> id = ep.extractId(child);
                where.add(id.getKey()).add("<>").add(QueryBuilder.objToString(id.getValue()));
            }
            sql += where.toString();
        }
        sql += ";";
        em.executeUpdate(sql);
    }

    private static class ManyToManyTableId {
        String tableName;
        String entityCol;
        String childCol;
    }
}
