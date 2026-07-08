package com.example.vehiclebackend.controller;

import com.example.vehiclebackend.entity.Vehicle;
import com.example.vehiclebackend.service.VehicleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

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
    record VehicleResponse(Long id, String kennzeichen, String make, String model, int year, String color, int kilometers, boolean inUse, String username, String createdBy, Instant inUseSince) {}

    private VehicleResponse toResponse(Vehicle v) {
        String createdBy = v.getUser() != null ? v.getUser().getUsername() : "";
        String username = v.getInUseBy() != null ? v.getInUseBy().getUsername() : createdBy;
        return new VehicleResponse(v.getId(), v.getKennzeichen(), v.getMake(), v.getModel(),
                v.getYear(), v.getColor(), v.getKilometers(), v.isInUse(), username, createdBy,
                v.getInUseSince());
    }

    @GetMapping
    public ResponseEntity<List<VehicleResponse>> getVehicles() {
        return ResponseEntity.ok(vehicleService.getVehicles().stream()
                .map(this::toResponse).toList());
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
        return ResponseEntity.ok(toResponse(vehicleService.addVehicle(vehicle)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable Long id) {
        vehicleService.deleteVehicle(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/kilometers")
    public ResponseEntity<VehicleResponse> updateKilometers(@PathVariable Long id,
                                                             @Valid @RequestBody KilometersRequest req) {
        return ResponseEntity.ok(toResponse(vehicleService.updateKilometers(id, req.kilometers())));
    }

    @PutMapping("/{id}/toggle-in-use")
    public ResponseEntity<VehicleResponse> toggleInUse(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(vehicleService.toggleInUse(id)));
    }
}
