package com.mmaricic.worm.helpers;

import javax.persistence.*;
import java.util.Calendar;
import java.util.Date;

@Entity
public class User {
    public static int stat = 2;
    private Integer id;
    private String address;
    private Date dateOfBirth;
    private String email;
    private String password;
    private Name name;

    @Id
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    @Column(name = "home_address")
    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Transient
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Embedded
    public Name getName() {
        return name;
    }

    public void setName(Name name) {
        this.name = name;
    }

    public void setDateOfBirth(Date dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    // This will be put in database
    public int getAge() {
        if (dateOfBirth != null) {
            Calendar userAge = Calendar.getInstance();
            userAge.setTime(dateOfBirth);
            return Calendar.getInstance().get(Calendar.YEAR) - userAge.get(Calendar.YEAR);
        }
        return 0;
    }

    @Embeddable
    public static class Name {
        @Column(name = "first_name") // This will be ignored because access type is inherited from parent.
        private String firstname;
        private String lastname;

        public Name() {
        }

        public Name(String firstname, String lastname) {
            this.firstname = firstname;
            this.lastname = lastname;
        }

        public String getFirstname() {
            return firstname;
        }

        public void setFirstname(String firstname) {
            this.firstname = firstname;
        }

        public String getLastname() {
            return lastname;
        }

        public void setLastname(String lastname) {
            this.lastname = lastname;
        }
    }
}