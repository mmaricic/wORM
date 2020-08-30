package com.mmaricic.worm.associations;

import com.mmaricic.worm.EntityManager;
import com.mmaricic.worm.EntityManagerFactory;
import com.mmaricic.worm.associations.entities.Address;
import com.mmaricic.worm.associations.entities.Company;
import com.mmaricic.worm.associations.entities.Phone;
import com.mmaricic.worm.associations.entities.User;
import com.mmaricic.worm.exceptions.EntityException;
import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AnnotationHandlerTest {
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String URL = "jdbc:mysql://localhost:3306/associations";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "password";

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
            stm.executeUpdate("DELETE FROM user_address");
            stm.executeUpdate("DELETE FROM phone");
            stm.executeUpdate("DELETE FROM company");
            stm.executeUpdate("DELETE FROM user");
            stm.executeUpdate("DELETE FROM address");


        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Test
    void oneToMany() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        Phone oldPhone = new Phone("923467");
        em.save(oldPhone);
        User user = new User("john doe");
        Phone phone1 = new Phone("012345");
        Phone phone2 = new Phone("056789");
        user.addPhone(phone1);
        user.addPhone(phone2);
        user.addPhone(oldPhone);
        em.save(user);

       /* User foundUser = em.find(User.class, user.getId());

        assertEquals(user.getId(), foundUser.getId());
        assertEquals(user.getName(), foundUser.getName());
        List<Phone> phones = foundUser.getPhones();
        assertEquals(phones.size(), 2);
        assertEquals(phones.get(0).getNumber(), phone1.getNumber());
        assertEquals(phones.get(1).getNumber(), phone2.getNumber());*/

        user.setName("new name");
        phone1.setNumber("000000");
        Phone phone3 = new Phone("999999");
        user.addPhone(phone3);

        em.update(user);

       /* foundUser = em.find(User.class, user.getId());
        assertEquals(user.getName(), foundUser.getName());
        phones = foundUser.getPhones();
        assertEquals(phones.size(), 3);
        Phone updatedPhone = em.find(Phone.class, phone1.getId());
        assertEquals(phone1.getNumber(), updatedPhone.getNumber());
        Phone newPhone = em.find(Phone.class, phone3.getId());
        assertEquals(phone3.getNumber(), newPhone.getNumber());*/

        em.delete(user);

        assertNull(em.find(User.class, user.getId()));
        assertNull(em.find(Phone.class, phone1.getId()));
        assertNull(em.find(Phone.class, phone2.getId()));
        assertNull(em.find(Phone.class, phone3.getId()));
    }

    @Test
    void ManyToOne() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        User user = new User("john doe");
        Phone phone = new Phone("012345");
        phone.setOwner(user);

        assertThrows(EntityException.class, () -> em.save(phone));

        em.save(user);
        em.save(phone);

        Company company = new Company("company");
        company.addEmployee(user);

        em.save(company);

       /* User foundUser = em.find(User.class, user.getId());

        assertEquals(user.getName(), foundUser.getName());
        List<Phone> phones = foundUser.getPhones();
        assertEquals(phones.size(), 1);
        assertEquals(phones.get(0).getNumber(), phone.getNumber());
*/
        user.setName("new name");
        phone.setNumber("000000");
        Phone phone2 = new Phone("999999");
        user.addPhone(phone2);

        em.update(phone);

        /*Phone foundPhone = em.find(Phone.class, phone.getId());
        assertEquals(phone.getNumber(), foundPhone.getNumber());
        foundUser = foundPhone.getOwner();
        assertEquals(user.getName(), foundUser.getName());
        assertEquals(user.getId(), foundUser.getId());
        phones = foundUser.getPhones();
        assertEquals(phones.size(), 2);
        assertEquals(phones.get(0).getId(), phone.getId());
        assertEquals(phones.get(1).getNumber(), phone2.getNumber());*/
        em.update(user);
        em.delete(phone);

        User foundUser = em.find(User.class, user.getId());
        assertNotNull(foundUser);
        //assertEquals(foundUser.getPhones().size(), 1);
        assertNull(em.find(Phone.class, phone.getId()));
        assertNotNull(em.find(Phone.class, phone2.getId()));
    }

    @Test
    void OneToOne() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        User user = new User("john doe");
        Company company = new Company("some company");
        Phone phone = new Phone("34567");

        company.setCEO(user);
        company.setPhoneNumber(phone);

        assertThrows(EntityException.class, () -> em.save(company));
        assertNull(company.getId());
        assertNull(phone.getId());

        em.save(user);
        em.save(company);

       /* User foundUser = em.find(User.class, user.getId());

        assertEquals(user.getName(), foundUser.getName());
        List<Phone> phones = foundUser.getPhones();
        assertEquals(phones.size(), 1);
        assertEquals(phones.get(0).getNumber(), phone.getNumber());
*/
        company.setName("new company name");
        user.setName("new name");
        phone.setNumber("0000");

        em.update(company);

        /*Phone foundPhone = em.find(Phone.class, phone.getId());
        assertEquals(phone.getNumber(), foundPhone.getNumber());
        foundUser = foundPhone.getOwner();
        assertEquals(user.getName(), foundUser.getName());
        assertEquals(user.getId(), foundUser.getId());
        phones = foundUser.getPhones();
        assertEquals(phones.size(), 2);
        assertEquals(phones.get(0).getId(), phone.getId());
        assertEquals(phones.get(1).getNumber(), phone2.getNumber());*/
        company.setName("user update name");
        phone.setNumber("23456");
        em.update(user);

        em.delete(user);
        assertNull(em.find(User.class, user.getId()));
        assertNotNull(em.find(Company.class, company.getId()));
        assertNotNull(em.find(Phone.class, phone.getId()));

        em.delete(company);

        assertNull(em.find(Company.class, company.getId()));
        assertNull(em.find(Phone.class, phone.getId()));
    }

    @Test
    void ManyToMany() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        User user = new User("john doe");
        Address address = new Address("no name", "Belgrade", "Serbia");
        user.addAddress(address);

        em.save(user);

        Address newAddress = new Address("address 2", "Belgrade", "Serbia");
        User newUser = new User("new user");

        newAddress.addResident(newUser);
        newAddress.addResident(user);
        newUser.addAddress(newAddress);

        em.save(newAddress);

        user.addAddress(newAddress);
        em.update(user);

        em.delete(user);
        assertNull(em.find(User.class, user.getId()));
        assertNotNull(em.find(Address.class, address.getId()));
        assertNotNull(em.find(Address.class, newAddress.getId()));
        List<Map<String, Object>> res = em.query("SELECT * FROM user_address WHERE user_id=" + user.getId());
        assertEquals(res.size(), 0);


        em.delete(newAddress);

        assertNull(em.find(Address.class, newAddress.getId()));
        assertNotNull(em.find(User.class, newUser.getId()));

        res = em.query("SELECT * FROM user_address WHERE address_id=" + newAddress.getId());
        assertEquals(res.size(), 0);
    }
}
