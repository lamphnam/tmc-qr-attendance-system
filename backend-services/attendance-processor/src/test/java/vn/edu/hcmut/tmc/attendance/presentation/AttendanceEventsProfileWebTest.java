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

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceEventsService;
import vn.edu.hcmut.tmc.attendance.presentation.controller.DevAttendanceEventsController;

@WebMvcTest(DevAttendanceEventsController.class)
@ActiveProfiles("test")
class AttendanceEventsProfileWebTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @MockitoBean AttendanceEventsService events;

    @Test
    void demoGetRouteIsAbsentOutsideDev() throws Exception {
        assertThat(context.getBeansOfType(DevAttendanceEventsController.class)).isEmpty();
        mvc.perform(get("/api/v1/attendance/events").param("limit", "20")).andExpect(status().isNotFound());
        verifyNoInteractions(events);
    }
}
