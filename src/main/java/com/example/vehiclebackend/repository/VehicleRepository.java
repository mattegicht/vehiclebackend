package com.example.vehiclebackend.repository;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    /** Fetch-joins both user relations so the list endpoint issues a single query. */
    @Query("select v from Vehicle v join fetch v.user left join fetch v.inUseBy")
    List<Vehicle> findAllWithUsers();

    /** Row-locked read used by toggleInUse to serialize concurrent check-outs. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vehicle v where v.id = :id")
    Optional<Vehicle> findWithLockById(@Param("id") Long id);

    boolean existsByUser(User user);

    List<Vehicle> findAllByInUseBy(User user);
}
