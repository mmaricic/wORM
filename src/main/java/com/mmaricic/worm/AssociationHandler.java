package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.EntityException;
import com.mmaricic.worm.exceptions.EntityLoaderException;
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

    Map<String, Object> getAssociatedParentsIds(Object entity) throws EntityException {
        Map<String, Object> ids = new HashMap<>();
        try { // TODO: Sredi ovo
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(field -> field.getAnnotation(ManyToOne.class) != null
                                || (field.getAnnotation(OneToOne.class) != null
                                && field.getAnnotation(OneToOne.class).mappedBy().equals("")))
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    assocField.setAccessible(true);
                    Object parent = assocField.get(entity);
                    Object value = null;
                    String key = extractForeignKeyColumnName(assocField, assocField.getType(), entity.getClass());
                    if (parent != null) {
                        value = extractForeignKeyValue(parent, entity.getClass());
                    }
                    ids.put(key, value);
                }
            } else {
                List<Method> associations = Stream
                        .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                        .map(PropertyDescriptor::getReadMethod)
                        .filter(getMethod -> getMethod != null
                                && (getMethod.getAnnotation(ManyToOne.class) != null
                                || (getMethod.getAnnotation(OneToOne.class) != null
                                && getMethod.getAnnotation(OneToOne.class).mappedBy().equals(""))))
                        .collect(Collectors.toList());

                for (Method getMethod : associations) {
                    Object parent = getMethod.invoke(entity);
                    Object value = null;
                    String key = extractForeignKeyColumnName(getMethod, getMethod.getReturnType(), entity.getClass());
                    if (parent != null) {
                        value = extractForeignKeyValue(parent, entity.getClass());
                    }
                    ids.put(key, value);
                }
            }
        } catch (IllegalAccessException | IntrospectionException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return ids;
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
            return ep.extactTableName(entityClass) + "_id";
        }

        if (ae.getAnnotation(OneToMany.class) != null)
            return ep.extactTableName(entityClass) + "_id";

        return ep.extactTableName(annotatedElementClass) + "_id";
    }

    private Object extractForeignKeyValue(Object relatedEntity, Class<?> entityClass) {
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

    boolean saveAssociations(Object entity) {
        try { // TODO: Sredi ovo
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(field -> field.getAnnotation(OneToMany.class) != null
                                || field.getAnnotation(ManyToMany.class) != null
                                || (field.getAnnotation(OneToOne.class) != null
                                && !field.getAnnotation(OneToOne.class).mappedBy().equals("")))
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    Set<CascadeType> cascades = getCascadeTypes(assocField);
                    if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.PERSIST))
                        continue;
                    assocField.setAccessible(true);
                    if (assocField.getAnnotation(ManyToMany.class) != null) {
                        for (Object child : (Collection) assocField.get(entity)) {
                            saveManyToMany(child, assocField, entity);
                        }
                        continue;
                    }
                    if (Collection.class.isAssignableFrom(assocField.getType()) && assocField.get(entity) != null) {
                        for (Object child : (Collection) assocField.get(entity)) {
                            String key = extractForeignKeyColumnName(assocField, child.getClass(), entity.getClass());
                            Object value = extractForeignKeyValue(entity, entity.getClass());
                            if (!updateChild(child, new HashMap<>(Map.of(key, value))))
                                return false;
                        }
                    } else {
                        Object child = assocField.get(entity);
                        if (child != null) {
                            String key = extractForeignKeyColumnName(assocField, child.getClass(), entity.getClass());
                            Object value = extractForeignKeyValue(entity, entity.getClass());
                            return updateChild(child, new HashMap<>(Map.of(key, value)));
                        }
                    }
                }
                return true;
            }

            List<Method> associations = Stream
                    .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .map(PropertyDescriptor::getReadMethod)
                    .filter(method -> (method != null
                            && (method.getAnnotation(OneToMany.class) != null
                            || method.getAnnotation(ManyToMany.class) != null
                            || (method.getAnnotation(OneToOne.class) != null
                            && !method.getAnnotation(OneToOne.class).mappedBy().equals("")))))
                    .collect(Collectors.toList());

            for (Method getMethod : associations) {
                Set<CascadeType> cascades = getCascadeTypes(getMethod);
                if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.PERSIST))
                    continue;
                if (getMethod.getAnnotation(ManyToMany.class) != null) {
                    for (Object child : (Collection) getMethod.invoke(entity)) {
                        saveManyToMany(child, getMethod, entity);
                    }
                    continue;
                }
                if (Collection.class.isAssignableFrom(getMethod.getReturnType())) {
                    for (Object child : (Collection) getMethod.invoke(entity)) {
                        String key = extractForeignKeyColumnName(getMethod, child.getClass(), entity.getClass());
                        Object value = extractForeignKeyValue(entity, entity.getClass());
                        if (!updateChild(child, new HashMap<>(Map.of(key, value))))
                            return false;
                    }
                } else {
                    Object child = getMethod.invoke(entity);
                    String key = extractForeignKeyColumnName(getMethod, child.getClass(), entity.getClass());
                    Object value = extractForeignKeyValue(entity, entity.getClass());
                    if (!updateChild(child, new HashMap<>(Map.of(key, value))))
                        return false;
                }
            }
            return true;

        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void saveManyToMany(Object child, AnnotatedElement ae, Object entity) {
        if (ae.getAnnotation(ManyToMany.class) == null)
            return;
        if (ep.extractId(child).getValue() == null) {
            em.save(child, null);
        }
        AbstractMap.SimpleEntry<String, AssociationId> associationTableId = getIds(entity, child, ae);
        AssociationId association = associationTableId.getValue();
        List<Map<String, Object>> res = em.query(String.format("SELECT * FROM %s WHERE %s=%s AND %s=%s;"
                , associationTableId.getKey(), association.firstCol, association.firstColValue, association.secondCol, association.secondColValue));
        if (res.isEmpty()) {
            em.executeUpdate(QueryBuilder.buildInsertQuery(associationTableId.getKey(),
                    Map.of(association.firstCol, association.firstColValue, association.secondCol, association.secondColValue)));
        }
    }

    private AbstractMap.SimpleEntry<String, AssociationId> getIds(Object entity, Object child, AnnotatedElement ae) {
        AssociationId ids = new AssociationId();
        String table = ep.extactTableName(entity.getClass()) + "_" + ep.extactTableName(child.getClass());
        ids.firstCol = ep.extactTableName(entity.getClass()) + "_id";
        ids.secondCol = ep.extactTableName(child.getClass()) + "_id";

        JoinTable jt = ae.getAnnotation(JoinTable.class);
        String mappedBy = ae.getAnnotation(ManyToMany.class).mappedBy();
        if (jt != null) {
            if (!jt.name().isEmpty()) {
                table = jt.name();
            }
            for (JoinColumn jc : jt.joinColumns()) {
                ids.firstCol = jc.name();
            }
            for (JoinColumn jc : jt.inverseJoinColumns()) {
                ids.secondCol = jc.name();
            }
        }

        if (jt == null && !mappedBy.isEmpty()) {
            table = ep.extactTableName(child.getClass()) + "_" + ep.extactTableName(entity.getClass());
            Field field;
            try {
                field = child.getClass().getDeclaredField(mappedBy);
            } catch (NoSuchFieldException e) {
                throw new EntityException(e.getMessage());
            }
            if (field.getAnnotation(JoinTable.class) == null) {
                try {
                    PropertyDescriptor descriptor = new PropertyDescriptor(mappedBy, child.getClass());
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
                    table = jt.name();
                }
                for (JoinColumn jc : jt.joinColumns()) {
                    ids.secondCol = jc.name();
                }
                for (JoinColumn jc : jt.inverseJoinColumns()) {
                    ids.firstCol = jc.name();
                }
            }
        }

        ids.firstColValue = ep.extractId(entity).getValue();
        ids.secondColValue = ep.extractId(child).getValue();
        return new AbstractMap.SimpleEntry<>(table, ids);
    }

    private String getMappedBy(AnnotatedElement ae) {
        OneToMany oneToMany = ae.getAnnotation(OneToMany.class);
        if (oneToMany != null)
            return oneToMany.mappedBy();
        OneToOne oneToOne = ae.getAnnotation(OneToOne.class);
        if (oneToOne != null)
            return oneToOne.mappedBy();

        return ae.getAnnotation(ManyToMany.class).mappedBy();
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

    private boolean updateChild(Object child, Map<String, Object> associationId) {
        if (child == null) {
            return true;
        }
        AbstractMap.SimpleEntry<String, Object> childId = ep.extractId(child);
        if (childId.getValue() != null) {
            return em.update(child, associationId);
        }
        return em.save(child, associationId);
    }

    void deleteAssociations(Object entity) {
        try { // TODO: Sredi ovo
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(field -> field.getAnnotation(OneToMany.class) != null
                                || field.getAnnotation(ManyToMany.class) != null
                                || (field.getAnnotation(OneToOne.class) != null
                                && !field.getAnnotation(OneToOne.class).mappedBy().equals("")))
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    Set<CascadeType> cascades = getCascadeTypes(assocField);
                    assocField.setAccessible(true);
                    if (!Collection.class.isAssignableFrom(assocField.getType())) {
                        if (cascades.contains(CascadeType.ALL)
                                || cascades.contains(CascadeType.REMOVE)
                                || removeOrphans(assocField))
                            em.delete(assocField.get(entity));
                        deleteManyToMany(assocField.get(entity), assocField, entity);
                        continue;
                    }
                    for (Object child : (Collection) assocField.get(entity)) {
                        if (cascades.contains(CascadeType.ALL)
                                || cascades.contains(CascadeType.REMOVE)
                                || removeOrphans(assocField))
                            em.delete(child);
                        deleteManyToMany(child, assocField, entity);
                    }
                }
                return;
            }
            List<Method> associations = Stream
                    .of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .map(PropertyDescriptor::getReadMethod)
                    .filter(method -> (method != null
                            && (method.getAnnotation(OneToMany.class) != null
                            || method.getAnnotation(ManyToMany.class) != null
                            || (method.getAnnotation(OneToOne.class) != null
                            && !method.getAnnotation(OneToOne.class).mappedBy().equals("")))))
                    .collect(Collectors.toList());

            for (Method getMethod : associations) {
                Set<CascadeType> cascades = getCascadeTypes(getMethod);

                if (!Collection.class.isAssignableFrom(getMethod.getReturnType())) {
                    if (cascades.contains(CascadeType.ALL) || cascades.contains(CascadeType.REMOVE) || removeOrphans(getMethod))
                        em.delete(getMethod.invoke(entity));
                    deleteManyToMany(getMethod.invoke(entity), getMethod, entity);
                    continue;
                }
                for (Object child : (Collection) getMethod.invoke(entity)) {
                    if (cascades.contains(CascadeType.ALL) || cascades.contains(CascadeType.REMOVE) || removeOrphans(getMethod))
                        em.delete(child);
                    deleteManyToMany(child, getMethod, entity);

                }
            }
        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            e.printStackTrace();
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

    private void deleteManyToMany(Object child, AnnotatedElement ae, Object entity) {
        if (ae.getAnnotation(ManyToMany.class) == null)
            return;
        AbstractMap.SimpleEntry<String, AssociationId> associationTableId = getIds(entity, child, ae);
        AssociationId association = associationTableId.getValue();
        em.executeUpdate(QueryBuilder.buildDeleteQuery(
                associationTableId.getKey(), association.firstCol, association.firstColValue));
    }

    public <T> void fetchAssociations(T entity, Map<String, Object> entityMap) {
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
                        String query = composeFetchChildrenQuery(entity, assocField, childrenType);
                        assocField.set(entity, fetchOneToMany(query,
                                assocField.getAnnotation(OneToMany.class).fetch(), childrenType));
                    }
                    if (assocField.getAnnotation(ManyToOne.class) != null
                            || assocField.getAnnotation(OneToOne.class) != null
                            && assocField.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                        assocField.set(entity, fetchParent(entity, assocField, assocField.getType(), entityMap));
                    }
                    if (assocField.getAnnotation(OneToOne.class) != null
                            && !assocField.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                        String query = composeFetchChildrenQuery(entity, assocField, assocField.getType());
                        assocField.set(entity, fetchOneToOne(query,
                                assocField.getAnnotation(OneToOne.class).fetch(), assocField.getType()));
                    }
                    if (assocField.getAnnotation(ManyToMany.class) != null) {

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
                    .filter(descriptor -> (descriptor.getReadMethod() != null && isAssociation(descriptor.getReadMethod())))
                    .collect(Collectors.toList());

            for (PropertyDescriptor descriptor : associations) {
                Method getMethod = descriptor.getReadMethod();
                Method setMehod = descriptor.getWriteMethod();
                if (getMethod.getAnnotation(OneToMany.class) != null) {
                    ParameterizedType collectionType = (ParameterizedType) getMethod.getGenericParameterTypes()[0];
                    Class<?> childrenType = (Class<?>) collectionType.getActualTypeArguments()[0];
                    String query = composeFetchChildrenQuery(entity, getMethod, childrenType);
                    List<?> res = fetchOneToMany(query,
                            getMethod.getAnnotation(OneToMany.class).fetch(), childrenType);
                    if (setMehod != null)
                        setMehod.invoke(entity, res);
                    else {
                        Field f = entity.getClass().getDeclaredField(descriptor.getName());
                        f.setAccessible(true);
                        f.set(entity, res);
                    }
                }
                if (getMethod.getAnnotation(ManyToOne.class) != null
                        || getMethod.getAnnotation(OneToOne.class) != null
                        && getMethod.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                    Object res = fetchParent(entity, getMethod, getMethod.getReturnType(), entityMap);
                    if (setMehod != null)
                        setMehod.invoke(entity, res);
                    else {
                        Field f = entity.getClass().getDeclaredField(descriptor.getName());
                        f.setAccessible(true);
                        f.set(entity, res);
                    }
                }
                if (getMethod.getAnnotation(OneToOne.class) != null
                        && !getMethod.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                    String query = composeFetchChildrenQuery(entity, getMethod, getMethod.getReturnType());
                    Object res = fetchOneToOne(query,
                            getMethod.getAnnotation(OneToOne.class).fetch(), getMethod.getReturnType());
                    if (setMehod != null)
                        setMehod.invoke(entity, res);
                    else {
                        Field f = entity.getClass().getDeclaredField(descriptor.getName());
                        f.setAccessible(true);
                        f.set(entity, res);
                    }
                }
                if (getMethod.getAnnotation(ManyToMany.class) != null) {

                }
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException | NoSuchFieldException e) {
            throw new EntityLoaderException("An error occurred while trying to set association fields for "
                    + entity.getClass().getSimpleName() + "entity: " + e.getMessage());
        }
    }

    private <T> Object fetchParent(T entity, AnnotatedElement ae, Class<?> oneType, Map<String, Object> entityMap) {
        String oneTableName = ep.extactTableName(oneType);
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
            LazyLoadProxy llp = new LazyLoadProxy<>(oneType, em, query);
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(oneType);
            enhancer.setCallback(llp);
            return enhancer.create();
        }
    }

    private <T> String composeFetchChildrenQuery(T entity, AnnotatedElement ae, Class<?> childrenType) {
        String queryTemplate = "SELECT * FROM %1$s WHERE %1$s.%2$s=%3$s";

        String childTableName = ep.extactTableName(childrenType);
        String parentForeignKeyIdCol = extractForeignKeyColumnName(ae, childrenType, entity.getClass());
        AbstractMap.SimpleEntry<String, Object> parentId = ep.extractId(entity);

        return String.format(queryTemplate,
                childTableName, parentForeignKeyIdCol, parentId.getValue());
    }

    private <T> List<?> fetchOneToMany(String query, FetchType fetchType, Class<?> childrenType) {
        if (fetchType == FetchType.EAGER) {
            return em.query(query, childrenType);
        }
        return new LazyList<>(query, childrenType, em, true);
    }

    private Object fetchOneToOne(String query, FetchType fetchType, Class<?> childrenType) {
        if (fetchType == FetchType.EAGER) {
            List<?> res = em.query(query, childrenType);
            if (res.size() == 0)
                return null;
            return res.get(0);
        }

        LazyLoadProxy llp = new LazyLoadProxy<>(childrenType, em, query);
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(childrenType);
        enhancer.setCallback(llp);
        return enhancer.create();
    }

    private boolean isAssociation(AnnotatedElement ae) {
        return ae.getAnnotation(OneToMany.class) != null
                || ae.getAnnotation(ManyToOne.class) != null
                || ae.getAnnotation(ManyToMany.class) != null
                || ae.getAnnotation(OneToOne.class) != null;

    }

    public void removeOldAssociationLinks(Object entity) {
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
                                child == null ? child : Collections.singletonList(child));
                    }
                    if (field.getAnnotation(ManyToMany.class) != null) {

                    }
                }
                return;
            }
            List<Method> associations = Stream.of(Introspector.getBeanInfo(entity.getClass(), Object.class).getPropertyDescriptors())
                    .map(PropertyDescriptor::getReadMethod)
                    .filter(method -> method != null && isAssociation(method))
                    .collect(Collectors.toList());
            for (Method method : associations) {
                if (method.getAnnotation(OneToMany.class) != null) {
                    ParameterizedType collectionType = (ParameterizedType) method.getGenericParameterTypes()[0];
                    Class<?> childType = (Class<?>) collectionType.getActualTypeArguments()[0];
                    Object children = method.invoke(entity);
                    removeLinksForChildren(entity, method, childType, children);
                }
                if (method.getAnnotation(OneToOne.class) != null &&
                        !method.getAnnotation(OneToOne.class).mappedBy().isEmpty()) {
                    Object child = method.invoke(entity);
                    removeLinksForChildren(entity, method, method.getReturnType(),
                            child == null ? child : Collections.singletonList(child));

                }
                if (method.getAnnotation(ManyToMany.class) != null) {

                }
            }
        } catch (IllegalAccessException | IntrospectionException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    private void removeLinksForChildren(Object entity, AnnotatedElement ae, Class<?> childType, Object children) {
        String queryTemplate = "UPDATE %1$s SET %2$s=null WHERE %2$s=%3$s ";
        if (ae.getAnnotation(OneToMany.class) != null
                && ae.getAnnotation(OneToMany.class).orphanRemoval()
                || ae.getAnnotation(OneToOne.class) != null
                && ae.getAnnotation(OneToOne.class).orphanRemoval()) {
            queryTemplate = "DELETE FROM %1$S WHERE %2$s=%3$s ";
        }
        String sql = String.format(queryTemplate, ep.extactTableName(childType),
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

    private class AssociationId {
        String firstCol;
        String secondCol;
        Object firstColValue;
        Object secondColValue;
    }

}
