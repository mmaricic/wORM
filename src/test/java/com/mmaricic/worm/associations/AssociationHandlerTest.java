package com.mmaricic.worm.associations;

import com.mmaricic.worm.EntityManager;
import com.mmaricic.worm.EntityManagerFactory;
import com.mmaricic.worm.associations.entities.Address;
import com.mmaricic.worm.associations.entities.Company;
import com.mmaricic.worm.associations.entities.Phone;
import com.mmaricic.worm.associations.entities.User;
import com.mmaricic.worm.exceptions.EntityException;
import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AssociationHandlerTest {
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String URL = "jdbc:mysql://localhost:3306/associations";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "password";

    @BeforeAll
    static void setUp() {
        EntityManagerFactory.configureDatabase(DRIVER, URL, USERNAME, PASSWORD);
    }

    @AfterAll
    static void tearDown() throws Exception {
        BasicDataSource bds = new BasicDataSource();
        bds.setDriverClassName(DRIVER);
        bds.setUrl(URL);
        bds.setUsername(USERNAME);
        bds.setPassword(PASSWORD);
        try (Connection conn = bds.getConnection();
             Statement stm = conn.createStatement()) {
            stm.executeUpdate("DELETE FROM users_addresses");
            stm.executeUpdate("DELETE FROM phone");
            stm.executeUpdate("DELETE FROM company");
            stm.executeUpdate("DELETE FROM user");
            stm.executeUpdate("DELETE FROM address");


        } catch (SQLException e) {
            e.printStackTrace();
        }
        EntityManagerFactory.removeConfiguration();
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

        User foundUser = em.find(User.class, user.getId());

        assertEquals(user.getId(), foundUser.getId());
        assertEquals(user.getName(), foundUser.getName());
        List<Phone> phones = foundUser.getPhones();
        assertEquals(3, phones.size());
        assertEquals(oldPhone.getNumber(), phones.get(0).getNumber());
        assertEquals(phone1.getNumber(), phones.get(1).getNumber());
        assertEquals(phone2.getNumber(), phones.get(2).getNumber());

        user.setName("new name");
        phone1.setNumber("000000");
        Phone phone3 = new Phone("999999");
        user.addPhone(phone3);
        user.removePhone(oldPhone);

        em.update(user);

        foundUser = em.find(User.class, user.getId());
        assertEquals(user.getName(), foundUser.getName());
        assertEquals(3, foundUser.getPhones().size());

        Phone updatedPhone = em.find(Phone.class, phone1.getId());
        assertEquals(phone1.getNumber(), updatedPhone.getNumber());

        Phone newPhone = em.find(Phone.class, phone3.getId());
        assertEquals(phone3.getNumber(), newPhone.getNumber());
        assertNull(em.find(Phone.class, oldPhone.getId()));

        phone3.setOwner(null);
        em.update(phone3);
        updatedPhone = em.find(Phone.class, phone3.getId());
        assertNull(updatedPhone.getOwner());

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
        user.addPhone(phone);
        Company company = new Company("company");
        company.addEmployee(user);

        em.save(company);

        User foundUser = em.find(User.class, user.getId());

        assertEquals(user.getName(), foundUser.getName());
        List<Phone> phones = foundUser.getPhones();
        assertEquals(1, phones.size());
        assertEquals(phone.getNumber(), phones.get(0).getNumber());

        user.setName("new name");
        phone.setNumber("000000");
        Phone phone2 = new Phone("999999");
        user.addPhone(phone2);

        em.update(phone);

        Phone foundPhone = em.find(Phone.class, phone.getId());
        assertEquals(phone.getNumber(), foundPhone.getNumber());
        foundUser = foundPhone.getOwner();
        assertNotEquals(user.getName(), foundUser.getName());
        assertEquals(user.getId(), foundUser.getId());
        phones = foundUser.getPhones();
        assertEquals(1, phones.size());

        em.update(user);
        foundUser = em.find(User.class, user.getId());
        phones = foundUser.getPhones();
        assertEquals(2, phones.size());
        assertEquals(phone.getId(), phones.get(0).getId());
        assertEquals(phone2.getId(), phones.get(1).getId());

        user.removePhone(phone2);
        Phone newPhone = new Phone("66666");
        user.addPhone(newPhone);
        em.update(user);
        assertNull(em.find(Phone.class, phone2.getId()));
        assertNotNull(em.find(Phone.class, newPhone.getId()));
        company.removeEmployee(user);
        em.update(company);
        assertEquals(0, em.find(Company.class, company.getId()).getEmployees().size());
        assertNotNull(em.find(User.class, user.getId()));

        em.delete(phone);

        foundUser = em.find(User.class, user.getId());
        assertNotNull(foundUser);
        assertEquals(1, foundUser.getPhones().size());

        em.delete(user);

        assertNull(em.find(Phone.class, newPhone.getId()));
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

        Company foundCompany = em.find(Company.class, company.getId());

        assertEquals(company.getName(), foundCompany.getName());
        assertEquals(phone.getNumber(), foundCompany.getPhoneNumber().getNumber());
        assertEquals(user.getId(), foundCompany.getCEO().getId());

        company.setName("new company name");
        user.setName("new name");
        phone.setNumber("0000");

        em.update(company);

        Phone foundPhone = em.find(Phone.class, phone.getId());
        assertEquals(phone.getNumber(), foundPhone.getNumber());
        User foundUser = em.find(User.class, user.getId());
        assertNotEquals(user.getName(), foundUser.getName());

        em.delete(company);

        assertNull(em.find(Company.class, company.getId()));
        assertNull(em.find(Phone.class, phone.getId()));
        assertNotNull(em.find(User.class, user.getId()));
    }

    @Test
    void ManyToMany() {
        EntityManager em = EntityManagerFactory.getEntityManager();

        User user = new User("john doe");
        Address address = new Address("no name", "Belgrade", "Serbia");
        user.addAddress(address);

        em.save(user);

        Address foundAddress = em.find(Address.class, address.getId());
        assertEquals(1, foundAddress.getResidents().size());
        assertEquals(user.getName(), foundAddress.getResidents().get(0).getName());

        Address newAddress = new Address("address 2", "Belgrade", "Serbia");
        User newUser = new User("new user");
        newAddress.addResident(newUser);
        newUser.addAddress(newAddress);
        newAddress.addResident(user);
        user.addAddress(newAddress);

        em.save(newAddress);

        foundAddress = em.find(Address.class, newAddress.getId());
        assertEquals(2, foundAddress.getResidents().size());
        assertNotNull(em.find(User.class, newUser.getId()));

        user.removeAddress(address);
        user.addAddress(newAddress);

        em.update(user);

        foundAddress = em.find(Address.class, address.getId());
        assertEquals(0, foundAddress.getResidents().size());

        User foundUser = em.find(User.class, user.getId());
        assertEquals(1, foundUser.getAddresses().size());

        em.delete(newUser);

        assertNull(em.find(User.class, newUser.getId()));
        assertNotNull(em.find(Address.class, newAddress.getId()));
        List<Map<String, Object>> res = em.query("SELECT * FROM users_addresses WHERE user_id=" + newUser.getId());
        assertEquals(0, res.size());
        res = em.query("SELECT * FROM users_addresses WHERE address_id=" + newAddress.getId());
        assertEquals(1, res.size());

        em.delete(newAddress);

        assertNull(em.find(Address.class, newAddress.getId()));
        assertNotNull(em.find(User.class, user.getId()));

        res = em.query("SELECT * FROM users_addresses WHERE address_id=" + newAddress.getId());
        assertEquals(0, res.size());
    }
}
