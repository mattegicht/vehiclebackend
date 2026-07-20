package com.example.vehiclebackend.controller;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.Reservation;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.service.VehicleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    record VehicleRequest(
            @NotBlank String kennzeichen,
            @NotBlank String make,
            @NotBlank String model,
            @Min(1886) int year,
            @NotBlank String color,
            @Min(0) int kilometers) {}
    record KilometersRequest(@Min(0) int kilometers) {}
    record VehicleResponse(Long id, String kennzeichen, String make, String model, int year, String color, int kilometers, boolean inUse, String username, String createdBy, Instant inUseSince, String reservedBy, Instant reservedUntil) {}
    record BookingResponse(Long id, String username, Instant checkedOutAt, Instant checkedInAt, int kmAtCheckout, Integer kmAtCheckin) {}
    record FleetBookingResponse(Long id, Long vehicleId, String kennzeichen, String username, Instant checkedOutAt, Instant checkedInAt, int kmAtCheckout, Integer kmAtCheckin) {}
    record PagedBookingsResponse(List<FleetBookingResponse> content, int page, int size,
                                 long totalElements, int totalPages, boolean last) {}
    record ReservationRequest(@NotNull Instant start, @NotNull Instant end,
                              @Size(max = 500) String purpose) {}
    record ReservationResponse(Long id, Long vehicleId, String kennzeichen, String username,
                               Instant startTime, Instant endTime, String purpose) {}

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
        vehicle.setKilometers(req.kilometers());
        // A brand-new vehicle can't have a reservation yet.
        return ResponseEntity.ok(toResponse(vehicleService.addVehicle(vehicle), null));
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
    public ResponseEntity<VehicleResponse> toggleInUse(@PathVariable Long id) {
        Vehicle v = vehicleService.toggleInUse(id);
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
                        b.getCheckedInAt(), b.getKmAtCheckout(), b.getKmAtCheckin()))
                .toList();
        return ResponseEntity.ok(new PagedBookingsResponse(content, result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isLast()));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<BookingResponse>> getHistory(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getHistory(id).stream()
                .map(b -> new BookingResponse(b.getId(), b.getUsername(), b.getCheckedOutAt(),
                        b.getCheckedInAt(), b.getKmAtCheckout(), b.getKmAtCheckin()))
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
}
