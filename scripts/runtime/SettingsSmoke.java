import android.app.Application;
import android.content.SharedPreferences;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import eu.kanade.tachiyomi.extension.all.dropbox.Dropbox;
import eu.kanade.tachiyomi.network.NetworkHelper;
import java.lang.reflect.*;
import java.util.*;
import okhttp3.OkHttpClient;
import io.javalin.plugin.json.JavalinJackson;
import suwayomi.tachidesk.manga.impl.Source;
import uy.kohesive.injekt.InjektKt;
import uy.kohesive.injekt.api.TypeReference;

/** Offline integration check against an installed Tachimanga runtime, never real user preferences. */
public class SettingsSmoke {
    private static final Map<String, Object> values = new HashMap<>();
    private static SharedPreferences prefs;
    public static class MemoryApplication extends Application {
        @Override public SharedPreferences getSharedPreferences(String key, int mode) { return prefs; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            android.content.pm.PackageInfo info = suwayomi.tachidesk.manga.impl.util.PackageTools.INSTANCE.getPackageInfo(args[1]);
            if (!info.packageName.equals(eu.kanade.tachiyomi.extension.all.dropbox.BuildConfig.APPLICATION_ID)
                    || !info.versionName.equals(eu.kanade.tachiyomi.extension.all.dropbox.BuildConfig.VERSION_NAME)
                    || info.versionCode != eu.kanade.tachiyomi.extension.all.dropbox.BuildConfig.VERSION_CODE) {
                throw new AssertionError("Tachimanga APK parser read unexpected package/version");
            }
            System.out.println("Tachimanga APK parser recognized " + info.versionName + " (code " + info.versionCode + ")");
        }
        prefs = (SharedPreferences) Proxy.newProxyInstance(SettingsSmoke.class.getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, params) -> {
            switch (method.getName()) {
                case "getString": case "getBoolean": case "getLong": case "getInt": case "getFloat": case "getStringSet": return values.getOrDefault((String) params[0], params[1]);
                case "contains": return values.containsKey(params[0]);
                case "getAll": return new HashMap<>(values);
                case "edit": return editor();
                default: return null;
            }
        });
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        // Avoid runtime Android constructors and any disk/global configuration access.
        MemoryApplication app = (MemoryApplication) unsafe.allocateInstance(MemoryApplication.class);
        NetworkHelper network = (NetworkHelper) unsafe.allocateInstance(NetworkHelper.class);
        Field client = NetworkHelper.class.getDeclaredField("client");
        client.setAccessible(true);
        client.set(network, new OkHttpClient());
        InjektKt.getInjekt().addSingleton((TypeReference<Application>) () -> Application.class, app);
        InjektKt.getInjekt().addSingleton((TypeReference<NetworkHelper>) () -> NetworkHelper.class, network);
        Dropbox source = new Dropbox();
        // Use the parent's virtual method, exactly as Tachimanga's Source DTO does.
        assertCleanHome(source);
        if (!values.isEmpty()) throw new AssertionError("Reading source metadata prepared OAuth credentials");
        PreferenceScreen screen = new PreferenceScreen(app);
        screen.setSharedPreferences(prefs);
        source.setupPreferenceScreen(screen);
        Preference build = screen.getPreferences().stream().filter(pref -> "extension_build".equals(pref.getKey())).findFirst().orElseThrow();
        if (build.isEnabled() || !build.getTitle().toString().contains(eu.kanade.tachiyomi.extension.all.dropbox.BuildConfig.VERSION_NAME)) throw new AssertionError("Executable build label missing");
        assertCleanHome(source);
        for (Preference pref : screen.getPreferences()) {
            if (pref.getKey() == null || pref.getDefaultValue() == null) throw new AssertionError("Unserializable preference: " + pref.getTitle());
            pref.getCurrentValue();
            pref.getDefaultValueType();
        }
        List<Source.PreferenceObject> objects = new ArrayList<>();
        for (Preference pref : screen.getPreferences()) objects.add(new Source.PreferenceObject(pref.getClass().getSimpleName(), pref));
        String json = new JavalinJackson().toJsonString(objects);
        if (!json.contains("authorization_url") || !json.contains("ListPreference")) throw new AssertionError("Settings JSON omitted expected controls");
        System.out.println("Settings screen created with " + screen.getPreferences().size() + " serializable preferences");
        if (args.length > 0 && args[0].equals("actions")) exercise(source, screen);
    }
    private static SharedPreferences.Editor editor() {
        final Map<String, Object> pending = new HashMap<>();
        final List<String> removed = new ArrayList<>();
        final boolean[] clear = {false};
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SettingsSmoke.class.getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (proxy, method, params) -> {
            String name = method.getName();
            if (name.startsWith("put")) { pending.put((String) params[0], params[1]); return proxy; }
            if (name.equals("remove")) { removed.add((String) params[0]); return proxy; }
            if (name.equals("clear")) { clear[0] = true; return proxy; }
            if (name.equals("commit") || name.equals("apply")) {
                if (clear[0]) values.clear();
                removed.forEach(values::remove); values.putAll(pending);
                return name.equals("commit") ? true : null;
            }
            return null;
        });
    }
    private static Preference find(PreferenceScreen screen, String key) {
        return screen.getPreferences().stream().filter(pref -> key.equals(pref.getKey())).findFirst().orElseThrow();
    }
    private static void assertCleanHome(Dropbox source) {
        Map<String, Object> before = new HashMap<>(values);
        String home = ((eu.kanade.tachiyomi.source.online.HttpSource) source).getHomeUrl();
        if (!"https://www.dropbox.com".equals(home)) throw new AssertionError("Browse subtitle contains a dynamic URL");
        if (!home.equals(source.getMangaUrl(eu.kanade.tachiyomi.source.model.SManga.Companion.create()))) throw new AssertionError("Manga browser contains an authorization URL");
        if (!home.equals(source.getChapterUrl(eu.kanade.tachiyomi.source.model.SChapter.Companion.create()))) throw new AssertionError("Chapter browser contains an authorization URL");
        if (!before.equals(values)) throw new AssertionError("Browser hooks changed authentication settings");
    }
    private static void exercise(Dropbox source, PreferenceScreen screen) {
        find(screen, "library_path").callChangeListener("/Books");
        if (!"/Books".equals(values.get("library_path"))) throw new AssertionError("Library preference not saved");
        String url = (String) values.get("authorization_url");
        if (url == null || !url.startsWith("https://www.dropbox.com/oauth2/authorize?") || !url.contains("code_challenge_method=S256")) throw new AssertionError("Sign-in URL not generated");
        if (!values.containsKey("verifier")) throw new AssertionError("PKCE verifier not saved");
        String verifier = (String) values.get("verifier");
        assertCleanHome(source);
        if (!url.equals(values.get("authorization_url")) || !verifier.equals(values.get("verifier"))) throw new AssertionError("Source metadata changed pending authorization");
        PreferenceScreen reopened = new PreferenceScreen(screen.getContext());
        reopened.setSharedPreferences(prefs);
        new Dropbox().setupPreferenceScreen(reopened);
        if (!url.equals(find(reopened, "authorization_url").getCurrentValue())) throw new AssertionError("Sign-in URL lost when source reloaded");
        assertCleanHome(new Dropbox());
        values.put("refresh_token", "test-refresh-token");
        values.put("authorization_url", "");
        values.put("verifier", "");
        assertCleanHome(source);
        find(reopened, "connection_action").callChangeListener("refresh");
        find(reopened, "connection_action").callChangeListener("disconnect");
        for (String key : Arrays.asList("access_token", "refresh_token", "auth_code", "verifier", "authorization_url")) {
            if (!"".equals(values.get(key))) throw new AssertionError("Disconnect did not clear " + key);
        }
        assertCleanHome(source);
        if (!"".equals(values.get("verifier"))) throw new AssertionError("Source metadata restarted OAuth after disconnect");
        System.out.println("Settings-only stable PKCE link, clean browser hooks across connection states, library edit, refresh and disconnect passed without network requests");
    }
}
