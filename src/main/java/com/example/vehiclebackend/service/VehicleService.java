package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.BookingRecord;
import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.repository.BookingRecordRepository;
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

    public VehicleService(VehicleRepository vehicleRepository, UserRepository userRepository,
                          BookingRecordRepository bookingRecordRepository) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
        this.bookingRecordRepository = bookingRecordRepository;
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
        // booking_records has an FK to the vehicle — clear the history first.
        bookingRecordRepository.deleteByVehicle(vehicle);
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
            vehicle.setInUse(true);
            vehicle.setInUseBy(user);
            vehicle.setInUseSince(now);
            bookingRecordRepository.save(
                    new BookingRecord(vehicle, user.getUsername(), now, vehicle.getKilometers()));
        }
        return vehicleRepository.save(vehicle);
    }
}
