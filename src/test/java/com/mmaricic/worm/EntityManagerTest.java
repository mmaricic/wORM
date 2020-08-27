package com.mmaricic.worm;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.mmaricic.worm.helpers.Company;
import com.mmaricic.worm.helpers.User;
import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityManagerTest {
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String URL = "jdbc:mysql://localhost:3306/orm";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "password";
    private static SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

    @BeforeAll
    static void setUp() {
        EntityManagerFactory.configureDatabase(DRIVER, URL, USERNAME, PASSWORD);
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
            stm.executeUpdate("DELETE FROM User");
            stm.executeUpdate("DELETE FROM companies");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Test
    void save() throws Exception {
        EntityManager em = EntityManagerFactory.getEntityManager();

        com.mmaricic.worm.helpers.User user = new com.mmaricic.worm.helpers.User();
        user.setId(2);
        user.setAddress("dummy");
        user.setEmail("john@mail.com");
        user.setPassword("pass123");
        user.setName(new com.mmaricic.worm.helpers.User.Name("john", "doe"));
        Calendar calendar = Calendar.getInstance();
        calendar.set(1996, Calendar.MARCH, 19);
        user.setDateOfBirth(formatter.parse(formatter.format(calendar.getTime())));

        em.save(user);
        com.mmaricic.worm.helpers.User foundUser = em.find(com.mmaricic.worm.helpers.User.class, 2);

        assertEquals(user.getId(), foundUser.getId());
        assertEquals(user.getAddress(), foundUser.getAddress());
        assertEquals(user.getEmail(), foundUser.getEmail());
        assertNull(foundUser.getPassword());
        assertEquals(user.getName().getFirstname(), foundUser.getName().getFirstname());
        assertEquals(user.getName().getLastname(), foundUser.getName().getLastname());
    }

    @Test
    void delete() {
        EntityManager em = EntityManagerFactory.getEntityManager();
        User user = new User();
        user.setId(1);
        user.setEmail("marija@mail.com");
        em.save(user);

        User foundUser = em.find(User.class, 1);
        assertEquals(user.getId(), foundUser.getId());
        assertEquals(user.getEmail(), foundUser.getEmail());

        em.delete(user);
        foundUser = em.find(User.class, 1);

        assertNull(foundUser);
    }

    @Test
    void update() {
        EntityManager em = EntityManagerFactory.getEntityManager();
        User user = new User();
        user.setId(1);
        user.setEmail("marija@mail.com");
        user.setAddress("old");
        em.save(user);

        User foundUser = em.find(User.class, 1);
        assertEquals(user.getId(), foundUser.getId());
        assertEquals(user.getEmail(), foundUser.getEmail());
        assertEquals(user.getAddress(), foundUser.getAddress());
        assertNull(foundUser.getName().getFirstname());
        assertNull(foundUser.getName().getLastname());

        user.setAddress("new");
        user.setName(new User.Name("john", "doe"));

        em.update(user);

        User updatedUser = em.find(User.class, 1);

        assertEquals(foundUser.getId(), updatedUser.getId());
        assertEquals(foundUser.getEmail(), updatedUser.getEmail());
        assertEquals("new", updatedUser.getAddress());
        assertEquals("john", updatedUser.getName().getFirstname());
    }

    @Test
    void find() throws Exception {
        EntityManager em = EntityManagerFactory.getEntityManager();
        Company notReturnedOffset = new Company();
        notReturnedOffset.setId(1);
        notReturnedOffset.setName("Company not returned because of offset");
        Calendar calendar = Calendar.getInstance();
        calendar.set(2012, Calendar.MAY, 4);
        notReturnedOffset.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        Company.Address address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Belgrade");
        notReturnedOffset.setAddress(address);

        Company company = new Company();
        company.setId(2);
        company.setName("Company");
        calendar.set(2016, Calendar.JULY, 10);
        company.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Belgrade");
        address.setHouseNumber(0);
        company.setAddress(address);


        Company company2 = new Company();
        company2.setId(3);
        company2.setName("Company 2");
        calendar.set(2014, Calendar.APRIL, 17);
        company2.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Nis");
        company2.setAddress(address);

        Company notReturnedConditions = new Company();
        notReturnedConditions.setId(4);
        notReturnedConditions.setName("Ignored Company");
        calendar.set(2020, Calendar.JUNE, 11);
        notReturnedConditions.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Italy");
        address.setCity("Milano");
        notReturnedConditions.setAddress(address);

        Company notReturnedLimit = new Company();
        notReturnedLimit.setId(5);
        notReturnedLimit.setName("Company not returned because of limit");
        calendar.set(2018, Calendar.OCTOBER, 12);
        notReturnedLimit.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Belgrade");
        notReturnedLimit.setAddress(address);

        em.save(notReturnedOffset);
        em.save(company);
        em.save(company2);
        em.save(notReturnedConditions);
        em.save(notReturnedLimit);

        List<Company> companies = em.find(Company.class)
                .where("country='Serbia'")
                .where("founding_date<'2020-01-01'")
                .orderBy("id asc")
                .offset(1)
                .limit(2);

        assertEquals(companies.size(), 2);
        compareCompanies(company, companies.get(0));
        compareCompanies(company2, companies.get(1));
    }

    @Test
    void findFirst() throws Exception {
        EntityManager em = EntityManagerFactory.getEntityManager();
        Calendar calendar = Calendar.getInstance();

        Company company = new Company();
        company.setId(2);
        company.setName("Company");
        calendar.set(2016, Calendar.JULY, 10);
        company.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        Company.Address address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Belgrade");
        address.setHouseNumber(0);
        company.setAddress(address);


        Company company2 = new Company();
        company2.setId(3);
        company2.setName("Company 2");
        calendar.set(2014, Calendar.APRIL, 17);
        company2.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Nis");
        company2.setAddress(address);

        Company notReturnedConditions = new Company();
        notReturnedConditions.setId(4);
        notReturnedConditions.setName("Ignored Company");
        calendar.set(2020, Calendar.JUNE, 11);
        notReturnedConditions.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Italy");
        address.setCity("Milano");
        notReturnedConditions.setAddress(address);

        em.save(company);
        em.save(company2);
        em.save(notReturnedConditions);

        Company result = em.find(Company.class)
                .where("country='Serbia'")
                .orderBy("id desc")
                .first();

        compareCompanies(company2, result);
    }

    @Test
    void query() throws Exception {
        Calendar calendar = Calendar.getInstance();

        EntityManager em = EntityManagerFactory.getEntityManager();
        Company company = new Company();
        company.setId(2);
        company.setName("Company");
        calendar.set(2016, Calendar.JULY, 10);
        company.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        Company.Address address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Belgrade");
        address.setHouseNumber(0);
        company.setAddress(address);


        Company company2 = new Company();
        company2.setId(3);
        company2.setName("Company 2");
        calendar.set(2014, Calendar.APRIL, 17);
        company2.setFoundingDate(formatter.parse(formatter.format(calendar.getTime())));
        address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Nis");
        company2.setAddress(address);

        em.save(company);
        em.save(company2);

        Map<String, Object> expected1 = new HashMap<>();
        expected1.put("name", "Company");
        expected1.put("city", "Belgrade");
        Map<String, Object> expected2 = new HashMap<>();
        expected2.put("name", "Company 2");
        expected2.put("city", "Nis");

        List<Map<String, Object>> companies = em.query(
                "SELECT name, city FROM companies WHERE country='Serbia' ORDER BY city;");

        assertEquals(companies.size(), 2);
        MapDifference<String, Object> diff = Maps.difference(expected1, companies.get(0));
        assertTrue(diff.areEqual(), diff.toString());
        diff = Maps.difference(expected2, companies.get(1));
        assertTrue(diff.areEqual(), diff.toString());

    }

    private void compareCompanies(Company expected, Company actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getFoundingDate(), actual.getFoundingDate());
        assertEquals(expected.getAddress().getCity(), expected.getAddress().getCity());
        assertEquals(expected.getAddress().getCountry(), expected.getAddress().getCountry());
        assertEquals(expected.getAddress().getHouseNumber(), expected.getAddress().getHouseNumber());
        assertEquals(expected.getAddress().getStreetName(), expected.getAddress().getStreetName());

    }
}