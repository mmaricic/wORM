package com.mmaricic.worm;

import com.mmaricic.worm.exceptions.EntityException;
import com.mmaricic.worm.helpers.Car;
import com.mmaricic.worm.helpers.SmartCar;
import com.mmaricic.worm.helpers.User;
import com.mmaricic.worm.helpers.Vehicle;
import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;

import static org.junit.jupiter.api.Assertions.*;

public class InheritenceTest {
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String URL = "jdbc:mysql://localhost:3306/orm";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "password";
    private static SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

    @BeforeAll
    static void setUp() {
        EntityManagerFactory.configureDatabase(DRIVER, URL, USERNAME, PASSWORD);
    }

    @AfterAll
    static void removeConfig() throws Exception {
        EntityManagerFactory.removeConfiguration();
    }

    @AfterEach
    void tearDown() {
        BasicDataSource bds = new BasicDataSource();
        bds.setDriverClassName(DRIVER);
        bds.setUrl(URL);
        bds.setUsername(USERNAME);
        bds.setPassword(PASSWORD);
        try (Connection conn = bds.getConnection();
             Statement stm = conn.createStatement()) {
            stm.executeUpdate("DELETE FROM Vehicle");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Test
    void save() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        Vehicle v = new Vehicle();
        v.name = "vehicle";
        em.save(v);

        Car c = new Car();
        c.name = "car";
        c.numOfDoors = 4;
        em.save(c);

        SmartCar sc = new SmartCar();
        sc.name = "smart car";
        sc.numOfDoors = 2;
        sc.price = 10000.0;
        em.save(sc);

        v = em.find(Vehicle.class, v.id);
        assertEquals("vehicle", v.name);

        c = em.find(Car.class, c.id);
        assertEquals("car", c.name);
        assertEquals((Object) 4, c.numOfDoors);

        sc = em.find(SmartCar.class, sc.id);
        assertEquals("smart car", sc.name);
        assertEquals((Object) 2, sc.numOfDoors);
        assertEquals(10000.0, sc.price);
    }


    @Test
    void delete() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        Car c = new Car();
        c.name = "car";
        c.numOfDoors = 4;
        em.save(c);

        c = em.find(Car.class, c.id);
        assertEquals("car", c.name);
        assertEquals((Object) 4, c.numOfDoors);

        em.delete(c);

        c = em.find(Car.class, c.id);

        assertNull(c);
    }

    @Test
    void update() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        Car c = new Car();
        c.name = "car";
        c.numOfDoors = 4;
        em.save(c);

        c = em.find(Car.class, c.id);
        assertEquals("car", c.name);
        assertEquals((Object) 4, c.numOfDoors);

        c.name = "new name";

        em.update(c);

        c = em.find(Car.class, c.id);

        assertEquals("new name", c.name);
        assertEquals(4, c.numOfDoors);
    }

    @Test
    void find() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        Vehicle v = new Vehicle();
        v.name = "name";
        em.save(v);

        Car c = new Car();
        c.name = "name";
        c.numOfDoors = 4;
        em.save(c);

        SmartCar sc = new SmartCar();
        sc.name = "name";
        sc.numOfDoors = 2;
        sc.price = 10000.0;
        em.save(sc);
        LazyList<Car> result = em.find(Car.class).where("name='name'");
        assertEquals(1, result.size());
    }

    @Test
    void withOneToMany() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        Car c = new Car();
        c.name = "name";
        c.numOfDoors = 4;

        User user = new User();
        user.setAddress("john@mail.com");

        c.owner = user;

        assertThrows(EntityException.class, () -> em.save(c));

        em.save(user);
        em.save(c);

        user = em.find(User.class, user.getId());
        assertEquals(1, user.getCars().size());

        Car foundCar = em.find(Car.class, c.id);
        assertEquals(user.getId(), foundCar.owner.getId());

    }
}
