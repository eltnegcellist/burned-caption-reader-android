package jp.hidemaru.burnedcaptionreader;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import jp.hidemaru.burnedcaptionreader.diagnostics.DiagnosticExport;

/** Settings are also available as a panel inside the running video player. */
public final class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new SettingsPanel(this, new AppPreferences(this),
                this::finish, () -> {}, false));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        DiagnosticExport.handle(this, request, result, data);
    }
}
