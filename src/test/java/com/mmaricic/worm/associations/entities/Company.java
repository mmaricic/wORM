package com.mmaricic.worm.associations.entities;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Company {
    @Id
    @GeneratedValue
    private Long id;
    private String name;
    @OneToOne
    @JoinColumn(name = "ceo_id")
    private User CEO;
    @OneToOne(mappedBy = "company", cascade = CascadeType.ALL)
    private Phone phoneNumber;
    @OneToMany(cascade = CascadeType.ALL)
    //@JoinColumn(name = "company_id")
    private List<User> employees = new ArrayList<>();

    public Company(String name) {
        this.name = name;
    }

    public Company() {
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

    public User getCEO() {
        return CEO;
    }

    public void setCEO(User CEO) {
        this.CEO = CEO;
    }

    public List<User> getEmployees() {
        return employees;
    }

    public void setEmployees(List<User> employees) {
        this.employees = employees;
    }

    public Phone getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(Phone phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void addEmployee(User user) {
        employees.add(user);
        user.setWorkplace(this);
    }

    public void removeEmployee(User user) {
        employees.remove(user);
        user.setWorkplace(null);
    }
}
