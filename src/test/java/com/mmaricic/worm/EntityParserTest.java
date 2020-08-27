package com.mmaricic.worm;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.mmaricic.worm.exceptions.AnnotationException;
import com.mmaricic.worm.exceptions.EntityIdException;
import com.mmaricic.worm.helpers.Company;
import com.mmaricic.worm.helpers.User;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.AbstractMap;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityParserTest {

    @Test
    void extractTableName() {
        @Entity
        class TestEntity {
        }

        EntityParser ep = new EntityParser();
        TestEntity testEntity = new TestEntity();

        String entClass = ep.extactTableName(testEntity.getClass());

        assertEquals(entClass, "TestEntity");
    }

    @Test
    void extractTableNameTableAnnotation() {
        final String tableName = "test_table";
        @Entity
        @Table(name = tableName)
        class TestEntity {
        }

        EntityParser ep = new EntityParser();
        TestEntity testEntity = new TestEntity();

        String entClass = ep.extactTableName(testEntity.getClass());

        assertEquals(entClass, tableName);
    }

    @Test
    void extractTableNameNoEntityAnnotation() {
        class TestEntity {
        }

        EntityParser ep = new EntityParser();
        TestEntity testEntity = new TestEntity();

        assertThrows(AnnotationException.class, () -> ep.extactTableName(testEntity.getClass()));
    }

    @Test
    void parseFromMethods() {
        Company company = new Company();
        company.setId(1);
        company.setName("Company");
        Calendar calendar = Calendar.getInstance();
        calendar.set(2012, Calendar.MAY, 4);
        company.setFoundingDate(calendar.getTime());
        Company.Address address = new Company.Address();
        address.setCountry("Serbia");
        address.setCity("Belgrade");
        address.setStreetName("Nemanjina");
        address.setHouseNumber(22);
        company.setAddress(address);

        HashMap<String, Object> expected = new HashMap<>();
        expected.put("id", 1);
        expected.put("name", "Company");
        expected.put("founding_date", calendar.getTime());
        expected.put("street_name", "Nemanjina");
        expected.put("house_number", 22);
        expected.put("city", "Belgrade");
        expected.put("country", "Serbia");

        EntityParser ep = new EntityParser();
        Map<String, Object> actual = ep.parse(company, true);
        MapDifference<String, Object> res = Maps.difference(expected, actual);
        assertTrue(res.areEqual(), res.toString());
    }

    @Test
    void parseFromFields() {
        User user = new User();
        user.setId(2L);
        user.setAddress("dummy");
        user.setEmail("john@mail.com");
        user.setPassword("pass123");
        user.setName(new User.Name("john", "doe"));

        HashMap<String, Object> expected = new HashMap<>();
        expected.put("id", 2L);
        expected.put("home_address", "dummy");
        expected.put("email", "john@mail.com");
        expected.put("firstname", "john");
        expected.put("lastname", "doe");
        expected.put("age", 0);

        EntityParser ep = new EntityParser();
        Map<String, Object> actual = ep.parse(user, true);
        MapDifference<String, Object> res = Maps.difference(expected, actual);
        assertTrue(res.areEqual(), res.toString());
    }

    @Test
    void parseNoId() {
        @Entity
        class Person {
            public String name;
        }

        EntityParser ep = new EntityParser();
        Person person = new Person();

        assertThrows(EntityIdException.class, () -> ep.parse(person, true));
    }

    @Test
    void extractIdFromField() {
        @Entity
        class Person {
            @Id
            @Column(name = "user_id")
            private int id;
            private String name;

            public int getId() {
                return id;
            }

            public String getName() {
                return name;
            }

            public void setId(int id) {
                this.id = id;
            }


            public void setName(String name) {
                this.name = name;
            }
        }

        EntityParser ep = new EntityParser();
        Person person = new Person();
        person.setId(2);
        person.setName("John Doe");
        AbstractMap.SimpleEntry<String, Object> expected = new AbstractMap.SimpleEntry<>("user_id", 2);

        AbstractMap.SimpleEntry<String, Object> actual = ep.extractId(person);
        assertEquals(expected, actual);
    }

    @Test
    void extractIdFromMethod() {
        @Entity
        class Person {
            private int id;
            private String name;

            @Id
            @Column(name = "user_id")
            public int getId() {
                return id;
            }

            public String getName() {
                return name;
            }

            public void setId(int id) {
                this.id = id;
            }


            public void setName(String name) {
                this.name = name;
            }
        }

        EntityParser ep = new EntityParser();
        Person person = new Person();
        person.setId(2);
        person.setName("John Doe");
        AbstractMap.SimpleEntry<String, Object> expected = new AbstractMap.SimpleEntry<>("user_id", 2);

        AbstractMap.SimpleEntry<String, Object> actual = ep.extractId(person);
        assertEquals(expected, actual);
    }

    @Test
    void reconstruct() {
        HashMap<String, Object> entityElements = new HashMap<>();
        entityElements.put("id", 2);
        entityElements.put("home_address", "dummy");
        entityElements.put("email", "john@mail.com");
        entityElements.put("firstname", "john");
        entityElements.put("lastname", "doe");
        entityElements.put("age", 24);


        EntityParser ep = new EntityParser();
        User user = ep.convertRowToEntity(User.class, entityElements, null);

        assertEquals(user.getId(), (Object) 2);
        assertEquals(user.getAddress(), "dummy");
        assertEquals(user.getEmail(), "john@mail.com");
        assertNull(user.getPassword());
        assertNotNull(user.getName());
        assertEquals(user.getName().getFirstname(), "john");
        assertEquals(user.getName().getLastname(), "doe");

    }
}