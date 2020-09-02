package com.mmaricic.worm.helpers;

import javax.persistence.Column;
import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import javax.persistence.ManyToOne;

@Entity
@DiscriminatorValue("c")
public class Car extends Vehicle {
    @Column(name = "doors_num")
    public int numOfDoors;
    @ManyToOne
    public User owner;
}
