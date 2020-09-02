package com.mmaricic.worm.helpers;

import javax.persistence.*;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public class Vehicle {
    @Id
    @GeneratedValue
    public Integer id;
    public String name;

}
