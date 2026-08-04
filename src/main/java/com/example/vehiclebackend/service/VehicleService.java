package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.CostEntry;
import com.example.vehiclebackend.entity.Reservation;
import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.repository.BookingRecordRepository;
import com.example.vehiclebackend.repository.CostEntryRepository;
import com.example.vehiclebackend.repository.ReservationRepository;
import com.example.vehiclebackend.repository.UserRepository;
import com.example.vehiclebackend.repository.VehicleRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final BookingRecordRepository bookingRecordRepository;
    private final ReservationRepository reservationRepository;
    private final CostEntryRepository costEntryRepository;

    public VehicleService(VehicleRepository vehicleRepository, UserRepository userRepository,
                          BookingRecordRepository bookingRecordRepository,
                          ReservationRepository reservationRepository,
                          CostEntryRepository costEntryRepository) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.bookingRecordRepository = bookingRecordRepository;
        this.reservationRepository = reservationRepository;
        this.costEntryRepository = costEntryRepository;
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private Vehicle getVehicle(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
    }

    private static boolean isAdmin(User user) {
        return "ROLE_ADMIN".equals(user.getRole());
    }

    private static boolean isCreator(Vehicle vehicle, User user) {
        return vehicle.getUser() != null && vehicle.getUser().getId().equals(user.getId());
    }

    private static boolean isCurrentDriver(Vehicle vehicle, User user) {
        return vehicle.getInUseBy() != null && vehicle.getInUseBy().getId().equals(user.getId());
    }

    public List<Vehicle> getVehicles() {
        return vehicleRepository.findAllWithUsers();
    }

    /** The reservation in effect right now for each vehicle (vehicle id → reservation),
     *  for labelling the list "Reserviert von X". Overlaps are prevented, so there is
     *  at most one per vehicle. */
    public Map<Long, Reservation> activeReservationsByVehicle() {
        Map<Long, Reservation> map = new HashMap<>();
        for (Reservation r : reservationRepository.findAllActiveAt(Instant.now())) {
            map.putIfAbsent(r.getVehicle().getId(), r);
        }
        return map;
    }

    /** The reservation in effect right now for one vehicle, or null. */
    public Reservation activeReservation(Vehicle vehicle) {
        List<Reservation> active = reservationRepository.findActiveAt(vehicle, Instant.now());
        return active.isEmpty() ? null : active.get(0);
    }

    @Transactional
    public Vehicle addVehicle(Vehicle vehicle) {
        String plate = normalizePlate(vehicle.getKennzeichen());
        if (vehicleRepository.existsByKennzeichen(plate)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kennzeichen already exists");
        }
        vehicle.setKennzeichen(plate);
        vehicle.setUser(currentUser());
        try {
            return vehicleRepository.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKennzeichen(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Kennzeichen already exists");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid vehicle data");
        }
    }

    /** Plates are trimmed before they are compared or stored, so "M-AB 123 " and
     *  "M-AB 123" cannot coexist as two vehicles. Applied on create as well as on
     *  rename — normalising only one of the two would leave the gap open. */
    private static String normalizePlate(String kennzeichen) {
        return kennzeichen == null ? null : kennzeichen.trim();
    }

    @Transactional
    public void deleteVehicle(Long id) {
        Vehicle vehicle = getVehicle(id);
        User user = currentUser();
        if (!isCreator(vehicle, user) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this vehicle");
        }
        // booking_records, reservations and cost_entries all FK the vehicle — clear first.
        bookingRecordRepository.deleteByVehicle(vehicle);
        reservationRepository.deleteByVehicle(vehicle);
        costEntryRepository.deleteByVehicle(vehicle);
        vehicleRepository.delete(vehicle);
    }

    /** Edit a vehicle's master data (plate, make, model, year, colour, kilometers).
     *  Same ownership rule as delete: only the creator or an admin — an arbitrary
     *  driver may correct the odometer, but not rewrite the vehicle's identity.
     *  A null `kilometers` leaves the odometer untouched. */
    @Transactional
    public Vehicle updateVehicle(Long id, String kennzeichen, String make, String model,
                                 int year, String color, Integer kilometers) {
        // Pessimistic row lock (same as toggleInUse). The entity has no @Version and
        // no @DynamicUpdate, so Hibernate writes *every* column from the snapshot read
        // here — including in_use / in_use_by_id / in_use_since. Without the lock a
        // check-out committing between the read and the write would be silently
        // reverted, leaving the vehicle "free" while someone is driving it.
        Vehicle vehicle = vehicleRepository.findWithLockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
        User user = currentUser();
        if (!isCreator(vehicle, user) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this vehicle");
        }
        String plate = normalizePlate(kennzeichen);
        if (vehicleRepository.existsByKennzeichenAndIdNot(plate, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kennzeichen already exists");
        }
        vehicle.setKennzeichen(plate);
        vehicle.setMake(make);
        vehicle.setModel(model);
        vehicle.setYear(year);
        vehicle.setColor(color);
        if (kilometers != null) {
            assertNotBelowOpenTrip(vehicle, kilometers);
            vehicle.setKilometers(kilometers);
        }
        try {
            // saveAndFlush, not save: this method is transactional, so a plain save()
            // would defer the UPDATE to commit — outside this try — and any violation
            // would surface as a 500 instead of being mapped below.
            return vehicleRepository.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKennzeichen(e)) {
                // Lost a race: another transaction took the plate after the check above.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Kennzeichen already exists");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid vehicle data");
        }
    }

    /** Tells a taken plate apart from any other integrity failure (e.g. an over-long
     *  make/model overflowing its varchar), which must not be reported as a plate
     *  conflict. Hibernate generates the constraint name under `ddl-auto=update`, so
     *  it isn't stable enough to match on — go by the driver's message instead
     *  (Postgres names the column, MySQL says "Duplicate entry"). */
    private static boolean isDuplicateKennzeichen(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        String message = e.getMostSpecificCause().getMessage();
        return message != null
                && (message.toLowerCase().contains("kennzeichen") || message.contains("Duplicate entry"));
    }

    /** The odometer may be corrected downwards (deliberate — it allows fixing typos),
     *  but never below the reading the currently open trip started at: check-in stores
     *  the vehicle's value as kmAtCheckin, so a lower number produces a negative trip
     *  distance, which the dashboard then divides fuel costs by. */
    private void assertNotBelowOpenTrip(Vehicle vehicle, int kilometers) {
        bookingRecordRepository
                .findFirstByVehicleAndCheckedInAtIsNullOrderByCheckedOutAtDesc(vehicle)
                .ifPresent(open -> {
                    if (kilometers < open.getKmAtCheckout()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Kilometers cannot be lower than " + open.getKmAtCheckout()
                                        + ", the reading when the current trip started");
                    }
                });
    }

    public List<BookingRecord> getHistory(Long id) {
        Vehicle vehicle = getVehicle(id);
        return bookingRecordRepository.findByVehicleOrderByCheckedOutAtDesc(vehicle);
    }

    /** Fleet-wide booking history for the analytics dashboard, one page at a time.
     *  Readable by any authenticated user (route is not admin-guarded). */
    public Page<BookingRecord> getAllHistory(Pageable pageable) {
        return bookingRecordRepository.findAllWithVehicle(pageable);
    }

    /** Admin-only: wipe a vehicle's booking history (route-guarded in SecurityConfig). */
    @Transactional
    public void clearHistory(Long id) {
        Vehicle vehicle = getVehicle(id);
        bookingRecordRepository.deleteByVehicle(vehicle);
    }

    /** Admin-only: delete a single booking record. The record must belong to the
     *  vehicle in the path, otherwise it's treated as not found. */
    @Transactional
    public void deleteHistoryEntry(Long vehicleId, Long recordId) {
        BookingRecord record = bookingRecordRepository.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking record not found"));
        if (!record.getVehicle().getId().equals(vehicleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking record not found for this vehicle");
        }
        bookingRecordRepository.delete(record);
    }

    /** Correct the odometer without touching the rest of the master data. Locked and
     *  transactional for the same reasons as updateVehicle: the save writes every
     *  column from the row read here, and the open-trip guard below is a check-then-act
     *  that a concurrent check-out would otherwise slip past. */
    @Transactional
    public Vehicle updateKilometers(Long id, int kilometers) {
        Vehicle vehicle = vehicleRepository.findWithLockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
        User user = currentUser();
        if (!isCreator(vehicle, user) && !isCurrentDriver(vehicle, user) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the vehicle's creator, its current driver, or an admin may update kilometers");
        }
        assertNotBelowOpenTrip(vehicle, kilometers);
        vehicle.setKilometers(kilometers);
        return vehicleRepository.save(vehicle);
    }

    @Transactional
    public Vehicle toggleInUse(Long id) {
        return toggleInUse(id, null, null, null);
    }

    /** Toggle check-out/check-in. On check-out, the optional Fahrtenbuch details
     *  (Zweck/Ziel/geschäftlich) are recorded on the new booking; on check-in they
     *  are ignored (the trip already carries them). */
    @Transactional
    public Vehicle toggleInUse(Long id, String purpose, String destination, Boolean business) {
        // Pessimistic row lock: concurrent toggles on the same vehicle serialize,
        // so two users cannot both check out a free vehicle.
        Vehicle vehicle = vehicleRepository.findWithLockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
        User user = currentUser();
        if (vehicle.isInUse() && vehicle.getInUseBy() != null && !isCurrentDriver(vehicle, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Vehicle is currently in use by " + vehicle.getInUseBy().getUsername());
        }
        Instant now = Instant.now();
        if (vehicle.isInUse()) {
            vehicle.setInUse(false);
            vehicle.setInUseBy(null);
            vehicle.setInUseSince(null);
            // Close the open booking record with the return time + odometer.
            bookingRecordRepository
                    .findFirstByVehicleAndCheckedInAtIsNullOrderByCheckedOutAtDesc(vehicle)
                    .ifPresent(booking -> {
                        booking.setCheckedInAt(now);
                        booking.setKmAtCheckin(vehicle.getKilometers());
                        bookingRecordRepository.save(booking);
                    });
        } else {
            // An active reservation held by someone else blocks the check-out.
            for (Reservation r : reservationRepository.findActiveAt(vehicle, now)) {
                if (!r.getUsername().equals(user.getUsername())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vehicle is reserved by " + r.getUsername() + " until " + r.getEndTime());
                }
            }
            vehicle.setInUse(true);
            vehicle.setInUseBy(user);
            vehicle.setInUseSince(now);
            BookingRecord booking =
                    new BookingRecord(vehicle, user.getUsername(), now, vehicle.getKilometers());
            booking.setPurpose(blankToNull(purpose));
            booking.setDestination(blankToNull(destination));
            booking.setBusiness(business);
            bookingRecordRepository.save(booking);
        }
        return vehicleRepository.save(vehicle);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Upcoming/ongoing reservations for one vehicle, soonest first. */
    public List<Reservation> getReservations(Long id) {
        Vehicle vehicle = getVehicle(id);
        return reservationRepository.findByVehicleOrderByStartTimeAsc(vehicle);
    }

    /** Fleet-wide reservations whose window hasn't ended yet, soonest first.
     *  Readable by any authenticated user (route is not admin-guarded). */
    public List<Reservation> getUpcomingReservations() {
        return reservationRepository.findUpcomingWithVehicle(Instant.now());
    }

    /** Reserve a vehicle for [start, end). Rejects a window that ends in the past,
     *  is non-positive, or overlaps an existing reservation for the same vehicle. */
    @Transactional
    public Reservation createReservation(Long vehicleId, Instant start, Instant end, String purpose) {
        // Pessimistic row lock (same as toggleInUse) so concurrent reservation
        // attempts on one vehicle serialize and can't both pass the overlap check.
        Vehicle vehicle = vehicleRepository.findWithLockById(vehicleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
        User user = currentUser();
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time");
        }
        if (!end.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reservation must end in the future");
        }
        List<Reservation> clashes = reservationRepository.findOverlapping(vehicle, start, end);
        if (!clashes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vehicle already reserved for that time by " + clashes.get(0).getUsername());
        }
        String trimmed = purpose == null || purpose.isBlank() ? null : purpose.trim();
        return reservationRepository.save(new Reservation(vehicle, user.getUsername(), start, end, trimmed));
    }

    /** Cancel a reservation. Only the person who made it or an admin may cancel;
     *  the reservation must belong to the vehicle in the path. */
    @Transactional
    public void cancelReservation(Long vehicleId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
        if (!reservation.getVehicle().getId().equals(vehicleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found for this vehicle");
        }
        User user = currentUser();
        if (!reservation.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who made the reservation or an admin may cancel it");
        }
        reservationRepository.delete(reservation);
    }

    /** Fuel/charge cost entries for one vehicle, newest first. */
    public List<CostEntry> getCosts(Long vehicleId) {
        Vehicle vehicle = getVehicle(vehicleId);
        return costEntryRepository.findByVehicleOrderByOccurredAtDesc(vehicle);
    }

    /** Fleet-wide cost entries for the analytics dashboard, newest first. */
    public List<CostEntry> getAllCosts() {
        return costEntryRepository.findAllWithVehicle();
    }

    /** Record a refuel/charge. energyType must be FUEL or ELECTRIC; amount/cost/km
     *  must be non-negative. */
    @Transactional
    public CostEntry addCost(Long vehicleId, Instant occurredAt, String energyType,
                             double amount, double cost, int kilometers, boolean fullTank, String note) {
        Vehicle vehicle = getVehicle(vehicleId);
        User user = currentUser();
        if (!"FUEL".equals(energyType) && !"ELECTRIC".equals(energyType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "energyType must be FUEL or ELECTRIC");
        }
        if (amount < 0 || cost < 0 || kilometers < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount, cost and kilometers must be >= 0");
        }
        Instant when = occurredAt != null ? occurredAt : Instant.now();
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        return costEntryRepository.save(new CostEntry(vehicle, user.getUsername(), when,
                energyType, amount, cost, kilometers, fullTank, trimmed));
    }

    /** Delete a cost entry. Only the person who recorded it or an admin may; the
     *  entry must belong to the vehicle in the path. */
    @Transactional
    public void deleteCost(Long vehicleId, Long costId) {
        CostEntry entry = costEntryRepository.findById(costId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cost entry not found"));
        if (!entry.getVehicle().getId().equals(vehicleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cost entry not found for this vehicle");
        }
        User user = currentUser();
        if (!entry.getUsername().equals(user.getUsername()) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who recorded the entry or an admin may delete it");
        }
        costEntryRepository.delete(entry);
    }
}
