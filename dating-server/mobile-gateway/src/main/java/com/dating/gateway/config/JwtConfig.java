package com.dating.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** JWT Configuration. */
@Configuration
public class JwtConfig {

    @Bean
    @ConfigurationProperties(prefix = "jwt")
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }

    @Bean
    public KeyPair jwtKeyPair(JwtProperties properties) {
        if (properties.getPrivateKeyBase64() != null && !properties.getPrivateKeyBase64().isEmpty()) {
            try {
                byte[] privateKeyBytes = Base64.getDecoder().decode(properties.getPrivateKeyBase64());
                PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
                return new KeyPair(privateKey.getPublic(), privateKey);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load JWT key pair", e);
            }
        }
        return generateKeyPair();
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    public static class JwtProperties {
        private String privateKeyBase64;
        private String publicKeyBase64;
        private int accessTokenExpirySeconds = 900;
        private int refreshTokenExpiryDays = 7;
        private String issuer = "dating-app";

        public String getPrivateKeyBase64() { return privateKeyBase64; }
        public void setPrivateKeyBase64(String privateKeyBase64) { this.privateKeyBase64 = privateKeyBase64; }
        public String getPublicKeyBase64() { return publicKeyBase64; }
        public void setPublicKeyBase64(String publicKeyBase64) { this.publicKeyBase64 = publicKeyBase64; }
        public int getAccessTokenExpirySeconds() { return accessTokenExpirySeconds; }
        public void setAccessTokenExpirySeconds(int accessTokenExpirySeconds) { this.accessTokenExpirySeconds = accessTokenExpirySeconds; }
        public int getRefreshTokenExpiryDays() { return refreshTokenExpiryDays; }
        public void setRefreshTokenExpiryDays(int refreshTokenExpiryDays) { this.refreshTokenExpiryDays = refreshTokenExpiryDays; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
    }
}
