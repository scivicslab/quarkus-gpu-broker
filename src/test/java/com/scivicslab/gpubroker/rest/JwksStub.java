package com.scivicslab.gpubroker.rest;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.jwt.build.Jwt;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Stands in for the Keycloak realm in the management-authentication tests: serves one signing key at
 * the JWKS path the broker is configured to read, and signs the tokens the tests present. Nothing
 * outside the process is touched. Started with {@link #up}; {@code false} leaves the port closed, which
 * is what a Keycloak that cannot be reached looks like.
 */
public abstract class JwksStub implements QuarkusTestResourceLifecycleManager {

    static final String ISSUER = "https://keycloak.test/realms/w206-infra";
    static final String ROLE = "broker-capacity-writer";
    static final String KID = "test-key";

    private static KeyPair signing;
    private static KeyPair other;
    private HttpServer server;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            signing = g.generateKeyPair();
            other = g.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** @return whether the stub answers; {@code false} models a Keycloak nobody can reach */
    abstract boolean up();

    @Override
    public Map<String, String> start() {
        try {
            int port;
            if (up()) {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/realms/w206-infra/protocol/openid-connect/certs", ex -> {
                    byte[] out = jwks().getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                    ex.close();
                });
                server.start();
                port = server.getAddress().getPort();
            } else {
                // A port that was free a moment ago and has nobody listening on it.
                try (var s = new java.net.ServerSocket(0)) {
                    port = s.getLocalPort();
                }
            }
            return Map.of(
                    "quarkus.oidc.auth-server-url", "http://127.0.0.1:" + port + "/realms/w206-infra",
                    "quarkus.oidc.token.issuer", ISSUER);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) server.stop(0);
    }

    private static String jwks() {
        RSAPublicKey pub = (RSAPublicKey) signing.getPublic();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + KID + "\","
                + "\"n\":\"" + b64.encodeToString(unsigned(pub.getModulus().toByteArray())) + "\","
                + "\"e\":\"" + b64.encodeToString(unsigned(pub.getPublicExponent().toByteArray())) + "\"}]}";
    }

    private static byte[] unsigned(byte[] b) {
        if (b.length > 1 && b[0] == 0) return java.util.Arrays.copyOfRange(b, 1, b.length);
        return b;
    }

    /** A token as Keycloak issues it to a service account, signed by the key the stub serves. */
    static String token(List<String> roles) {
        return token(roles, ISSUER, Instant.now().plusSeconds(300), false);
    }

    static String token(List<String> roles, String issuer, Instant expires, boolean signedByAnotherKey) {
        KeyPair key = signedByAnotherKey ? other : signing;
        return Jwt.issuer(issuer)
                .subject("service-account-test")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(expires)
                .jws().keyId(KID)
                .sign(key.getPrivate());
    }
}
