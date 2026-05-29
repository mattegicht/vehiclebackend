package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    List<Vehicle> findAllByUser(User user);
}
