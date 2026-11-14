package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpException;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpTransport;

class WhitelistHttpConfigTest {

    private ApplicationContextRunner context(RestClient client) {
        return new ApplicationContextRunner().withUserConfiguration(WhitelistHttpConfig.class)
                .withBean("centralRestClient", RestClient.class, () -> client)
                .withBean(KioskIdentity.class, () -> new KioskIdentity("BD-K01", "BD", "BD-G01"))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void wiresExplicitTransportCredentialWithoutCallingCentralOrCreatingSnapshotIntegration() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://central.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        context(builder.build()).withPropertyValues("kiosk.whitelist.api-key=synthetic-config-credential",
                "kiosk.whitelist.secret-key-base64=not-an-http-credential").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(WhitelistHttpTransport.class)
                    .doesNotHaveBean(IWhitelistSnapshotSource.class).doesNotHaveBean(IWhitelistSnapshotStore.class);
            server.verify();
            server.expect(requestTo("http://central.invalid/api/v1/kiosk/whitelist?factoryId=BD"))
                    .andExpect(header("X-Kiosk-Api-Key", "synthetic-config-credential"))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            assertThat(application.getBean(WhitelistHttpTransport.class).fetchEmployees()).isEmpty();
            server.verify();
        });
    }

    @Test
    void absentHttpCredentialAllowsStartupButCannotUseTheSqliteEncryptionKeyAsFallback() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://central.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        context(builder.build()).withPropertyValues("kiosk.whitelist.api-key=",
                "kiosk.whitelist.secret-key-base64=synthetic-encryption-config").run(application -> {
            assertThat(application).hasNotFailed();
            assertThatThrownBy(() -> application.getBean(WhitelistHttpTransport.class).fetchEmployees())
                    .isInstanceOf(WhitelistHttpException.class).hasMessage("whitelist HTTP credential is not configured")
                    .hasNoCause();
            server.verify();
        });
    }
}
