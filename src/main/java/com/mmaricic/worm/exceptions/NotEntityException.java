package com.mmaricic.worm.exceptions;

public class NotEntityException extends RuntimeException {

    public NotEntityException(String className) {
        super("Class " + className + " is not marked as an entity! If the class represent Table in database, please add @Entity annotation.");
    }
}
