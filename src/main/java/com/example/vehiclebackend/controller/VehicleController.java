package com.example.vehiclebackend.controller;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.CostEntry;
import com.example.vehiclebackend.entity.Reservation;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.service.VehicleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    // `kilometers` is a boxed Integer on purpose: as a primitive an absent JSON field
    // deserializes to 0 and passes @Min(0), which on PUT would wipe the odometer the
    // whole analytics dashboard is computed from. Null means "not supplied" — POST
    // starts at 0, PUT leaves the stored value alone.
    record VehicleRequest(
            @NotBlank String kennzeichen,
            @NotBlank String make,
            @NotBlank String model,
            @Min(1886) int year,
            @NotBlank String color,
            @Min(0) Integer kilometers) {}
    // Boxed + @NotNull for the same reason as VehicleRequest above: as a primitive an
    // absent field would deserialize to 0 and wipe the odometer. Here the reading *is*
    // the whole payload, so omitting it is always a client bug — reject it with a 400
    // rather than guessing.
    record KilometersRequest(@NotNull @Min(0) Integer kilometers) {}
    // Optional Fahrtenbuch details sent with a check-out (toggle-in-use). All fields
    // optional; ignored when the toggle is a check-in.
    record TripDetailsRequest(@Size(max = 500) String purpose,
                              @Size(max = 500) String destination,
                              Boolean business) {}
    record VehicleResponse(Long id, String kennzeichen, String make, String model, int year, String color, int kilometers, boolean inUse, String username, String createdBy, Instant inUseSince, String reservedBy, Instant reservedUntil) {}
    record BookingResponse(Long id, String username, Instant checkedOutAt, Instant checkedInAt, int kmAtCheckout, Integer kmAtCheckin, String purpose, String destination, Boolean business) {}
    record FleetBookingResponse(Long id, Long vehicleId, String kennzeichen, String username, Instant checkedOutAt, Instant checkedInAt, int kmAtCheckout, Integer kmAtCheckin, String purpose, String destination, Boolean business) {}
    record PagedBookingsResponse(List<FleetBookingResponse> content, int page, int size,
                                 long totalElements, int totalPages, boolean last) {}
    record ReservationRequest(@NotNull Instant start, @NotNull Instant end,
                              @Size(max = 500) String purpose) {}
    record ReservationResponse(Long id, Long vehicleId, String kennzeichen, String username,
                               Instant startTime, Instant endTime, String purpose) {}
    // amount/cost/kilometers are boxed + @NotNull so an omitted field is a 400 instead
    // of a silent 0 — a 0/0/0 entry would quietly skew the cost-per-km analytics.
    // `fullTank` stays primitive: false is a genuine default for an optional flag.
    record CostRequest(@NotNull Instant occurredAt, @NotBlank String energyType,
                       @NotNull @PositiveOrZero Double amount, @NotNull @PositiveOrZero Double cost,
                       @NotNull @Min(0) Integer kilometers, boolean fullTank,
                       @Size(max = 500) String note) {}
    record CostResponse(Long id, Long vehicleId, String kennzeichen, String username, Instant occurredAt,
                        String energyType, double amount, double cost, int kilometers, boolean fullTank,
                        String note) {}

    private VehicleResponse toResponse(Vehicle v, Reservation activeRes) {
        String createdBy = v.getUser() != null ? v.getUser().getUsername() : "";
        String username = v.getInUseBy() != null ? v.getInUseBy().getUsername() : createdBy;
        return new VehicleResponse(v.getId(), v.getKennzeichen(), v.getMake(), v.getModel(),
                v.getYear(), v.getColor(), v.getKilometers(), v.isInUse(), username, createdBy,
                v.getInUseSince(),
                activeRes != null ? activeRes.getUsername() : null,
                activeRes != null ? activeRes.getEndTime() : null);
    }

    @GetMapping
    public ResponseEntity<List<VehicleResponse>> getVehicles() {
        Map<Long, Reservation> active = vehicleService.activeReservationsByVehicle();
        return ResponseEntity.ok(vehicleService.getVehicles().stream()
                .map(v -> toResponse(v, active.get(v.getId()))).toList());
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> addVehicle(@Valid @RequestBody VehicleRequest req) {
        Vehicle vehicle = new Vehicle();
        vehicle.setKennzeichen(req.kennzeichen());
        vehicle.setMake(req.make());
        vehicle.setModel(req.model());
        vehicle.setYear(req.year());
        vehicle.setColor(req.color());
        vehicle.setKilometers(req.kilometers() != null ? req.kilometers() : 0);
        // A brand-new vehicle can't have a reservation yet.
        return ResponseEntity.ok(toResponse(vehicleService.addVehicle(vehicle), null));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VehicleResponse> updateVehicle(@PathVariable Long id,
                                                         @Valid @RequestBody VehicleRequest req) {
        Vehicle v = vehicleService.updateVehicle(id, req.kennzeichen(), req.make(), req.model(),
                req.year(), req.color(), req.kilometers());
        return ResponseEntity.ok(toResponse(v, vehicleService.activeReservation(v)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        vehicleService.deleteVehicle(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/kilometers")
    public ResponseEntity<VehicleResponse> updateKilometers(@PathVariable Long id,
                                                             @Valid @RequestBody KilometersRequest req) {
        Vehicle v = vehicleService.updateKilometers(id, req.kilometers());
        return ResponseEntity.ok(toResponse(v, vehicleService.activeReservation(v)));
    }

    @PutMapping("/{id}/toggle-in-use")
    public ResponseEntity<VehicleResponse> toggleInUse(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TripDetailsRequest req) {
        Vehicle v = req == null
                ? vehicleService.toggleInUse(id)
                : vehicleService.toggleInUse(id, req.purpose(), req.destination(), req.business());
        return ResponseEntity.ok(toResponse(v, vehicleService.activeReservation(v)));
    }

    // Fleet-wide booking history for the analytics dashboard (any authenticated user).
    // Paginated so the payload doesn't grow with the whole fleet's history; the
    // client pages through with ?page=&size= (size clamped to 1..MAX_BOOKINGS_PAGE_SIZE).
    private static final int DEFAULT_BOOKINGS_PAGE_SIZE = 100;
    private static final int MAX_BOOKINGS_PAGE_SIZE = 500;

    @GetMapping("/bookings")
    public ResponseEntity<PagedBookingsResponse> getAllBookings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_BOOKINGS_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_BOOKINGS_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "checkedOutAt"));
        Page<BookingRecord> result = vehicleService.getAllHistory(pageable);
        List<FleetBookingResponse> content = result.getContent().stream()
                .map(b -> new FleetBookingResponse(b.getId(), b.getVehicle().getId(),
                        b.getVehicle().getKennzeichen(), b.getUsername(), b.getCheckedOutAt(),
                        b.getCheckedInAt(), b.getKmAtCheckout(), b.getKmAtCheckin(),
                        b.getPurpose(), b.getDestination(), b.getBusiness()))
                .toList();
        return ResponseEntity.ok(new PagedBookingsResponse(content, result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isLast()));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<BookingResponse>> getHistory(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getHistory(id).stream()
                .map(b -> new BookingResponse(b.getId(), b.getUsername(), b.getCheckedOutAt(),
                        b.getCheckedInAt(), b.getKmAtCheckout(), b.getKmAtCheckin(),
                        b.getPurpose(), b.getDestination(), b.getBusiness()))
                .toList());
    }

    // Admin-only (enforced in SecurityConfig): clears the vehicle's booking history.
    @DeleteMapping("/{id}/history")
    public ResponseEntity<Void> clearHistory(@PathVariable Long id) {
        vehicleService.clearHistory(id);
        return ResponseEntity.noContent().build();
    }

    // Admin-only (enforced in SecurityConfig): deletes a single history entry.
    @DeleteMapping("/{id}/history/{recordId}")
    public ResponseEntity<Void> deleteHistoryEntry(@PathVariable Long id, @PathVariable Long recordId) {
        vehicleService.deleteHistoryEntry(id, recordId);
        return ResponseEntity.noContent().build();
    }

    private ReservationResponse toReservationResponse(Reservation r) {
        return new ReservationResponse(r.getId(), r.getVehicle().getId(),
                r.getVehicle().getKennzeichen(), r.getUsername(),
                r.getStartTime(), r.getEndTime(), r.getPurpose());
    }

    // Reserve a vehicle for a future window (any authenticated user).
    @PostMapping("/{id}/reservations")
    public ResponseEntity<ReservationResponse> createReservation(
            @PathVariable Long id, @Valid @RequestBody ReservationRequest req) {
        return ResponseEntity.ok(toReservationResponse(
                vehicleService.createReservation(id, req.start(), req.end(), req.purpose())));
    }

    @GetMapping("/{id}/reservations")
    public ResponseEntity<List<ReservationResponse>> getReservations(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getReservations(id).stream()
                .map(this::toReservationResponse).toList());
    }

    // Fleet-wide upcoming reservations for the calendar/overview (any authenticated user).
    @GetMapping("/reservations")
    public ResponseEntity<List<ReservationResponse>> getUpcomingReservations() {
        return ResponseEntity.ok(vehicleService.getUpcomingReservations().stream()
                .map(this::toReservationResponse).toList());
    }

    // Cancel a reservation (reserver or admin, enforced in the service).
    @DeleteMapping("/{id}/reservations/{reservationId}")
    public ResponseEntity<Void> cancelReservation(@PathVariable Long id, @PathVariable Long reservationId) {
        vehicleService.cancelReservation(id, reservationId);
        return ResponseEntity.noContent().build();
    }

    private CostResponse toCostResponse(CostEntry c) {
        return new CostResponse(c.getId(), c.getVehicle().getId(), c.getVehicle().getKennzeichen(),
                c.getUsername(), c.getOccurredAt(), c.getEnergyType(), c.getAmount(), c.getCost(),
                c.getKilometers(), c.isFullTank(), c.getNote());
    }

    // Record a refuel/charge for a vehicle (any authenticated user).
    @PostMapping("/{id}/costs")
    public ResponseEntity<CostResponse> addCost(@PathVariable Long id,
                                                @Valid @RequestBody CostRequest req) {
        return ResponseEntity.ok(toCostResponse(vehicleService.addCost(id, req.occurredAt(),
                req.energyType(), req.amount(), req.cost(), req.kilometers(), req.fullTank(), req.note())));
    }

    @GetMapping("/{id}/costs")
    public ResponseEntity<List<CostResponse>> getCosts(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getCosts(id).stream()
                .map(this::toCostResponse).toList());
    }

    // Fleet-wide cost entries for the dashboard cost-per-km tile (any authenticated user).
    @GetMapping("/costs")
    public ResponseEntity<List<CostResponse>> getAllCosts() {
        return ResponseEntity.ok(vehicleService.getAllCosts().stream()
                .map(this::toCostResponse).toList());
    }

    // Delete a cost entry (recorder or admin, enforced in the service).
    @DeleteMapping("/{id}/costs/{costId}")
    public ResponseEntity<Void> deleteCost(@PathVariable Long id, @PathVariable Long costId) {
        vehicleService.deleteCost(id, costId);
        return ResponseEntity.noContent().build();
    }
}
