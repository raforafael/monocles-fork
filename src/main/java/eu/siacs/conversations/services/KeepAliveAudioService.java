package eu.siacs.conversations.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.preference.PreferenceManager;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;

/**
 * FORK EDIT D — "silent audio keep-alive".
 *
 * <p>Android's Doze mode freezes background network sockets, so without a push service (FCM /
 * UnifiedPush) an XMPP connection can only be serviced when an alarm fires — in practice every
 * 5-10 minutes. That is the entire cause of the "messages arrive a few minutes late" symptom.
 *
 * <p>Android will however never freeze an app that is actively playing audio, because doing so
 * would audibly interrupt the user's music. This service exploits that by looping a completely
 * silent WAV through ExoPlayer inside a {@code mediaPlayback} foreground service. The system
 * therefore keeps the process — and crucially its network access — fully awake, and messages are
 * delivered the instant the server pushes them over the already-open stream.
 *
 * <p>Trade-offs, all deliberate:
 *
 * <ul>
 *   <li>Higher battery use, because the CPU/radio are no longer allowed to fully idle.
 *   <li>The audio session is real, so it takes an audio "slot". We use {@link C#USAGE_MEDIA} with
 *       {@code handleAudioFocus = false} so we never steal focus from, pause, or duck other apps'
 *       music.
 *   <li>Google Play forbids this pattern. Irrelevant for a self-built / F-Droid sideloaded fork.
 * </ul>
 *
 * <p>Opt-in only, via the {@code keep_alive_audio} preference (default off).
 */
public class KeepAliveAudioService extends android.app.Service {

    public static final String PREFERENCE_KEY = "keep_alive_audio";

    private static final String CHANNEL_ID = "keep_alive_audio";
    private static final int NOTIFICATION_ID = 0x4b41; // "KA"

    private ExoPlayer player;

    /** True when the user has enabled the keep-alive preference. */
    public static boolean isEnabled(final Context context) {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        return p.getBoolean(PREFERENCE_KEY, context.getResources().getBoolean(R.bool.keep_alive_audio));
    }

    /** Starts or stops the service to match the current preference value. Safe to call often. */
    public static void apply(final Context context) {
        final Intent intent = new Intent(context, KeepAliveAudioService.class);
        if (isEnabled(context)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } catch (final RuntimeException e) {
                Log.w(Config.LOGTAG, "unable to start keep alive audio service", e);
            }
        } else {
            try {
                context.stopService(intent);
            } catch (final RuntimeException e) {
                // ignored
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // USAGE_MEDIA keeps us in the "music is playing" bucket the OS refuses to freeze.
        // handleAudioFocus=false is essential: we must never interrupt the user's real audio.
        final AudioAttributes attributes =
                new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build();

        player =
                new ExoPlayer.Builder(this)
                        .setAudioAttributes(attributes, /* handleAudioFocus= */ false)
                        .setWakeMode(C.WAKE_MODE_NETWORK)
                        .build();

        final Uri uri =
                Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.silence);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVolume(0f);
        player.prepare();
        player.setPlayWhenReady(true);
        Log.d(Config.LOGTAG, "KeepAliveAudioService: silent playback started");
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        startForegroundCompat();
        return START_STICKY;
    }

    private void startForegroundCompat() {
        final PendingIntent contentIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, ConversationsActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification notification =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.keep_alive_audio_notification_title))
                        .setContentText(getString(R.string.keep_alive_audio_notification_text))
                        .setSmallIcon(R.drawable.ic_link_24dp)
                        .setContentIntent(contentIntent)
                        .setOngoing(true)
                        .setShowWhen(false)
                        .setPriority(NotificationCompat.PRIORITY_MIN)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (final IllegalStateException | SecurityException e) {
            Log.e(Config.LOGTAG, "unable to start keep alive foreground service", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final android.app.NotificationChannel channel =
                    new android.app.NotificationChannel(
                            CHANNEL_ID,
                            getString(R.string.keep_alive_audio_channel_name),
                            android.app.NotificationManager.IMPORTANCE_MIN);
            channel.setDescription(getString(R.string.keep_alive_audio_channel_description));
            channel.setShowBadge(false);
            final android.app.NotificationManager manager =
                    getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        Log.d(Config.LOGTAG, "KeepAliveAudioService: stopped");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }
}
