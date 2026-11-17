package vn.edu.hcmut.tmc.kiosk.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport.Status;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.DevWhitelistSyncController;

@WebMvcTest(controllers = DevWhitelistSyncController.class, properties = "kiosk.whitelist.sync-enabled=true")
@ActiveProfiles("dev")
class DevWhitelistSyncControllerWebTest {

    @Autowired MockMvc mvc;
    @MockitoBean SyncWhitelistUseCase sync;

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"UPDATED", "NOT_NEWER"})
    void onlyDurableOrUnchangedResultsAreFrontendSuccess(Status result) throws Exception {
        when(sync.run()).thenReturn(WhitelistSyncReport.forSnapshot(result, 7));
        for (int count = 0; count < 2; count++) {
            mvc.perform(post("/api/dev/kiosk/whitelist/sync"))
                    .andExpect(status().isOk()).andExpect(header().string("X-TMC-Dev-Only", "true"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value(result.name()))
                    .andExpect(jsonPath("$.snapshotVersion").value(7))
                    .andExpect(jsonPath("$.employees").doesNotExist());
        }
        verify(sync, times(2)).run();
    }

    @ParameterizedTest
    @CsvSource({"OFFLINE,409,CENTRAL_SYNC_DISABLED", "ALREADY_RUNNING,409,WHITELIST_SYNC_IN_PROGRESS",
            "SOURCE_UNAVAILABLE,503,WHITELIST_SOURCE_UNAVAILABLE", "INVALID_SNAPSHOT,502,INVALID_WHITELIST_SNAPSHOT",
            "CACHE_UNAVAILABLE,503,WHITELIST_CACHE_UNAVAILABLE", "INVALID_CACHE,503,WHITELIST_CACHE_INVALID"})
    void failuresCannotTriggerTheFrontendSuccessMessage(Status result, int httpStatus, String code) throws Exception {
        when(sync.run()).thenReturn(WhitelistSyncReport.withoutSnapshot(result));
        mvc.perform(post("/api/dev/kiosk/whitelist/sync").header("X-Request-Id", "manual-sync"))
                .andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.correlationId").value("manual-sync"))
                .andExpect(jsonPath("$.snapshotVersion").doesNotExist());
        verify(sync).run();
    }

    @Test
    void unexpectedFailureNeverExposesSensitiveExceptionDiagnostics() throws Exception {
        when(sync.run()).thenThrow(new IllegalStateException("synthetic-api-key sharedSecret ciphertext"));
        String body = mvc.perform(post("/api/dev/kiosk/whitelist/sync"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("WHITELIST_SYNC_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("synthetic-api-key", "sharedSecret", "ciphertext", "IllegalStateException");
    }
}
