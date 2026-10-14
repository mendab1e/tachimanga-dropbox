package eu.kanade.tachiyomi.extension.all.dropbox;

import android.content.Context;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** Test-only availability double; never package or use for the real compatibility check. */
public final class AndroidSignIn {
    public static final AndroidSignIn INSTANCE = new AndroidSignIn();
    public boolean available(Context context) { return true; }
    public void start(Context context, DropboxApi api, Function0<Unit> onConnected) {
        throw new AssertionError("Offline settings specs must not launch native authorization");
    }
}
