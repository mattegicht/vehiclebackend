package com.example.vehiclebackend.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One check-out/check-in cycle of a vehicle — the audit log entry. Created when a
 * vehicle is taken and closed (check-in time + km) when it's returned. The user is
 * stored as a username snapshot rather than an FK so the log survives user deletion
 * and doesn't block it.
 */
@Entity
@Table(name = "booking_records")
public class BookingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private String username;

    @Column(name = "checked_out_at", nullable = false)
    private Instant checkedOutAt;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "km_at_checkout", nullable = false)
    private int kmAtCheckout;

    @Column(name = "km_at_checkin")
    private Integer kmAtCheckin;

    public BookingRecord() {}

    public BookingRecord(Vehicle vehicle, String username, Instant checkedOutAt, int kmAtCheckout) {
        this.vehicle = vehicle;
        this.username = username;
        this.checkedOutAt = checkedOutAt;
        this.kmAtCheckout = kmAtCheckout;
    }

    public Long getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Instant getCheckedOutAt() { return checkedOutAt; }
    public void setCheckedOutAt(Instant checkedOutAt) { this.checkedOutAt = checkedOutAt; }
    public Instant getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(Instant checkedInAt) { this.checkedInAt = checkedInAt; }
    public int getKmAtCheckout() { return kmAtCheckout; }
    public void setKmAtCheckout(int kmAtCheckout) { this.kmAtCheckout = kmAtCheckout; }
    public Integer getKmAtCheckin() { return kmAtCheckin; }
    public void setKmAtCheckin(Integer kmAtCheckin) { this.kmAtCheckin = kmAtCheckin; }
}
