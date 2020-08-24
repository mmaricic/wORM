package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.DatabaseConfigurationException;
import org.apache.commons.dbcp2.BasicDataSource;

public class EntityManagerFactory {
    private static BasicDataSource dataSource = null;

    public static void configureDatabase(String driver, String url, String username, String password) {
        if (dataSource != null) {
            throw new DatabaseConfigurationException("Database configuration was already set!");
        }
        dataSource = new BasicDataSource();
        dataSource.setDriverClassName(driver);
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);

    }

    public static EntityManager getEntityManager() {
        if (dataSource == null) {
            throw new DatabaseConfigurationException(
                    "Configuration for database needs to be set first (EntityManagerFactory.configureDatabase).");
        }
        return new EntityManager(dataSource);
    }
}
