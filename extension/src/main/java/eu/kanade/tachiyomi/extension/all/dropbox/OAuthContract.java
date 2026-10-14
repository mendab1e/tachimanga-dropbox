package eu.kanade.tachiyomi.extension.all.dropbox;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Android bridge protocol; no host-provided libraries are needed by the installed activity. */
public final class OAuthContract {
    public static final String REDIRECT_URI = "dropbox-manga://oauth/callback";
    public static final String ACTIVITY = "eu.kanade.tachiyomi.extension.all.dropbox.AndroidOAuthActivity";
    public static final String START = "eu.kanade.tachiyomi.extension.all.dropbox.SIGN_IN";
    public static final String RECEIVER = "receiver";
    public static final String RETURN_TO_READER = "return_to_reader";
    public static final String URL = "authorization_url";
    public static final String STATE = "state";
    public static final String CODE = "code";
    public static final long SESSION_LIFETIME_MS = 10 * 60 * 1000L;
    public static final int STATE_LENGTH = 64;
    public static final int MAX_URL_LENGTH = 8192;
    public static final int MAX_CODE_LENGTH = 4096;

    private OAuthContract() {}

    public static boolean validCode(String code) {
        if (code == null || code.isEmpty() || code.length() > MAX_CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < 33 || code.charAt(i) > 126) return false;
        }
        return true;
    }

    /** Null means invalid; an empty code means the user denied access. Never return error text. */
    public static String callbackCode(String url, String expectedState) {
        if (url == null || url.length() > MAX_URL_LENGTH || expectedState == null || expectedState.isEmpty()) return null;
        try {
            URI uri = new URI(url);
            if (!"dropbox-manga".equals(uri.getScheme()) || !"oauth".equals(uri.getRawAuthority())
                    || !"/callback".equals(uri.getRawPath()) || uri.getRawFragment() != null
                    || uri.getRawQuery() == null) return null;
            Map<String, String> values = new HashMap<>();
            String[] fields = uri.getRawQuery().split("&", -1);
            if (fields.length > 8) return null;
            for (String field : fields) {
                String[] pair = field.split("=", 2);
                if (pair.length != 2) return null;
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value = URLDecoder.decode(pair[1], "UTF-8");
                if (values.put(key, value) != null) return null;
            }
            String state = values.get("state");
            if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                    expectedState.getBytes(StandardCharsets.UTF_8))) return null;
            if (values.containsKey("error")) {
                return !values.containsKey("code") && "access_denied".equals(values.get("error")) ? "" : null;
            }
            String code = values.get("code");
            return validCode(code) ? code : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
