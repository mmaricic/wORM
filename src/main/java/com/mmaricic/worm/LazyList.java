package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.QueryException;

import java.util.*;

public class LazyList<T> implements List<T> {
    private List<T> delegate;
    private final StringJoiner sqlJoiner;
    private boolean firstWhereAdded = false;
    private final Class<T> entityClass;
    private final EntityParser entityParser;
    private final EntityManager entityManager;
    private final List<String> orderBy = new ArrayList<>();
    private Integer limit = null;
    private Integer offset = null;


    public LazyList(String sql, Class<T> entityClass, EntityManager entityManager) {
        sqlJoiner = new StringJoiner(" ");
        sqlJoiner.add(sql);
        this.entityClass = entityClass;
        entityParser = new EntityParser();
        this.entityManager = entityManager;
    }

    public LazyList<T> where(String sql) {
        if (firstWhereAdded) {
            sqlJoiner.add("AND");
        } else {
            sqlJoiner.add("WHERE");
        }
        sqlJoiner.add(sql);
        firstWhereAdded = true;
        return this;
    }

/*    public LazyList<T>  orWhere(String sql) {
        if(!firstWhereAdded) {
            throw new QueryException("Can't use OR where when there is no other condition.");
        }
        sqlJoiner.add("OR").add(sql);
        return this;
    }*/

    public LazyList<T> orderBy(String order) {
        String[] spl = order.split(" ");
        if (spl.length > 2) {
            throw new QueryException(
                    "Order by accepts only one column name and optionally 'asc' or 'desc' for order direction");
        }
        if (spl.length == 2) {
            if (!spl[1].equalsIgnoreCase("asc") && !spl[1].equalsIgnoreCase("desc")) {
                throw new QueryException(
                        "Order by accepts only one column name and optionally 'asc' or 'desc' for order direction");
            }
        }
        orderBy.add(order);
        return this;
    }

    public LazyList<T> limit(int limit) {
        if (this.limit != null) {
            throw new QueryException("You already set limit for your query");
        }
        if (limit < 0) {
            throw new QueryException("Limit can't be negative or zero.");
        }
        this.limit = limit;
        return this;
    }

    public LazyList<T> offset(int offset) {
        if (this.offset != null) {
            throw new QueryException("You already set offset for your query");
        }
        if (offset < 0) {
            throw new QueryException("Offset can't be negative.");
        }
        this.offset = offset;
        return this;
    }

    public T first() {
        limit = 1;
        init();
        if (delegate.size() == 0) {
            return null;
        }
        return delegate.get(0);
    }

    private void init() {
        if (delegate != null)
            return;

        if (orderBy.size() > 0) {
            sqlJoiner.add("ORDER BY");
            StringJoiner orderJoin = new StringJoiner(", ");
            for (String order : orderBy) {
                orderJoin.add(order);
            }
            sqlJoiner.add(orderJoin.toString());
        }
        if (limit != null) {
            sqlJoiner.add("LIMIT").add(limit.toString());
        }
        if (offset != null) {
            sqlJoiner.add("OFFSET").add(offset.toString());
        }

        List<Map<String, Object>> result = entityManager.query(sqlJoiner.toString() + ";");
        delegate = new ArrayList<>();
        for (Map<String, Object> row : result) {
            delegate.add(entityParser.convertRowToEntity(entityClass, row, null));
        }
    }

    @Override
    public int size() {
        init();
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        init();
        return delegate.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        init();
        return delegate.contains(o);
    }

    @Override
    public Iterator<T> iterator() {
        init();
        return delegate.iterator();
    }

    @Override
    public Object[] toArray() {
        init();
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        init();
        return delegate.toArray(a);
    }

    @Override
    public boolean add(T t) {
        init();
        return delegate.add(t);
    }

    @Override
    public boolean remove(Object o) {
        init();
        return delegate.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        init();
        return delegate.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        init();
        return delegate.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        init();
        return delegate.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        init();
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        init();
        return delegate.retainAll(c);
    }

    @Override
    public void clear() {
        if (delegate != null) {
            delegate.clear();
        } else {
            delegate = new ArrayList<>();
        }
    }

    @Override
    public T get(int index) {
        init();
        return delegate.get(index);
    }

    @Override
    public T set(int index, T element) {
        init();
        return delegate.set(index, element);
    }

    @Override
    public void add(int index, T element) {
        init();
        delegate.add(index, element);
    }

    @Override
    public T remove(int index) {
        init();
        return delegate.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        init();
        return delegate.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        init();
        return delegate.lastIndexOf(o);
    }

    @Override
    public ListIterator<T> listIterator() {
        init();
        return delegate.listIterator();
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        init();
        return delegate.listIterator(index);
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        init();
        return delegate.subList(fromIndex, toIndex);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        init();
        return delegate.equals(o);
    }

    @Override
    public int hashCode() {
        init();
        return delegate.hashCode();
    }
}