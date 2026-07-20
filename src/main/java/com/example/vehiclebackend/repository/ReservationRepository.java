package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.Reservation;
import com.example.vehiclebackend.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** Reservations for a vehicle whose window overlaps [start, end). Two windows
     *  overlap iff each starts strictly before the other ends; abutting windows
     *  (one ends exactly when the next begins) do not clash. Used to reject
     *  double-booking. */
    @Query("select r from Reservation r where r.vehicle = :vehicle "
            + "and r.startTime < :end and r.endTime > :start")
    List<Reservation> findOverlapping(@Param("vehicle") Vehicle vehicle,
                                      @Param("start") Instant start,
                                      @Param("end") Instant end);

    /** Reservations covering :instant for a vehicle (started, not yet ended) —
     *  used to block a check-out by anyone other than the reserver. */
    @Query("select r from Reservation r where r.vehicle = :vehicle "
            + "and r.startTime <= :instant and r.endTime > :instant")
    List<Reservation> findActiveAt(@Param("vehicle") Vehicle vehicle,
                                   @Param("instant") Instant instant);

    /** Every reservation covering :instant across the fleet, vehicle fetched, so
     *  the list endpoint can label each vehicle "Reserviert von X" in one query. */
    @Query("select r from Reservation r join fetch r.vehicle "
            + "where r.startTime <= :instant and r.endTime > :instant")
    List<Reservation> findAllActiveAt(@Param("instant") Instant instant);

    List<Reservation> findByVehicleOrderByStartTimeAsc(Vehicle vehicle);

    /** Upcoming/ongoing fleet-wide reservations (window not yet ended), with the
     *  vehicle fetched in the same query to avoid an N+1, soonest first. */
    @Query("select r from Reservation r join fetch r.vehicle "
            + "where r.endTime > :now order by r.startTime asc")
    List<Reservation> findUpcomingWithVehicle(@Param("now") Instant now);

    void deleteByVehicle(Vehicle vehicle);
}
