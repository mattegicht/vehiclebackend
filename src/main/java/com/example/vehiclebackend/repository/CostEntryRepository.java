package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.CostEntry;
import com.example.vehiclebackend.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CostEntryRepository extends JpaRepository<CostEntry, Long> {

    List<CostEntry> findByVehicleOrderByOccurredAtDesc(Vehicle vehicle);

    /** Every cost entry across the fleet, newest first, with the vehicle fetched in
     *  the same query so the dashboard can read its id/kennzeichen without an N+1. */
    @Query("select c from CostEntry c join fetch c.vehicle order by c.occurredAt desc")
    List<CostEntry> findAllWithVehicle();

    void deleteByVehicle(Vehicle vehicle);
}
