package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.EntityIdException;
import com.mmaricic.worm.exceptions.EntityIdException.EntityIdExceptionType;
import com.mmaricic.worm.exceptions.EntityLoaderException;
import com.mmaricic.worm.exceptions.NotEntityException;

import javax.persistence.*;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EntityParser {
    //TODO: kako resiti id - baza generise, ti generises, user input, kako hendlovati sve ovo?
    public Map<String, Object> parse(Object entity, boolean includeId)
            throws NotEntityException, EntityLoaderException {
        if (entity == null) {
            return new LinkedHashMap<>();
        }
        Class<?> entityClass = entity.getClass();
        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw new NotEntityException(entityClass.getSimpleName());
        }


        if (isIddAnnotationOnField(entityClass))
            return parseFromFields(entity, includeId);
        else
            return parseFromGetters(entity, includeId);
    }

    public String extactTableName(Class<?> entityClass) throws NotEntityException {
        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw new NotEntityException(entityClass.getSimpleName());
        }
        Table annot = entityClass.getAnnotation(Table.class);
        if (annot != null) {
            return annot.name();
        }
        return entityClass.getSimpleName();
    }

    public AbstractMap.SimpleEntry<String, Object> extractId(Object entity)
            throws EntityIdException, EntityLoaderException {
        Class<?> entityClass = entity.getClass();

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
                throw new EntityLoaderException(String.format("Field %s in class %s is inaccessible.",
                        idField.getName(), entityClass.getSimpleName()));
            }
        }

        PropertyDescriptor idDescriptor = getIdDescriptor(entityClass);
        try {
            return new AbstractMap.SimpleEntry<>(getColumnNameFromDescriptor(idDescriptor), idDescriptor.getReadMethod().invoke(entity));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new EntityLoaderException(
                    String.format("An error occurred while trying to invoke getter method %s in entity class: %s, message: %s",
                            idDescriptor.getReadMethod().getName(), entityClass.getSimpleName(), e.getMessage()));
        }
    }

    public String extractIdColumnName(Class<?> entityClass) {
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

    public <T> T convertRowToEntity(Class<T> entityClass, Map<String, Object> entityElements, Class<?> parentClass)
            throws EntityLoaderException {
        T entity;
        try {
            entity = entityClass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new EntityLoaderException("Entity class " + entityClass.getSimpleName() + " does not have a default constructor.");
        } catch (IllegalAccessException e) {
            throw new EntityLoaderException("Constructor for entity class" + entityClass.getSimpleName() + " is not accessible. Please make it public");
        } catch (InstantiationException e) {
            throw new EntityLoaderException("Entity class" + entityClass.getSimpleName() + " is abstract!");
        } catch (InvocationTargetException e) {
            throw new EntityLoaderException("Default counstructor for entity class " + entityClass.getSimpleName() + "threw the following error: " + e.getMessage());
        }

        try {
            if (isIddAnnotationOnField(parentClass == null ? entityClass : parentClass)) {
                for (Field field : entityClass.getDeclaredFields()) {
                    if (field.getAnnotation(Embedded.class) != null) {
                        field.setAccessible(true);
                        field.set(entity, convertRowToEntity(field.getType(), entityElements, entityClass));
                        continue;
                    }
                    Object value = entityElements.get(getColumnNameFromField(field));
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
                    if (getMethod != null && setMethod != null) {
                        if (getMethod.getAnnotation(Embedded.class) != null) {
                            setMethod.invoke(entity, convertRowToEntity(getMethod.getReturnType(), entityElements, entityClass));
                            continue;
                        }

                        String colName = getColumnNameFromDescriptor(propertyDescriptor);
                        Object value = entityElements.get(colName);
                        if (value != null) {
                            setMethod.invoke(entity, value);
                        }
                    }
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | IntrospectionException e) {
            throw new EntityLoaderException("An error occured while trying to convert row: " + e.getMessage());
        }
        return entity;
    }

    private boolean isIddAnnotationOnField(Class<?> entityClass) throws EntityIdException {
        long fieldsWithId = Stream.of(entityClass.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class)).count();
        if (fieldsWithId > 1) {
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
        }
        return fieldsWithId != 0;
    }

    private Map<String, Object> parseFromFields(Object entity, boolean includeId) throws EntityLoaderException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (entity == null) {
            return result;
        }

        Class<?> entityClass = entity.getClass();

        for (Field field : entityClass.getDeclaredFields()) {
            if (!includeId && field.getAnnotation(Id.class) != null) {
                continue;
            }

            field.setAccessible(true);

            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            if (field.getAnnotation(Transient.class) != null) {
                continue;
            }
            try {
                if (field.getAnnotation(Embedded.class) != null) {
                    result.putAll(parseFromFields(field.get(entity), includeId));
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

    private Map<String, Object> parseFromGetters(Object entity, boolean includeId)
            throws EntityIdException, EntityLoaderException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (entity == null) {
            return result;
        }

        Class<?> entityClass = entity.getClass();
        boolean foundId = false;

        try {
            for (PropertyDescriptor propertyDescriptor :
                    Introspector.getBeanInfo(entityClass, Object.class).getPropertyDescriptors()) {
                Method getMethod = propertyDescriptor.getReadMethod();

                if (getMethod == null) {
                    continue;
                }

                if (getMethod.getAnnotation(Id.class) != null) {
                    if (foundId) {
                        throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
                    }
                    foundId = true;
                    if (!includeId) {
                        continue;
                    }
                }

                if (getMethod.getAnnotation(Transient.class) != null) {
                    continue;
                }
                try {
                    if (getMethod.getAnnotation(Embedded.class) != null) {
                        result.putAll(parseFromGetters(getMethod.invoke(entity), includeId));
                        continue;
                    }
                    Object value = getMethod.invoke(entity);
                    if (value != null)
                        result.put(getColumnNameFromDescriptor(propertyDescriptor), value);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new EntityLoaderException(
                            String.format("An error occurred while trying to invoke getter method %s in entity class: %s, message: %s",
                                    getMethod.getName(), entityClass.getSimpleName(), e.getMessage()));
                }
            }
        } catch (IntrospectionException e) {
            throw new EntityLoaderException(
                    String.format("An error occurred while trying to parse entity class: %s, message: %s",
                            entityClass.getSimpleName(), e.getMessage()));
        }

        if (!foundId && !entityClass.isAnnotationPresent(Embeddable.class)) {
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.NO_ID);
        }
        return result;
    }

    private String getColumnNameFromField(Field field) {
        Column colAnnot = field.getAnnotation(Column.class);
        return colAnnot != null ? colAnnot.name() : field.getName();
    }

    private String getColumnNameFromDescriptor(PropertyDescriptor descriptor) {
        Column colAnnot = descriptor.getReadMethod().getAnnotation(Column.class);
        return colAnnot != null ? colAnnot.name() : descriptor.getName();
    }

    private PropertyDescriptor getIdDescriptor(Class<?> entityClass) throws EntityIdException, EntityLoaderException {
        try {
            List<PropertyDescriptor> idDescriptorsList = Stream
                    .of(Introspector.getBeanInfo(entityClass, Object.class).getPropertyDescriptors())
                    .filter(descriptor ->
                            descriptor.getReadMethod() != null && descriptor.getReadMethod().isAnnotationPresent(Id.class))
                    .collect(Collectors.toList());

            if (idDescriptorsList.size() == 0) {
                throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.NO_ID);
            }
            if (idDescriptorsList.size() > 1) {
                throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.MULTIPLE_IDS);
            }
            return idDescriptorsList.get(0);
        } catch (IntrospectionException e) {
            throw new EntityLoaderException(
                    String.format("An error occurred while trying to find Id annotation for entity class: %s, message: %s",
                            entityClass.getSimpleName(), e.getMessage()));
        }
    }

}
