package vn.edu.hcmut.tmc.kiosk.presentation;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.DevWhitelistSyncController;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.KioskScanController;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.UnimplementedScanController;

@WebMvcTest(controllers = {DevWhitelistSyncController.class, KioskScanController.class, UnimplementedScanController.class},
        properties = "kiosk.whitelist.sync-enabled=true")
@ActiveProfiles("test")
class WhitelistSyncProfileWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean SyncWhitelistUseCase sync;

    @Test
    void manualTriggerIs404OutsideDev() throws Exception {
        mvc.perform(post("/api/dev/kiosk/whitelist/sync")).andExpect(status().isNotFound());
        verifyNoInteractions(sync);
    }

    @Test
    void scanReturnsTheProductionStubOutsideDev() throws Exception {
        mvc.perform(post("/api/local/scan")).andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
    }
}
