package eu.kanade.tachiyomi.extension.all.dropbox;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Browser callback relay only. Runs in the APK's UID, never reads or stores reader credentials. */
public final class AndroidOAuthActivity extends Activity {
    private ResultReceiver receiver;
    private PendingIntent returnToReader;
    private String state;
    private String authorizationUrl;
    private long expiresAt;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            receiver = savedInstanceState.getParcelable(OAuthContract.RECEIVER);
            returnToReader = savedInstanceState.getParcelable(OAuthContract.RETURN_TO_READER);
            state = savedInstanceState.getString(OAuthContract.STATE);
            authorizationUrl = savedInstanceState.getString(OAuthContract.URL);
            expiresAt = savedInstanceState.getLong("expires_at");
        } else if (OAuthContract.START.equals(getIntent().getAction()) && getIntent().getData() == null) {
            receiver = getIntent().getParcelableExtra(OAuthContract.RECEIVER);
            returnToReader = getIntent().getParcelableExtra(OAuthContract.RETURN_TO_READER);
            authorizationUrl = getIntent().getStringExtra(OAuthContract.URL);
            if (validAuthorizationUrl(authorizationUrl)) {
                state = Uri.parse(authorizationUrl).getQueryParameter(OAuthContract.STATE);
                expiresAt = System.currentTimeMillis() + 10 * 60 * 1000;
            }
        }
        if (receiver == null || state == null || state.isEmpty() || authorizationUrl == null
                || expiresAt <= System.currentTimeMillis()) {
            showExpired();
            return;
        }
        showPending();
        if (getIntent().getData() != null) {
            receiveCallback(getIntent());
        } else if (savedInstanceState == null) {
            openBrowser();
        }
    }

    private boolean validAuthorizationUrl(String value) {
        if (value == null || value.length() > 8192) return false;
        Uri uri = Uri.parse(value);
        return "https".equals(uri.getScheme()) && "www.dropbox.com".equals(uri.getAuthority())
                && "/oauth2/authorize".equals(uri.getPath()) && uri.getFragment() == null
                && OAuthContract.REDIRECT_URI.equals(uri.getQueryParameter("redirect_uri"))
                && "code".equals(uri.getQueryParameter("response_type"))
                && "S256".equals(uri.getQueryParameter("code_challenge_method"))
                && uri.getQueryParameter("state") != null && uri.getQueryParameter("state").length() == 64;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A second start must not replace the receiver of a pending browser authorization.
        if (Intent.ACTION_VIEW.equals(intent.getAction())) receiveCallback(intent);
    }

    private void receiveCallback(Intent intent) {
        if (receiver == null || expiresAt <= System.currentTimeMillis()) {
            showExpired();
            return;
        }
        String code = OAuthContract.callbackCode(intent.getDataString(), state);
        if (code == null) return; // Ignore malformed, unrelated and forged callbacks.
        Bundle result = new Bundle();
        result.putString(OAuthContract.STATE, state);
        if (!code.isEmpty()) result.putString(OAuthContract.CODE, code);
        receiver.send(code.isEmpty() ? RESULT_CANCELED : RESULT_OK, result);
        receiver = null;
        returnToReader();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Binder capabilities and public challenge state only; no verifier, code or tokens.
        outState.putParcelable(OAuthContract.RECEIVER, receiver);
        outState.putParcelable(OAuthContract.RETURN_TO_READER, returnToReader);
        outState.putString(OAuthContract.STATE, state);
        outState.putString(OAuthContract.URL, authorizationUrl);
        outState.putLong("expires_at", expiresAt);
    }

    private void openBrowser() {
        if (expiresAt <= System.currentTimeMillis()) { showExpired(); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ignored) {
            cancel();
        }
    }

    private LinearLayout layout(String message) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextSize(18);
        layout.addView(text);
        setContentView(layout);
        return layout;
    }

    private void button(LinearLayout layout, String text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(action);
        layout.addView(button);
    }

    private void showPending() {
        LinearLayout layout = layout("Finish authorizing Dropbox in your browser. You will return to your reader automatically.");
        button(layout, "Open browser again", view -> openBrowser());
        button(layout, "Cancel sign-in", view -> cancel());
    }

    private void showExpired() {
        LinearLayout layout = layout("This sign-in session expired or your reader was closed. Return to Dropbox source settings and start sign-in again, or use the manual sign-in link.");
        button(layout, "Return to reader", view -> cancel());
    }

    @Override public void onBackPressed() { cancel(); }

    private void cancel() {
        if (receiver != null && state != null) {
            Bundle result = new Bundle();
            result.putString(OAuthContract.STATE, state);
            receiver.send(RESULT_CANCELED, result);
            receiver = null;
        }
        returnToReader();
    }

    private void returnToReader() {
        if (returnToReader != null) {
            try { returnToReader.send(); } catch (PendingIntent.CanceledException ignored) { }
        }
        finish();
    }
}
