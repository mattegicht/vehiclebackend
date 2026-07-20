package com.example.vehiclebackend.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A single refuel or charge event for a vehicle — the Tank-/Ladekosten-Log. Stores
 * the amount (liters or kWh), the total cost, and the odometer at the time so
 * cost-per-km can be derived. The user is a username snapshot (like BookingRecord)
 * so the entry survives user deletion.
 */
@Entity
@Table(name = "cost_entries")
public class CostEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private String username;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    // "FUEL" (Kraftstoff) or "ELECTRIC" (Strom).
    @Column(name = "energy_type", nullable = false)
    private String energyType;

    // Liters for fuel, kWh for electric.
    @Column(nullable = false)
    private double amount;

    // Total cost of this refuel/charge, in euros.
    @Column(nullable = false)
    private double cost;

    // Odometer reading at the fill-up.
    @Column(nullable = false)
    private int kilometers;

    // Voll- (true) vs Teilbetankung (false).
    @Column(name = "full_tank", nullable = false)
    private boolean fullTank;

    @Column(length = 500)
    private String note;

    public CostEntry() {}

    public CostEntry(Vehicle vehicle, String username, Instant occurredAt, String energyType,
                     double amount, double cost, int kilometers, boolean fullTank, String note) {
        this.vehicle = vehicle;
        this.username = username;
        this.occurredAt = occurredAt;
        this.energyType = energyType;
        this.amount = amount;
        this.cost = cost;
        this.kilometers = kilometers;
        this.fullTank = fullTank;
        this.note = note;
    }

    public Long getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getEnergyType() { return energyType; }
    public void setEnergyType(String energyType) { this.energyType = energyType; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
    public int getKilometers() { return kilometers; }
    public void setKilometers(int kilometers) { this.kilometers = kilometers; }
    public boolean isFullTank() { return fullTank; }
    public void setFullTank(boolean fullTank) { this.fullTank = fullTank; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
