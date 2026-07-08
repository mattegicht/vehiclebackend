package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRecordRepository extends JpaRepository<BookingRecord, Long> {

    List<BookingRecord> findByVehicleOrderByCheckedOutAtDesc(Vehicle vehicle);

    /** The still-open booking for a vehicle (checked out, not yet returned), if any. */
    Optional<BookingRecord> findFirstByVehicleAndCheckedInAtIsNullOrderByCheckedOutAtDesc(Vehicle vehicle);

    void deleteByVehicle(Vehicle vehicle);
}
