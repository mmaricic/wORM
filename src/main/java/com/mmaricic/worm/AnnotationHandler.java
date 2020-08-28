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
                        String key = extractForeignKeyColumnName(assocField, parent.getClass());
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
                        String key = extractForeignKeyColumnName(getMethod, parent.getClass());
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

    private String extractForeignKeyColumnName(AnnotatedElement ae, Class<?> annotatedElementClass) {

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

    boolean saveAssociatedChildren(Object entity) {
        try { // TODO: Sredi ovo
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(field -> field.getAnnotation(OneToMany.class) != null
                                || (field.getAnnotation(OneToOne.class) != null
                                && !field.getAnnotation(OneToOne.class).mappedBy().equals("")))
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    Set<CascadeType> cascades = getCascadeTypes(assocField);
                    if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.PERSIST))
                        continue;
                    String mappedBy = getMappedBy(assocField);
                    assocField.setAccessible(true);
                    if (Collection.class.isAssignableFrom(assocField.getType())) {
                        for (Object child : (Collection) assocField.get(entity)) {
                            String key = extractForeignKeyColumnName(assocField, child.getClass());
                            Object value = extractForeignKeyValue(entity, entity.getClass());
                            if (!updateChild(child, new HashMap<>(Map.of(key, value))))
                                return false;
                        }
                    } else {
                        Object child = assocField.get(entity);
                        String key = extractForeignKeyColumnName(assocField, child.getClass());
                        Object value = extractForeignKeyValue(entity, entity.getClass());
                        return updateChild(child, new HashMap<>(Map.of(key, value)));
                    }
                }
                return true;
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
                if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.PERSIST))
                    continue;
                String mappedBy = getMappedBy(getMethod);
                if (Collection.class.isAssignableFrom(getMethod.getReturnType())) {
                    for (Object child : (Collection) getMethod.invoke(entity)) {
                        String key = extractForeignKeyColumnName(getMethod, child.getClass());
                        Object value = extractForeignKeyValue(entity, entity.getClass());
                        if (!updateChild(child, new HashMap<>(Map.of(key, value))))
                            return false;
                    }
                } else {
                    Object child = getMethod.invoke(entity);
                    String key = extractForeignKeyColumnName(getMethod, child.getClass());
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

    void deleteChildren(Object entity) {
        try { // TODO: Sredi ovo
            if (ep.isIddAnnotationOnField(entity.getClass())) {
                List<Field> associations = Stream.of(entity.getClass().getDeclaredFields())
                        .filter(field -> field.getAnnotation(OneToMany.class) != null
                                || (field.getAnnotation(OneToOne.class) != null
                                && !field.getAnnotation(OneToOne.class).mappedBy().equals("")))
                        .collect(Collectors.toList());

                for (Field assocField : associations) {
                    Set<CascadeType> cascades = getCascadeTypes(assocField);
                    if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.REMOVE))
                        continue;
                    assocField.setAccessible(true);
                    if (!Collection.class.isAssignableFrom(assocField.getType())) {
                        em.delete(assocField.get(entity));
                        continue;
                    }
                    for (Object child : (Collection) assocField.get(entity)) {
                        em.delete(child);
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
                if (!cascades.contains(CascadeType.ALL) && !cascades.contains(CascadeType.REMOVE))
                    continue;
                if (!Collection.class.isAssignableFrom(getMethod.getReturnType())) {
                    em.delete(getMethod.invoke(entity));
                    continue;
                }
                for (Object child : (Collection) getMethod.invoke(entity)) {
                    em.delete(child);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            e.printStackTrace();
        }
    }
}
