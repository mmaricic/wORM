package com.mmaricic.worm;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;
import java.util.List;

public class LazyLoadProxy<T> implements MethodInterceptor {

    private T entity;
    private boolean invoked = false;
    private Class<T> entityClass;
    private EntityManager entityManager;
    private String query;

    public LazyLoadProxy(Class<T> entityClass, EntityManager entityManager, String query) {
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
