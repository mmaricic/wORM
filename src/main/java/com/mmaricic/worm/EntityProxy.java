package com.mmaricic.worm;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;
import java.util.List;

public class EntityProxy<T> implements MethodInterceptor {

    private T entity;
    private boolean invoked = false;
    private final Class<T> entityClass;
    private final EntityManager entityManager;
    private final String query;

    public EntityProxy(Class<T> entityClass, EntityManager entityManager, String query) {
        this.entityClass = entityClass;
        this.entityManager = entityManager;
        this.query = query;
    }

    @Override
    public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
        if (!invoked) {
            List<T> result = entityManager.query(query, entityClass);
            if (result.size() == 1)
                entity = result.get(0);
            invoked = true;
        }
        return method.invoke(entity, objects);
    }
}
