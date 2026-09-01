package jp.hidemaru.burnedcaptionreader;

import android.service.notification.NotificationListenerService;

/**
 * Android requires an enabled notification-listener component before a third-party app may query
 * active media sessions. Notification contents are intentionally not inspected or stored.
 */
public final class CaptionNotificationListenerService extends NotificationListenerService {}
