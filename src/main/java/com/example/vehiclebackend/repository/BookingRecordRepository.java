package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BookingRecordRepository extends JpaRepository<BookingRecord, Long> {

    List<BookingRecord> findByVehicleOrderByCheckedOutAtDesc(Vehicle vehicle);

    /** One page of fleet-wide bookings, with the vehicle fetched in the same query
     *  so callers can read its id/kennzeichen without an N+1. Sort/paging come from
     *  the {@link Pageable}; the {@code vehicle} join is to-one, so paging still runs
     *  in SQL (no in-memory pagination). */
    @Query(value = "select b from BookingRecord b join fetch b.vehicle",
            countQuery = "select count(b) from BookingRecord b")
    Page<BookingRecord> findAllWithVehicle(Pageable pageable);

    /** The still-open booking for a vehicle (checked out, not yet returned), if any. */
    Optional<BookingRecord> findFirstByVehicleAndCheckedInAtIsNullOrderByCheckedOutAtDesc(Vehicle vehicle);

    boolean existsByVehicle(Vehicle vehicle);

    void deleteByVehicle(Vehicle vehicle);
}
