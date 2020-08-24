package com.mmaricic.worm.helpers;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "companies")
public class Company {
    @Id
    private int id;
    private String name;
    @Embedded
    private Address address;
    @Column(name = "founding_date")
    private Date foundingDate;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    @Column(name = "ignored_annotation")
    public Date getFoundingDate() {
        return foundingDate;
    }

    public void setFoundingDate(Date founding_date) {
        this.foundingDate = founding_date;
    }

    @Embeddable
    public static class Address {
        @Column(name = "street_name")
        private String streetName;
        @Column(name = "house_number")
        private int houseNumber;
        private String city;
        private String country;

        public String getStreetName() {
            return streetName;
        }

        public void setStreetName(String street_name) {
            this.streetName = street_name;
        }

        public int getHouseNumber() {
            return houseNumber;
        }

        public void setHouseNumber(int house_number) {
            this.houseNumber = house_number;
        }

        @Column(name = "ignored_annotation")
        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }
    }
}
