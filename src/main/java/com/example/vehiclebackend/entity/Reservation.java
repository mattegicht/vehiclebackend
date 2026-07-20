package com.example.vehiclebackend.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A future claim on a vehicle for a [startTime, endTime) window, so two people
 * don't plan on the same car. Unlike a {@link BookingRecord} — which logs an
 * actual check-out/check-in that already happened — a reservation is forward
 * looking and never carries odometer readings. The user is stored as a username
 * snapshot (like BookingRecord) so the reservation survives user deletion.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private String username;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(length = 500)
    private String purpose;

    public Reservation() {}

    public Reservation(Vehicle vehicle, String username, Instant startTime, Instant endTime, String purpose) {
        this.vehicle = vehicle;
        this.username = username;
        this.startTime = startTime;
        this.endTime = endTime;
        this.purpose = purpose;
    }

    public Long getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
