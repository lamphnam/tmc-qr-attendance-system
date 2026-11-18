package vn.edu.hcmut.tmc.kiosk.infrastructure.security;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import vn.edu.hcmut.tmc.kiosk.domain.port.IHmacSha256;

@Component
public class JdkHmacSha256 implements IHmacSha256 {
    @Override
    public byte[] digest(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK HMAC-SHA256 is unavailable", e);
        }
    }
}
