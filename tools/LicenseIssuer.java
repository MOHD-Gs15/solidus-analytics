import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Solidus SA2 license issuer (Ed25519).
 *
 * <p>DEPRECATED - use {@code tools/license/SolidusLicenseTool.java} instead.
 * This legacy tool emits the retired 4-field dash-format keys and documents the
 * pre-embedded-key distribution flow (shipping the public key to customer
 * servers via SOLIDUS_LICENSE_PUBLIC_KEY). Since audit round 2 the verifier
 * embeds the vendor public key in the JAR and IGNORES that env var, so keys
 * issued here fail verification. It is kept only as a historical reference.</p>
 *
 * <p>Replaces the previous HMAC-based SA1 scheme: SA1 required the signing
 * secret on the customer's own server, which made every sale a master-key
 * leak. Ed25519 removes the secret from the customer side entirely.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   # 1. Generate a keypair (do this ONCE, keep the private key offline):
 *   java tools/LicenseIssuer.java generate
 *
 *   # 2. Get the target server's fingerprint (from that server's log,
 *   #      or by running the same command there):
 *   java tools/LicenseIssuer.java fingerprint &lt;server-game-dir&gt;
 *
 *   # 3. Issue a license:
 *   java tools/LicenseIssuer.java issue &lt;privateKeyB64&gt; &lt;licensee&gt; &lt;expiry ISO-8601&gt; &lt;fingerprint|ANY&gt;
 *
 *   # 4. Put the printed SA2 key into config/solidus-analytics/license.key
 *   #    and export SOLIDUS_LICENSE_PUBLIC_KEY=&lt;publicKeyB64&gt; on the server.
 * </pre>
 *
 * <p>Requires Java 15+ (Ed25519). No external dependencies.</p>
 */
public class LicenseIssuer {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "generate".equals(args[0])) {
            generate();
            return;
        }
        if (args.length == 5 && "issue".equals(args[0])) {
            issue(args[1], args[2], args[3], args[4]);
            return;
        }
        if (args.length == 2 && "fingerprint".equals(args[0])) {
            fingerprint(args[1]);
            return;
        }
        System.err.println("Usage:");
        System.err.println("  java tools/LicenseIssuer.java generate");
        System.err.println("  java tools/LicenseIssuer.java fingerprint <server-game-dir>");
        System.err.println("  java tools/LicenseIssuer.java issue <privateKeyFile> <licensee> <expiry ISO-8601> <fingerprint|ANY>");
        System.err.println();
        System.err.println("SECURITY (SA2-025, CWE-214): the FIRST argument of 'issue' is a PATH to a file");
        System.err.println("containing the base64 private key (or set SOLIDUS_ISSUER_KEY and pass '-').");
        System.err.println("A raw private key used to be accepted directly on the command line, which");
        System.err.println("leaked it into shell history, 'ps'/'/proc' and terminal scrollback.");
        System.exit(2);
    }

    private static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        // SECURITY (SA2-025): the private key used to be printed to stdout -
        // terminal scrollback, CI logs and shell history are poor places for
        // a signing root. It now lands in a 0600 file; only the public key
        // is printed.
        java.nio.file.Path privFile = java.nio.file.Path.of("solidus-license-private-key.b64");
        java.nio.file.Path pubFile = java.nio.file.Path.of("solidus-license-public-key.b64");
        java.nio.file.Files.writeString(privFile, privateKey);
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                     java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            java.nio.file.Files.setPosixFilePermissions(privFile, perms);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // non-POSIX filesystem
        }
        java.nio.file.Files.writeString(pubFile, publicKey);
        System.out.println("# PUBLIC key - ships to customer servers (also written to " + pubFile + "):");
        System.out.println(publicKey);
        System.out.println();
        System.out.println("# PRIVATE key - KEEP OFFLINE, never share, never put on a server.");
        System.out.println("# Written to " + privFile + " (chmod 600) instead of stdout.");
    }

    private static void issue(String privateKeyRef, String licensee, String expiryIso, String fingerprint) throws Exception {
        // SECURITY (SA2-025, CWE-214): the raw private key is no longer
        // accepted as a command-line ARGUMENT (it leaked into shell history,
        // 'ps'/'/proc' and terminal scrollback). Pass a PATH to a file that
        // contains the base64 key - or '-' with the key in SOLIDUS_ISSUER_KEY.
        String privateKeyB64;
        if ("-".equals(privateKeyRef)) {
            privateKeyB64 = System.getenv("SOLIDUS_ISSUER_KEY");
            if (privateKeyB64 == null || privateKeyB64.isBlank()) {
                System.err.println("ERROR: SOLIDUS_ISSUER_KEY is not set (required when the key argument is '-')");
                System.exit(2);
                return;
            }
        } else {
            java.nio.file.Path keyFile = java.nio.file.Path.of(privateKeyRef);
            if (!java.nio.file.Files.exists(keyFile)) {
                System.err.println("ERROR: " + privateKeyRef + " does not exist - pass a PATH to a file holding the base64 private key (or '-' with SOLIDUS_ISSUER_KEY)");
                System.exit(2);
                return;
            }
            privateKeyB64 = java.nio.file.Files.readString(keyFile);
        }
        byte[] privateKeyBytes;
        try {
            privateKeyBytes = Base64.getDecoder().decode(privateKeyB64.trim());
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: private key is not valid base64");
            System.exit(2);
            return;
        }
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));

        String payload = "2|" + licensee + "|" + expiryIso + "|" + fingerprint;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        String key = "SA2-"
            + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
            + "-"
            + Base64.getEncoder().encodeToString(signature);
        System.out.println(key);
    }

    /**
     * Computes the server fingerprint exactly like LicenseVerifier inside the
     * mods: sha256(gameDirAbsolutePath + hostname), first 16 hex chars upper.
     */
    private static void fingerprint(String gameDir) throws Exception {
        String raw = (gameDir == null ? "" : gameDir)
            + java.net.InetAddress.getLocalHost().getHostName();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        System.out.println(sb.substring(0, 16).toUpperCase());
    }
}
