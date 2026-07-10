package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BookingRecordRepository extends JpaRepository<BookingRecord, Long> {

    List<BookingRecord> findByVehicleOrderByCheckedOutAtDesc(Vehicle vehicle);

    /** Every booking across the fleet, newest first, with the vehicle fetched in
     *  the same query so callers can read its id/kennzeichen without an N+1. */
    @Query("select b from BookingRecord b join fetch b.vehicle order by b.checkedOutAt desc")
    List<BookingRecord> findAllWithVehicle();

    /** The still-open booking for a vehicle (checked out, not yet returned), if any. */
    Optional<BookingRecord> findFirstByVehicleAndCheckedInAtIsNullOrderByCheckedOutAtDesc(Vehicle vehicle);

    void deleteByVehicle(Vehicle vehicle);
}
