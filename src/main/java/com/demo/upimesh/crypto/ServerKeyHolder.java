package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the server's RSA keypair.
 *
 * In production, the private key must be explicitly provisioned via KMS, Vault,
 * or Docker secrets. Generating fallback keys on startup in production is strictly disabled.
 */
@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    @Value("${RSA_PUBLIC_KEY_B64:}")
    private String publicKeyB64;

    @Value("${RSA_PRIVATE_KEY_B64:}")
    private String privateKeyB64;

    @Value("${RSA_PUBLIC_KEY_FILE:}")
    private String publicKeyFile;

    @Value("${RSA_PRIVATE_KEY_FILE:}")
    private String privateKeyFile;

    @Value("${upi.mesh.rsa.key-file-path:}")
    private String keyFilePath;

    @Value("${upi.mesh.rsa.enforce-provisioned:false}")
    private boolean enforceProvisioned;

    private KeyPair keyPair;

    @PostConstruct
    public void init() throws Exception {
        // Mode 1: Load from Docker Secrets / File paths (_FILE variables)
        if (!publicKeyFile.isBlank() && !privateKeyFile.isBlank()) {
            Path pubPath = Path.of(publicKeyFile);
            Path privPath = Path.of(privateKeyFile);
            if (Files.exists(pubPath) && Files.exists(privPath)) {
                String pubB64 = Files.readString(pubPath).trim();
                String privB64 = Files.readString(privPath).trim();
                this.keyPair = loadFromBase64(pubB64, privB64);
                log.info("Server RSA keypair loaded from secret files: {}. Fingerprint: {}",
                        privateKeyFile, getPublicKeyBase64().substring(0, 32) + "...");
                return;
            }
        }

        // Mode 2: Load from environment variables
        if (!publicKeyB64.isBlank() && !privateKeyB64.isBlank()) {
            this.keyPair = loadFromBase64(publicKeyB64, privateKeyB64);
            log.info("Server RSA keypair loaded from environment variables. Fingerprint: {}",
                    getPublicKeyBase64().substring(0, 32) + "...");
            return;
        }

        // Strict enforcement check for production
        if (enforceProvisioned) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: RSA private key must be explicitly provisioned in production deployments (via RSA_PRIVATE_KEY_B64 or Docker secret files). Auto-generation fallback is disabled.");
        }

        // Mode 3: Load from or save to file (Local persistence for development)
        if (!keyFilePath.isBlank()) {
            Path pubPath = Path.of(keyFilePath + ".pub");
            Path privPath = Path.of(keyFilePath + ".key");

            if (Files.exists(pubPath) && Files.exists(privPath)) {
                String pubB64 = Files.readString(pubPath).trim();
                String privB64 = Files.readString(privPath).trim();
                this.keyPair = loadFromBase64(pubB64, privB64);
                log.info("Server RSA keypair loaded from file: {}. Fingerprint: {}",
                        keyFilePath, getPublicKeyBase64().substring(0, 32) + "...");
                return;
            }

            // Generate and persist
            this.keyPair = generateNewKeyPair();
            saveToFile(pubPath, privPath);
            log.info("Server RSA keypair generated and saved to: {}. Fingerprint: {}",
                    keyFilePath, getPublicKeyBase64().substring(0, 32) + "...");
            return;
        }

        // Mode 4: Ephemeral (dev only)
        this.keyPair = generateNewKeyPair();
        log.info("Server RSA keypair generated (ephemeral — development mode). Fingerprint: {}",
                getPublicKeyBase64().substring(0, 32) + "...");
    }

    private KeyPair generateNewKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private KeyPair loadFromBase64(String pubB64, String privB64) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        byte[] pubBytes = Base64.getDecoder().decode(pubB64);
        byte[] privBytes = Base64.getDecoder().decode(privB64);
        PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        return new KeyPair(pub, priv);
    }

    private void saveToFile(Path pubPath, Path privPath) throws IOException {
        Files.createDirectories(pubPath.getParent());
        Files.writeString(pubPath,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        Files.writeString(privPath,
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
