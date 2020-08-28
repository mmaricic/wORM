package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.AnnotationException;
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

public class EntityParser {
    public Map<String, Object> parse(Object entity, boolean includeId)
            throws AnnotationException, EntityLoaderException {
        if (entity == null) {
            return new LinkedHashMap<>();
        }
        Class<?> entityClass = entity.getClass();
        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw new AnnotationException(String.format(
                    "Class %s is not marked as an entity! " +
                            "If the class represent Table in database, please add @Entity annotation.",
                    entityClass.getSimpleName()));
        }


        if (isIddAnnotationOnField(entityClass))
            return parseFromFields(entity, includeId);
        else
            return parseFromGetters(entity, includeId);
    }

    public String extactTableName(Class<?> entityClass) throws AnnotationException {
        if (!entityClass.isAnnotationPresent(Entity.class)) {
            throw new AnnotationException(String.format(
                    "Class %s is not marked as an entity! " +
                            "If the class represent Table in database, please add @Entity annotation.",
                    entityClass.getSimpleName()));
        }
        Table annot = entityClass.getAnnotation(Table.class);
        if (annot != null) {
            return annot.name();
        }
        return entityClass.getSimpleName().toLowerCase();
    }

    public AbstractMap.SimpleEntry<String, Object> extractId(Object entity)
            throws EntityIdException, EntityLoaderException { // Ovo da vraca mapu jer je mozda embeddedId
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
            return new AbstractMap.SimpleEntry<>(
                    getColumnNameFromDescriptor(idDescriptor), idDescriptor.getReadMethod().invoke(entity));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new EntityLoaderException(String.format(
                    "An error occurred while trying to invoke getter method %s in entity class: %s, message: %s",
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
                    "Default counstructor for entity class %s threw the following error: %S",
                    entityClass.getSimpleName(), e.getMessage()));
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
                            setMethod.invoke(
                                    entity, convertRowToEntity(getMethod.getReturnType(), entityElements, entityClass));
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
            throw new EntityLoaderException("An error occurred while trying to convert row: " + e.getMessage());
        }
        return entity;
    }

    boolean isIddAnnotationOnField(Class<?> entityClass) throws EntityIdException {
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
            if (field.getAnnotation(GeneratedValue.class) != null) {
                if (field.getAnnotation(Id.class) == null) {
                    throw new AnnotationException(String.format(
                            "Bad annotation in class %s. @GeneratedValue can only be used with @Id annotation.",
                            entityClass.getSimpleName()));
                }
            }

            field.setAccessible(true);

            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            if (field.getAnnotation(Transient.class) != null) {
                continue;
            }

            if (isAssociation(field)) {
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

                if (isAssociation(getMethod)) {
                    continue;
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
                    throw new EntityLoaderException(String.format(
                            "An error occurred while trying to invoke getter method %s in entity class: %s, message: %s",
                            getMethod.getName(), entityClass.getSimpleName(), e.getMessage()));
                }
            }
        } catch (IntrospectionException e) {
            throw new EntityLoaderException(String.format(
                    "An error occurred while trying to parse entity class: %s, message: %s",
                    entityClass.getSimpleName(), e.getMessage()));
        }

        if (!foundId && !entityClass.isAnnotationPresent(Embeddable.class)) {
            throw new EntityIdException(entityClass.getSimpleName(), EntityIdExceptionType.NO_ID);
        }
        return result;
    }

    private boolean isAssociation(AnnotatedElement ae) {
        return ae.getAnnotation(OneToMany.class) != null
                || ae.getAnnotation(ManyToOne.class) != null
                || ae.getAnnotation(ManyToMany.class) != null
                || ae.getAnnotation(OneToOne.class) != null;

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
            throw new EntityLoaderException(String.format(
                    "An error occurred while trying to find Id annotation for entity class: %s, message: %s",
                    entityClass.getSimpleName(), e.getMessage()));
        }
    }

    public boolean idIsAutoGenerated(Class<?> entityClass) { // Ovde proveriti da li je EmbeddedId zajedno sa GeneratedValue
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
            throw new EntityLoaderException(String.format(
                    "Error checking if id field is autogenerated for class %s. Error: %s",
                    entityClass.getSimpleName(), e.getMessage()));
        }
        return false;
    }
}
