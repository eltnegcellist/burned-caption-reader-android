package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.os.Bundle;

/** Settings are also available as a panel inside the running video player. */
public final class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new SettingsPanel(this, new AppPreferences(this),
                this::finish, () -> {}, false));
    }
}
