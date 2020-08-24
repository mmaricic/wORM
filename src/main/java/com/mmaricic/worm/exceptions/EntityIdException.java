package com.mmaricic.worm.exceptions;

import java.util.HashMap;
import java.util.Map;


public class EntityIdException extends RuntimeException {
    public enum EntityIdExceptionType {
        MULTIPLE_IDS,
        NO_ID
    }

    private static final Map<EntityIdExceptionType, String> errorMessages;

    static {
        errorMessages = new HashMap<>();
        errorMessages.put(EntityIdExceptionType.MULTIPLE_IDS, "Class %s has more then one Column declared as @Id. For composite keys please use IdClass.");
        errorMessages.put(EntityIdExceptionType.NO_ID, "Class %s does not have Id column. Please annotate your primary key column with @Id");
    }

    public EntityIdException(String className, EntityIdExceptionType type) {
        super(String.format(errorMessages.get(type), className));
    }
}

