package vn.edu.hcmut.tmc.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceHistoryService;
import vn.edu.hcmut.tmc.attendance.presentation.controller.DevAttendanceHistoryController;

@WebMvcTest(DevAttendanceHistoryController.class)
@ActiveProfiles("test")
class AttendanceHistoryProfileWebTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    AttendanceHistoryService history;

    @Test
    void historyControllerAndBothRoutesAreAbsentWithoutDevProfile() throws Exception {
        assertThat(context.getBeansOfType(DevAttendanceHistoryController.class)).isEmpty();
        for (String path : new String[] {"/api/dev/attendance/history", "/api/v1/attendance/history"}) {
            mvc.perform(get(path).param("employeeId", "EMP001").param("factoryId", "BD")
                            .param("fromInclusive", "2026-11-14T01:00:00Z")
                            .param("toExclusive", "2026-11-14T01:01:00Z"))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(history);
    }
}
