package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.Reservation;
import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.repository.BookingRecordRepository;
import com.example.vehiclebackend.repository.ReservationRepository;
import com.example.vehiclebackend.repository.UserRepository;
import com.example.vehiclebackend.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;
    private final BookingRecordRepository bookingRecordRepository;
    private final ReservationRepository reservationRepository;

    public VehicleService(VehicleRepository vehicleRepository, UserRepository userRepository,
                          BookingRecordRepository bookingRecordRepository,
                          ReservationRepository reservationRepository) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.bookingRecordRepository = bookingRecordRepository;
        this.reservationRepository = reservationRepository;
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
        // booking_records and reservations both FK the vehicle — clear them first.
        bookingRecordRepository.deleteByVehicle(vehicle);
        reservationRepository.deleteByVehicle(vehicle);
        vehicleRepository.delete(vehicle);
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
            bookingRecordRepository.save(
                    new BookingRecord(vehicle, user.getUsername(), now, vehicle.getKilometers()));
        }
        return vehicleRepository.save(vehicle);
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
}
