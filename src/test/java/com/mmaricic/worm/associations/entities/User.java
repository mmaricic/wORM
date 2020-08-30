package com.mmaricic.worm.associations.entities;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class User {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company workplace;
    @ManyToMany(cascade = CascadeType.PERSIST)
    @JoinTable(name = "user_address", joinColumns = @JoinColumn(name = "user_id"))
    private List<Address> addresses = new ArrayList<>();
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "owner")
    private List<Phone> phones = new ArrayList<>();
    @OneToOne(mappedBy = "CEO")
    private Company ownerOf;


    public User(String name) {
        this.name = name;
    }

    public User() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Company getWorkplace() {
        return workplace;
    }

    public void setWorkplace(Company workplace) {
        this.workplace = workplace;
    }

    public List<Address> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<Address> addresses) {
        this.addresses = addresses;
    }

    public List<Phone> getPhones() {
        return phones;
    }

    public void setPhones(List<Phone> phones) {
        this.phones = phones;
    }

    public void addPhone(Phone phone) {
        phones.add(phone);
        phone.setOwner(this);
    }

    public void removePhone(Phone phone) {
        phones.remove(phone);
        phone.setOwner(null);
    }

    public Company getOwnerOf() {
        return ownerOf;
    }

    public void setOwnerOf(Company ownerOf) {
        this.ownerOf = ownerOf;
    }

    public void addAddress(Address a) {
        addresses.add(a);
    }

    public void removeAddress(Address a) {
        addresses.remove(a);
    }
}
