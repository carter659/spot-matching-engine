package com.exchange.matching.server.service;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.Base64;

public final class PasswordHash {
    private PasswordHash() {}
    public static String encode(String password) {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(password, salt));
    }
    public static boolean matches(String password, String encoded) {
        if (password == null || password.length() > 128) return false;
        String[] parts = encoded.split(":");
        return MessageDigest.isEqual(Base64.getDecoder().decode(parts[1]), derive(password, Base64.getDecoder().decode(parts[0])));
    }
    private static byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
}
