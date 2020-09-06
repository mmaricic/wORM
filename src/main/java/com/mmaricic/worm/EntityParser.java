package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.AnnotationException;
import com.mmaricic.worm.exceptions.EntityException;
import com.mmaricic.worm.exceptions.EntityIdException;
import com.mmaricic.worm.exceptions.EntityIdException.EntityIdExceptionType;
import com.mmaricic.worm.exceptions.EntityLoaderException;

import javax.persistence.*;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class EntityParser {
    Map<String, Object> parse(Object entity, boolean includeId)
            throws AnnotationException, EntityLoaderException, EntityIdException {
        if (entity == null) {
            return new LinkedHashMap<>();
        }
        Class<?> entityClass = entity.getClass();
        Map<String, Object> result = new LinkedHashMap<>();
        if (isIddAnnotationOnField(entityClass))
            do {
                result.putAll(parseFromFields(entity, entityClass, includeId));
                entityClass = entityClass.getSuperclass();
            } while (entityClass != null && entityClass != Object.class);
        else
            do {
                result.putAll(parseFromGetters(entity, entityClass, includeId));
                entityClass = entityClass.getSuperclass();
            } while (entityClass != null && entityClass != Object.class);

        entityClass = entity.getClass();
        if (entityClass.getSuperclass() != Object.class || entityClass.getAnnotation(Inheritance.class) != null) {
            String dc = getDiscriminatorColumnName(entityClass);
            String dv = getDiscriminatorValue(entityClass);
            result.put(dc, dv);
        }

        return result;
    }

    String getDiscriminatorValue(Class<?> entityClass) {
        DiscriminatorValue dv = entityClass.getAnnotation(DiscriminatorValue.class);
        if (dv == null)
            return entityClass.getSimpleName();
        return dv.value();
    }

    String getDiscriminatorColumnName(Class<?> entityClass) {
        while (entityClass != null && entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }
        DiscriminatorColumn dc = entityClass.getAnnotation(DiscriminatorColumn.class);
        if (dc == null)
            return "dtype";

        return dc.name();
    }

    private Map<String, Object> parseFromFields(Object entity, Class<?> entityClass, boolean includeId)
            throws EntityLoaderException, AnnotationException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (entity == null)
            return result;

        for (Field field : entityClass.getDeclaredFields()) {
            if (shouldNotPersist(field, includeId, entityClass.getSimpleName()))
                continue;

            field.setAccessible(true);

            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers))
                continue;

            try {
                if (field.getAnnotation(Embedded.class) != null) {
                    result.putAll(parseFromFields(field.get(entity), field.getType(), includeId));
                    continue;
                }

                Object value = field.get(entity);
                if (value != null)
                    result.put(getColumnNameFromField(field), value);

            } catch (IllegalAccessException e) {
                throw new EntityLoaderException(String.format("Field %s in class %s is inaccessible.",
                        field.getName(), entityClass.getSimpleName()));
            }
        }
        return result;
    }

    private Map<String, Object> parseFromGetters(Object entity, Class<?> entityClass, boolean includeId)
            throws EntityIdException, EntityLoaderException, AnnotationException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (entity == null) {
            return result;
        }
        boolean foundId = false;

        try {
            for (PropertyDescriptor propertyDescriptor :
                    Introspector.getBeanInfo(entityClass, Object.class).getPropertyDescriptors()) {
                Method getMethod = propertyDescriptor.getReadMethod();
                if (getMethod == null)
                    continue;

                if (getMethod.getAnnotation(Id.class) != null) {
                    if (foundId)
                        throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
                    foundId = true;
                }

                if (shouldNotPersist(getMethod, includeId, entityClass.getSimpleName()))
                    continue;

                try {
                    if (getMethod.getAnnotation(Embedded.class) != null) {
                        result.putAll(parseFromGetters(getMethod.invoke(entity), getMethod.getReturnType(), includeId));
                        continue;
                    }

                    Object value = getMethod.invoke(entity);
                    if (value != null)
                        result.put(getColumnNameFromDescriptor(propertyDescriptor), value);

                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new EntityLoaderException(String.format(
                            "An error occurred while trying to invoke method %s in entity class: %s, message: %s",
                            getMethod.getName(), entityClass.getSimpleName(), e.getMessage()));
                }
            }
        } catch (IntrospectionException e) {
            throw new EntityLoaderException(String.format(
                    "An error occurred while trying to parse entity class: %s, message: %s",
                    entityClass.getSimpleName(), e.getMessage()));
        }

        if (!foundId && !entityClass.isAnnotationPresent(Embeddable.class))
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.NO_ID);

        return result;
    }

    private boolean shouldNotPersist(AnnotatedElement ae, boolean includeId, String className)
            throws AnnotationException {
        if (!includeId && ae.getAnnotation(Id.class) != null)
            return true;

        if (isAssociation(ae))
            return true;

        if (ae.getAnnotation(Transient.class) != null)
            return true;

        if (ae.getAnnotation(GeneratedValue.class) != null && ae.getAnnotation(Id.class) == null)
            throw new AnnotationException(String.format(
                    "Bad annotation in class %s. @GeneratedValue can only be used with @Id annotation.",
                    className));

        return false;
    }

    void verifyItsEntityClass(Class<?> entityClass) throws AnnotationException {
        if (!entityClass.isAnnotationPresent(Entity.class))
            throw new AnnotationException(String.format(
                    "Class %s is not marked as an entity! " +
                            "If the class represent Table in database, please add @Entity annotation.",
                    entityClass.getSimpleName()));
    }

    String extractTableName(Class<?> entityClass) throws AnnotationException {
        while (entityClass != null && entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }
        verifyItsEntityClass(entityClass);
        Table annot = entityClass.getAnnotation(Table.class);
        if (annot != null) {
            return annot.name();
        }
        return entityClass.getSimpleName().toLowerCase();
    }

    AbstractMap.SimpleEntry<String, Object> extractId(Object entity)
            throws EntityIdException, EntityException {
        Class<?> entityClass = entity.getClass();
        while (entityClass != null && entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }

        List<Field> idList = Stream.of(entityClass.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class)).collect(Collectors.toList());

        if (idList.size() > 1) {
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
        }
        if (idList.size() == 1) {
            Field idField = idList.get(0);
            try {
                idField.setAccessible(true);
                return new AbstractMap.SimpleEntry<>(getColumnNameFromField(idField), idField.get(entity));

            } catch (IllegalAccessException e) {
                throw new EntityException(String.format("Field %s in class %s is inaccessible.",
                        idField.getName(), entityClass.getSimpleName()));
            }
        }

        PropertyDescriptor idDescriptor = getIdDescriptor(entityClass);
        Method getMethod = idDescriptor.getReadMethod();
        try {
            return new AbstractMap.SimpleEntry<>(
                    getColumnNameFromDescriptor(idDescriptor),
                    getMethod.invoke(entity));

        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new EntityException(String.format(
                    "An error occurred while trying to invoke getter method %s in entity class: %s, message: %s",
                    idDescriptor.getReadMethod().getName(), entityClass.getSimpleName(), e.getMessage()));
        }
    }

    String extractIdColumnName(Class<?> entityClass) throws EntityIdException, EntityException {
        while (entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }
        List<Field> idList = Stream.of(entityClass.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class)).collect(Collectors.toList());

        if (idList.size() > 1) {
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
        }
        if (idList.size() == 1) {
            return getColumnNameFromField(idList.get(0));
        }

        PropertyDescriptor idDescriptor = getIdDescriptor(entityClass);
        return getColumnNameFromDescriptor(idDescriptor);
    }

    private String getColumnNameFromField(Field field) {
        Column colAnnot = field.getAnnotation(Column.class);
        return colAnnot != null ? colAnnot.name() : field.getName();
    }

    private String getColumnNameFromDescriptor(PropertyDescriptor descriptor) {
        Column colAnnot = descriptor.getReadMethod().getAnnotation(Column.class);
        return colAnnot != null ? colAnnot.name() : descriptor.getName();
    }

    <T> T convertRowToEntity(Class<T> entityClass, Map<String, Object> entityElements, Class<?> parentClass)
            throws EntityLoaderException, EntityIdException {
        T entity;
        try {
            entity = entityClass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new EntityLoaderException(String.format(
                    "Entity class %s does not have a default constructor.", entityClass.getSimpleName()));
        } catch (IllegalAccessException e) {
            throw new EntityLoaderException(String.format(
                    "Constructor for entity class %s is not accessible. Please make it public",
                    entityClass.getSimpleName()));
        } catch (InstantiationException e) {
            throw new EntityLoaderException(String.format(
                    "Entity class %s is abstract!", entityClass.getSimpleName()));
        } catch (InvocationTargetException e) {
            throw new EntityLoaderException(String.format(
                    "Default constructor for entity class %s threw the following error: %S",
                    entityClass.getSimpleName(), e.getMessage()));
        }
        Class<?> currentClass = entityClass;
        do {
            convertRowToEntity(entity, currentClass, entityElements, parentClass);
            currentClass = currentClass.getSuperclass();
        } while (currentClass != null && currentClass != Object.class);
        return entity;
    }

    private void convertRowToEntity(Object entity, Class<?> entityClass, Map<String, Object> entityElements, Class<?> parentClass) {
        try {
            if (isIddAnnotationOnField(parentClass == null ? entityClass : parentClass)) {
                for (Field field : entityClass.getDeclaredFields()) {
                    if (field.getAnnotation(Embedded.class) != null) {
                        field.setAccessible(true);
                        field.set(entity, convertRowToEntity(field.getType(), entityElements, entityClass));
                        continue;
                    }
                    Object value = convertType(entityElements.get(getColumnNameFromField(field)), field.getType());
                    if (value != null) {
                        field.setAccessible(true);
                        field.set(entity, value);
                    }
                }
            } else {
                for (PropertyDescriptor propertyDescriptor :
                        Introspector.getBeanInfo(entityClass, Object.class).getPropertyDescriptors()) {
                    Method getMethod = propertyDescriptor.getReadMethod();
                    Method setMethod = propertyDescriptor.getWriteMethod();
                    if (getMethod != null) {
                        try {
                            if (getMethod.getAnnotation(Embedded.class) != null) {
                                Object embedded = convertRowToEntity(
                                        getMethod.getReturnType(), entityElements, entityClass);
                                if (setMethod != null)
                                    setMethod.invoke(entity, embedded);
                                else {
                                    Field f = entity.getClass().getDeclaredField(propertyDescriptor.getName());
                                    f.setAccessible(true);
                                    f.set(entity, embedded);
                                }
                                continue;
                            }

                            Object value = convertType(
                                    entityElements.get(getColumnNameFromDescriptor(propertyDescriptor)),
                                    getMethod.getReturnType());
                            if (value != null) {
                                if (setMethod != null)
                                    setMethod.invoke(entity, value);
                                else {
                                    Field f = entity.getClass().getDeclaredField(propertyDescriptor.getName());
                                    f.setAccessible(true);
                                    f.set(entity, value);
                                }
                            }
                        } catch (NoSuchFieldException e) {
                            throw new EntityLoaderException(String.format(
                                    "For property %s in class %s there is no matching field or setter method.",
                                    propertyDescriptor.getName(), entityClass.getSimpleName()));
                        }
                    }
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            throw new EntityLoaderException("An error occurred while trying to convert row: " + e.getMessage());
        }
    }

    private Object convertType(Object columnVal, Class<?> type) {
        if (columnVal == null)
            return null;
        if (type.equals(boolean.class) || type.equals(Boolean.class))
            return (int) columnVal == 1;
        if (type.equals(char.class) || type.equals(Character.class))
            return columnVal.toString().charAt(0);
        return columnVal;
    }

    boolean isIddAnnotationOnField(Class<?> entityClass) throws EntityIdException {
        while (entityClass != null && entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }
        long fieldsWithId = Stream.of(entityClass.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class)).count();
        if (fieldsWithId > 1) {
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
        }
        return fieldsWithId != 0;
    }


    private boolean isAssociation(AnnotatedElement ae) {
        return ae.getAnnotation(OneToMany.class) != null
                || ae.getAnnotation(ManyToOne.class) != null
                || ae.getAnnotation(ManyToMany.class) != null
                || ae.getAnnotation(OneToOne.class) != null;
    }

    private PropertyDescriptor getIdDescriptor(Class<?> entityClass) throws EntityIdException, EntityException {
        try {
            List<PropertyDescriptor> idDescriptorsList = Stream
                    .of(Introspector.getBeanInfo(entityClass, Object.class).getPropertyDescriptors())
                    .filter(descriptor ->
                            descriptor.getReadMethod() != null
                                    && descriptor.getReadMethod().isAnnotationPresent(Id.class))
                    .collect(Collectors.toList());

            if (idDescriptorsList.size() == 0) {
                throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.NO_ID);
            }
            if (idDescriptorsList.size() > 1) {
                throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
            }
            return idDescriptorsList.get(0);
        } catch (IntrospectionException e) {
            throw new EntityException(String.format(
                    "An error occurred while trying to find Id annotation for entity class: %s, message: %s",
                    entityClass.getSimpleName(), e.getMessage()));
        }
    }

    boolean isIdAutoGenerated(Class<?> entityClass) throws EntityException {
        while (entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }
        Optional<Field> idField = Stream.of(entityClass.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class)).findAny();
        if (idField.isPresent())
            return idField.get().getAnnotation(GeneratedValue.class) != null;

        try {
            Optional<PropertyDescriptor> idDescriptor = Stream
                    .of(Introspector.getBeanInfo(entityClass, Object.class).getPropertyDescriptors())
                    .filter(descriptor ->
                            descriptor.getReadMethod() != null
                                    && descriptor.getReadMethod().isAnnotationPresent(Id.class))
                    .findAny();
            if (idDescriptor.isPresent())
                return idDescriptor.get().getReadMethod().getAnnotation(GeneratedValue.class) != null;
        } catch (IntrospectionException e) {
            throw new EntityException(String.format(
                    "Error checking if id field is autogenerated for class %s. Error: %s",
                    entityClass.getSimpleName(), e.getMessage()));
        }
        return false;
    }

    Field getIdField(Class<?> entityClass) throws NoSuchFieldException {
        while (entityClass.getSuperclass() != Object.class) {
            entityClass = entityClass.getSuperclass();
        }
        return entityClass.getDeclaredField(extractIdColumnName(entityClass));
    }
}
