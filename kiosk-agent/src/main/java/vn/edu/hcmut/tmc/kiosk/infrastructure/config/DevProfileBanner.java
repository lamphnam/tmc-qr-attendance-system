package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Makes the unauthenticated dev fixtures impossible to miss in the logs. */
@Component
@Profile("dev")
class DevProfileBanner {

    private static final Logger log = LoggerFactory.getLogger(DevProfileBanner.class);

    @EventListener(ApplicationReadyEvent.class)
    void warn() {
        log.warn("DEV PROFILE ACTIVE: unauthenticated /api/dev/kiosk/** fixtures are exposed. "
                + "They use a synthetic event with NO QR/TOTP check. Never run this profile outside a developer machine.");
    }
}
