package vn.edu.hcmut.tmc.kiosk.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.DevWhitelistSyncController;

class DevWhitelistSyncAvailabilityTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DevWhitelistSyncController.class);

    @Test
    void missingFlagLeavesDevEndpointPresentButDisabledEvenIfUseCaseExists() {
        SyncWhitelistUseCase sync = mock(SyncWhitelistUseCase.class);
        context.withPropertyValues("spring.profiles.active=dev").withBean(SyncWhitelistUseCase.class, () -> sync)
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    MockMvcBuilders.standaloneSetup(app.getBean(DevWhitelistSyncController.class)).build()
                            .perform(post("/api/dev/kiosk/whitelist/sync"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("WHITELIST_SYNC_DISABLED"));
                    verifyNoInteractions(sync);
                });
    }

    @Test
    void enabledWithoutUseCaseStartsAndAnswers503InsteadOfFalseSuccess() {
        context.withPropertyValues("spring.profiles.active=dev", "kiosk.whitelist.sync-enabled=true").run(app -> {
            assertThat(app).hasNotFailed();
            MockMvcBuilders.standaloneSetup(app.getBean(DevWhitelistSyncController.class)).build()
                    .perform(post("/api/dev/kiosk/whitelist/sync"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("WHITELIST_SYNC_UNAVAILABLE"));
        });
    }

    @Test
    void nonDevProfileDoesNotRegisterTheControllerEvenWithFlagTrue() {
        context.withPropertyValues("spring.profiles.active=test", "kiosk.whitelist.sync-enabled=true")
                .run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(DevWhitelistSyncController.class));
    }
}
