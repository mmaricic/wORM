package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.EntityException;

import javax.persistence.*;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class AnnotationHandler {
    private final EntityManager em;
    private final EntityParser ep;

    AnnotationHandler(EntityManager entityManager) {
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
                    if (parent != null) {
                        String key = extractForeignKeyColumnName(assocField, parent.getClass(), entity.getClass());
                        Object value = extractForeignKeyValue(parent, entity.getClass());
                        ids.put(key, value);
                    }
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
                    if (parent != null) {
                        String key = extractForeignKeyColumnName(getMethod, parent.getClass(), entity.getClass());
                        Object value = extractForeignKeyValue(parent, entity.getClass());
                        ids.put(key, value);
                    }
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
            return mappedBy + "_id";
        }

        return ep.extactTableName(entityClass) + "_id";
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
            em.save(child);
        }
        AbstractMap.SimpleEntry<String, AssociationId> associationTableId = getIds(entity, child, ae);
        AssociationId association = associationTableId.getValue();
        List<Map<String, Object>> res = em.query(String.format("SELECT * FROM %s WHERE %s=%s AND %s=%s;"
                , associationTableId.getKey(), association.col1, association.value1, association.col2, association.value2));
        if (res.isEmpty()) {
            em.executeUpdate(QueryBuilder.buildInsertQuery(associationTableId.getKey(),
                    Map.of(association.col1, association.value1, association.col2, association.value2)));
        }
    }

    private AbstractMap.SimpleEntry<String, AssociationId> getIds(Object entity, Object child, AnnotatedElement ae) {
        AssociationId ids = new AssociationId();
        String table = ep.extactTableName(entity.getClass()) + "_" + ep.extactTableName(child.getClass());
        ids.col1 = ep.extactTableName(entity.getClass()) + "_id";
        ids.col2 = ep.extactTableName(child.getClass()) + "_id";

        JoinTable jt = ae.getAnnotation(JoinTable.class);
        String mappedBy = ae.getAnnotation(ManyToMany.class).mappedBy();
        if (jt != null) {
            if (!jt.name().isEmpty()) {
                table = jt.name();
            }
            for (JoinColumn jc : jt.joinColumns()) {
                ids.col1 = jc.name();
            }
            for (JoinColumn jc : jt.inverseJoinColumns()) {
                ids.col2 = jc.name();
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
                    ids.col2 = jc.name();
                }
                for (JoinColumn jc : jt.inverseJoinColumns()) {
                    ids.col1 = jc.name();
                }
            }
        }

        ids.value1 = ep.extractId(entity).getValue();
        ids.value2 = ep.extractId(child).getValue();
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
                                || (field.getAnnotation(OneToOne.class) != null
                                && !field.getAnnotation(OneToOne.class).mappedBy().equals("")))
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    Set<CascadeType> cascades = getCascadeTypes(assocField);
                    assocField.setAccessible(true);
                    if (!Collection.class.isAssignableFrom(assocField.getType())) {
                        if (cascades.contains(CascadeType.ALL) || cascades.contains(CascadeType.REMOVE))
                            em.delete(assocField.get(entity));
                        deleteManyToMany(assocField.get(entity), assocField, entity);
                        continue;
                    }
                    for (Object child : (Collection) assocField.get(entity)) {
                        if (cascades.contains(CascadeType.ALL) || cascades.contains(CascadeType.REMOVE))
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
                            || (method.getAnnotation(OneToOne.class) != null
                            && !method.getAnnotation(OneToOne.class).mappedBy().equals("")))))
                    .collect(Collectors.toList());

            for (Method getMethod : associations) {
                Set<CascadeType> cascades = getCascadeTypes(getMethod);

                if (!Collection.class.isAssignableFrom(getMethod.getReturnType())) {
                    if (cascades.contains(CascadeType.ALL) || cascades.contains(CascadeType.REMOVE))
                        em.delete(getMethod.invoke(entity));
                    deleteManyToMany(getMethod.invoke(entity), getMethod, entity);
                    continue;
                }
                for (Object child : (Collection) getMethod.invoke(entity)) {
                    if (cascades.contains(CascadeType.ALL) || cascades.contains(CascadeType.REMOVE))
                        em.delete(child);
                    deleteManyToMany(child, getMethod, entity);

                }
            }
        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            e.printStackTrace();
        }
    }

    private void deleteManyToMany(Object child, AnnotatedElement ae, Object entity) {
        if (ae.getAnnotation(ManyToMany.class) == null)
            return;
        AbstractMap.SimpleEntry<String, AssociationId> associationTableId = getIds(entity, child, ae);
        AssociationId association = associationTableId.getValue();
        em.executeUpdate(QueryBuilder.buildDeleteQuery(
                associationTableId.getKey(), association.col1, association.value1));
    }

    private class AssociationId {
        String col1;
        String col2;
        Object value1;
        Object value2;
    }

}
