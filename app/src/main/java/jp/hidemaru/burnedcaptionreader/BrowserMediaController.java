package jp.hidemaru.burnedcaptionreader;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import java.util.List;
import java.util.Locale;

/** Optional pause/resume controller for browser media sessions. */
public final class BrowserMediaController {
    private final Context context;
    private final MediaSessionManager sessionManager;
    private final ComponentName listenerComponent;
    private MediaController pausedByUs;

    public BrowserMediaController(Context context) {
        this.context = context.getApplicationContext();
        sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(context, CaptionNotificationListenerService.class);
    }

    public boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null || enabled.isEmpty()) return false;
        for (String value : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component != null && context.getPackageName().equals(component.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean pauseBrowser() {
        if (!hasNotificationAccess() || sessionManager == null) return false;
        try {
            List<MediaController> controllers = sessionManager.getActiveSessions(listenerComponent);
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                if (!isBrowser(controller.getPackageName()) || state == null
                        || state.getState() != PlaybackState.STATE_PLAYING) continue;
                controller.getTransportControls().pause();
                pausedByUs = controller;
                return true;
            }
        } catch (SecurityException ignored) {
            // Access may have been revoked while capture was running.
        }
        return false;
    }

    public synchronized void resumeIfPausedByUs() {
        MediaController controller = pausedByUs;
        pausedByUs = null;
        if (controller == null) return;
        PlaybackState state = controller.getPlaybackState();
        if (state != null && state.getState() == PlaybackState.STATE_PAUSED) {
            controller.getTransportControls().play();
        }
    }

    public synchronized void release() {
        pausedByUs = null;
    }

    private boolean isBrowser(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        return value.contains("chrome") || value.contains("browser") || value.contains("sbrowser")
                || value.contains("firefox") || value.contains("mozilla") || value.contains("brave")
                || value.contains("opera") || value.contains("vivaldi") || value.contains("emm");
    }
}
