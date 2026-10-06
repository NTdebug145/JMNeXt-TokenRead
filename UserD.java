import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;

public class UserD {

    public static void main(String[] args) throws Exception {
        Path keyFile = Paths.get(System.getProperty("user.home"),
                ".config", "jmnext", "keys", "jm_session_v1.key");
        if (!Files.exists(keyFile)) {
            System.err.println("[!] key file missing: " + keyFile);
            return;
        }
        byte[] key = Files.readAllBytes(keyFile);
        if (key.length != 32) {
            System.err.println("[!] unexpected key length: " + key.length);
            return;
        }

        Preferences prefs = Preferences.userRoot().node("com/jmnext/jm_secure");

        String jwt = decryptField(prefs, "jwt", key);
        if (jwt != null) {
            System.out.println("=== jwt ===");
            printJwt(jwt);
            System.out.println();
        }

        String member = decryptField(prefs, "member", key);
        if (member != null) {
            System.out.println("=== member ===");
            System.out.println(prettyJson(member));
            System.out.println();
        }
    }

    static String decryptField(Preferences prefs, String name, byte[] key) {
        String b64 = prefs.get(name, null);
        if (b64 == null || b64.isEmpty()) {
            System.err.println("[!] no '" + name + "' entry in secure store");
            return null;
        }
        String plain = decryptGcm(b64, key);
        if (plain == null) {
            System.err.println("[!] decryption failed for '" + name + "'");
        }
        return plain;
    }

    static void printJwt(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            System.out.println("[!] not a well-formed JWT (expected 3 segments, got " + parts.length + ")");
            System.out.println(jwt);
            return;
        }
        System.out.println("--- header ---");
        System.out.println(prettyJson(decodeSegment(parts[0])));
        System.out.println("--- payload ---");
        System.out.println(prettyJson(decodeSegment(parts[1])));
        System.out.println("--- signature ---");
        System.out.println("[binary, " + parts[2].length() + " base64 chars, not printable]");
    }

    static String decodeSegment(String segment) {
        String s = segment.replace('-', '+').replace('_', '/');
        int pad = (4 - s.length() % 4) % 4;
        for (int i = 0; i < pad; i++) s += "=";
        byte[] raw = Base64.getDecoder().decode(s);
        return new String(raw, StandardCharsets.UTF_8);
    }

    static String prettyJson(String json) {
        String unescaped = unescapeUnicode(json);

        StringBuilder sb = new StringBuilder(unescaped.length() + 64);
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < unescaped.length(); i++) {
            char c = unescaped.charAt(i);

            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < unescaped.length()) {
                    sb.append(unescaped.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"':
                    inString = true;
                    sb.append(c);
                    break;
                case '{':
                case '[':
                    sb.append(c);
                    indent++;
                    sb.append('\n').append(spaces(indent));
                    break;
                case '}':
                case ']':
                    indent = Math.max(0, indent - 1);
                    sb.append('\n').append(spaces(indent)).append(c);
                    break;
                case ',':
                    sb.append(c).append('\n').append(spaces(indent));
                    break;
                case ':':
                    sb.append(": ");
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }

    static String unescapeUnicode(String input) {
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(input);
        StringBuffer sb = new StringBuffer(input.length());
        while (m.find()) {
            int cp = Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) cp)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String decryptGcm(String b64, byte[] key) {
        try {
            byte[] packed = Base64.getDecoder().decode(b64);
            if (packed.length < 2) return null;
            int ivLen = packed[0] & 0xFF;
            if (ivLen < 12 || ivLen > 16 || packed.length < 1 + ivLen) return null;

            byte[] iv = new byte[ivLen];
            System.arraycopy(packed, 1, iv, 0, ivLen);
            byte[] body = new byte[packed.length - 1 - ivLen];
            System.arraycopy(packed, 1 + ivLen, body, 0, body.length);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}