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
 * <p>This tool is the ONLY place where the signing private key is ever used.
 * Run it on a trusted, offline-capable machine - never on customer servers.
 * Customer servers receive only the PUBLIC key (via SOLIDUS_LICENSE_PUBLIC_KEY)
 * and can verify licenses but can never mint them.</p>
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
        System.err.println("  java tools/LicenseIssuer.java issue <privateKeyB64> <licensee> <expiry ISO-8601> <fingerprint|ANY>");
        System.exit(2);
    }

    private static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair pair = generator.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        System.out.println("# PUBLIC key - ships to customer servers via SOLIDUS_LICENSE_PUBLIC_KEY:");
        System.out.println(publicKey);
        System.out.println();
        System.out.println("# PRIVATE key - KEEP OFFLINE, never share, never put on a server:");
        System.out.println(privateKey);
    }

    private static void issue(String privateKeyB64, String licensee, String expiryIso, String fingerprint) throws Exception {
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
