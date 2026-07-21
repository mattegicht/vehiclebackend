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

    public Vehicle addVehicle(Vehicle vehicle) {
        vehicle.setUser(currentUser());
        return vehicleRepository.save(vehicle);
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
     *  driver may correct the odometer, but not rewrite the vehicle's identity. */
    public Vehicle updateVehicle(Long id, String kennzeichen, String make, String model,
                                 int year, String color, int kilometers) {
        Vehicle vehicle = getVehicle(id);
        User user = currentUser();
        if (!isCreator(vehicle, user) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this vehicle");
        }
        vehicle.setKennzeichen(kennzeichen);
        vehicle.setMake(make);
        vehicle.setModel(model);
        vehicle.setYear(year);
        vehicle.setColor(color);
        vehicle.setKilometers(kilometers);
        try {
            return vehicleRepository.save(vehicle);
        } catch (DataIntegrityViolationException e) {
            // `kennzeichen` is unique — renaming onto another vehicle's plate.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kennzeichen already exists");
        }
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

    public Vehicle updateKilometers(Long id, int kilometers) {
        Vehicle vehicle = getVehicle(id);
        User user = currentUser();
        if (!isCreator(vehicle, user) && !isCurrentDriver(vehicle, user) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the vehicle's creator, its current driver, or an admin may update kilometers");
        }
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
