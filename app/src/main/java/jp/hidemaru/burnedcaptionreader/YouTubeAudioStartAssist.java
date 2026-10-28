package jp.hidemaru.burnedcaptionreader;

/**
 * Best-effort unmute on the user's existing play/tap gesture in YouTube WebView.
 *
 * A synthetic HTMLElement.click() does not create trusted user activation and
 * cannot reliably bypass Chromium/YouTube autoplay restrictions. This helper
 * never auto-clicks, removes or overlays the original YouTube controls.
 */
public final class YouTubeAudioStartAssist {
    private YouTubeAudioStartAssist() {}

    public static final String INSTALL_SCRIPT = """
            (() => {
              if (window.__captionAudioStartAssist) return 'already_installed';
              // A real YouTube-video tap is needed for a browser-trusted activation.
              const state = { video: null, activated: new WeakSet(),
                              gestureVideo: null, gestureAt: -1000000,
                              attempts: 0 };
              window.__captionAudioStartAssist = state;
              const video = () => document.querySelector('video.html5-main-video')
                               || document.querySelector('video');
              const markStable = (v) => {
                // YouTube's own play handler may momentarily re-mute after the tap.
                // Do not mark success until the audio setting has stayed stable.
                setTimeout(() => {
                  if (!v.muted && v.volume > 0) state.activated.add(v);
                }, 800);
              };
              const unmute = (v) => {
                if (!v || state.activated.has(v)) return;
                // Do not override the user's subsequent volume/mute decisions.
                if (!v.muted && v.volume > 0) {
                  markStable(v);
                  return;
                }
                if (state.attempts >= 3) return;
                state.attempts++;
                try {
                  v.muted = false;
                  v.defaultMuted = false;
                  if (v.volume === 0) v.volume = 1;
                  if (!v.muted && v.volume > 0) markStable(v);
                } catch (ignored) {
                  // YouTube / WebView may reject unmuting outside trusted activation.
                }
              };
              const attach = (v) => {
                if (!v || state.video === v) return;
                state.video = v;
                state.attempts = 0;
                v.addEventListener('play', () => {
                  if (state.gestureVideo === v && performance.now() - state.gestureAt < 2000)
                    unmute(v);
                });
                v.addEventListener('volumechange', () => {
                  if (state.gestureVideo === v && performance.now() - state.gestureAt < 750
                      && v.muted && !state.activated.has(v)) unmute(v);
                });
              };
              const gesture = (event) => {
                // Only the user's genuine action in the on-screen video area.
                if (!event.isTrusted) return;
                const v = video();
                if (!v) return;
                const rect = v.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return;
                if (event.type !== 'keydown') {
                  if (event.clientX < rect.left || event.clientX > rect.right
                      || event.clientY < rect.top || event.clientY > rect.bottom) return;
                } else if (!['Enter', ' ', 'MediaPlayPause'].includes(event.key)) return;
                attach(v);
                state.gestureVideo = v;
                state.gestureAt = performance.now();
                unmute(v);
              };
              // pointerup is user activation for touch on Chromium.
              document.addEventListener('pointerup', gesture, true);
              document.addEventListener('click', gesture, true);
              document.addEventListener('keydown', gesture, true);
              return 'installed';
            })()
            """;
}
