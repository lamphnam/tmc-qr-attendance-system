package vn.edu.hcmut.tmc.kiosk.domain.port;

/** Computes a 32-byte HMAC-SHA256 digest without retaining the key or message.
 * Requires a non-empty key and throws IllegalStateException if the crypto provider fails.
 */
@FunctionalInterface
public interface IHmacSha256 {
    byte[] digest(byte[] key, byte[] message);
}
