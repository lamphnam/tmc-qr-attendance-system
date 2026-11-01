package vn.edu.hcmut.tmc.kiosk.domain.model;

/** Config-driven identity of this kiosk (NFR-5: no factory or gate is hard-coded). */
public record KioskIdentity(String kioskId, String factoryId, String defaultGateId) {
}
