package com.example.vehiclebackend.service;

import com.example.vehiclebackend.entity.User;
import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.repository.UserRepository;
import com.example.vehiclebackend.repository.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final UserRepository userRepository;

    public VehicleService(VehicleRepository vehicleRepository, UserRepository userRepository) {
        this.vehicleRepository = vehicleRepository;
        this.userRepository = userRepository;
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

    public void deleteVehicle(Long id) {
        Vehicle vehicle = getVehicle(id);
        User user = currentUser();
        if (!isCreator(vehicle, user) && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not own this vehicle");
        }
        vehicleRepository.delete(vehicle);
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
        if (vehicle.isInUse()) {
            vehicle.setInUse(false);
            vehicle.setInUseBy(null);
        } else {
            vehicle.setInUse(true);
            vehicle.setInUseBy(user);
        }
        return vehicleRepository.save(vehicle);
    }
}
