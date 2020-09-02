package com.mmaricic.worm.helpers;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

@Entity
@DiscriminatorValue("sc")
public class SmartCar extends Car {
    public double price;
}
