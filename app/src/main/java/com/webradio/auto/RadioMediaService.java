package com.webradio.auto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import java.util.ArrayList;
import java.util.List;

public class RadioMediaService extends MediaBrowserServiceCompat {

    private static final String TAG = "RadioMediaService";
    private static final String CHANNEL_ID = "webradio_channel";
    private static final int NOTIFICATION_ID = 1;

    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaPlayer silentPlayer;

    public interface CommandListener {
        void onPrevious();
        void onNext();
        void onPlay();
        void onPause();
    }

    private static CommandListener commandListener;

    public static void setCommandListener(CommandListener listener) {
        commandListener = listener;
    }

    private static String currentStation = "Web Radio Auto";
    private static String currentTitle = "Seleziona una stazione";
    private static boolean isPlaying = false;

    public static void updateNowPlaying(String station, String title, boolean playing) {
        currentStation = station != null ? station : "Web Radio Auto";
        currentTitle = title != null ? title : "";
        isPlaying = playing;
    }

    private static RadioMediaService instance;

    public static RadioMediaService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        setupMediaSession();
        startSilentAudio();
        requestAudioFocus();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Web Radio Auto", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Controlli riproduzione radio");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);

        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                Log.d(TAG, "COMANDO: PLAY");
                isPlaying = true;
                updatePlaybackState();
                updateNotification();
                if (commandListener != null) commandListener.onPlay();
            }

            @Override
            public void onPause() {
                Log.d(TAG, "COMANDO: PAUSE");
                isPlaying = false;
                updatePlaybackState();
                updateNotification();
                if (commandListener != null) commandListener.onPause();
            }

            @Override
            public void onSkipToNext() {
                Log.d(TAG, "COMANDO: NEXT");
                if (commandListener != null) commandListener.onNext();
            }

            @Override
            public void onSkipToPrevious() {
                Log.d(TAG, "COMANDO: PREVIOUS");
                if (commandListener != null) commandListener.onPrevious();
            }

            @Override
            public void onFastForward() {
                Log.d(TAG, "COMANDO: FF -> NEXT");
                if (commandListener != null) commandListener.onNext();
            }

            @Override
            public void onRewind() {
                Log.d(TAG, "COMANDO: REWIND -> PREV");
                if (commandListener != null) commandListener.onPrevious();
            }

            @Override
            public boolean onMediaButtonEvent(@NonNull Intent mediaButtonEvent) {
                Log.d(TAG, "MEDIA BUTTON: " + mediaButtonEvent);
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });

        updatePlaybackState();
        updateMetadata();
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());
        Log.d(TAG, "MediaSession attiva");
    }

    /**
     * FIX MT8227L / Android 13:
     * Riproduce un file WAV silenzioso in loop tramite MediaPlayer nativo.
     * Questo e NECESSARIO perche Android 13 sulle autoradio con chipset
     * MediaTek 8227L richiede un MediaPlayer nativo attivo per considerare
     * la MediaSession come quella "corrente" e inoltrarle i comandi volante.
     * L'audio HTML5 nella WebView NON viene riconosciuto dal sistema.
     */
    private void startSilentAudio() {
        try {
            silentPlayer = new MediaPlayer();
            silentPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            silentPlayer.setVolume(0f, 0f);

            java.io.File silentFile = createSilentWavFile();
            if (silentFile != null) {
                silentPlayer.setDataSource(silentFile.getAbsolutePath());
                silentPlayer.setLooping(true);
                silentPlayer.prepare();
                silentPlayer.start();
                Log.d(TAG, "Audio silenzioso avviato");
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore audio silenzioso: " + e.getMessage());
        }
    }

    private java.io.File createSilentWavFile() {
        try {
            java.io.File file = new java.io.File(getCacheDir(), "silence.wav");
            if (file.exists()) return file;

            int sampleRate = 8000;
            int numSamples = sampleRate;
            int dataSize = numSamples * 2;

            byte[] wav = new byte[44 + dataSize];
            wav[0]='R'; wav[1]='I'; wav[2]='F'; wav[3]='F';
            int fileSize = 36 + dataSize;
            wav[4]=(byte)(fileSize&0xff); wav[5]=(byte)((fileSize>>8)&0xff);
            wav[6]=(byte)((fileSize>>16)&0xff); wav[7]=(byte)((fileSize>>24)&0xff);
            wav[8]='W'; wav[9]='A'; wav[10]='V'; wav[11]='E';
            wav[12]='f'; wav[13]='m'; wav[14]='t'; wav[15]=' ';
            wav[16]=16; wav[20]=1;
            wav[22]=1;
            wav[24]=(byte)(sampleRate&0xff); wav[25]=(byte)((sampleRate>>8)&0xff);
            int byteRate = sampleRate * 2;
            wav[28]=(byte)(byteRate&0xff); wav[29]=(byte)((byteRate>>8)&0xff);
            wav[32]=2;
            wav[34]=16;
            wav[36]='d'; wav[37]='a'; wav[38]='t'; wav[39]='a';
            wav[40]=(byte)(dataSize&0xff); wav[41]=(byte)((dataSize>>8)&0xff);
            wav[42]=(byte)((dataSize>>16)&0xff); wav[43]=(byte)((dataSize>>24)&0xff);

            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(wav);
            fos.close();
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private void requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChange ->
                    Log.d(TAG, "Audio focus: " + focusChange))
                .build();

            audioManager.requestAudioFocus(audioFocusRequest);
        }
    }

    public void updatePlaybackState() {
        long actions = PlaybackStateCompat.ACTION_PLAY
                     | PlaybackStateCompat.ACTION_PAUSE
                     | PlaybackStateCompat.ACTION_PLAY_PAUSE
                     | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                     | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                     | PlaybackStateCompat.ACTION_FAST_FORWARD
                     | PlaybackStateCompat.ACTION_REWIND
                     | PlaybackStateCompat.ACTION_STOP;

        int state = isPlaying
            ? PlaybackStateCompat.STATE_PLAYING
            : PlaybackStateCompat.STATE_PAUSED;

        PlaybackStateCompat ps = new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build();

        mediaSession.setPlaybackState(ps);
    }

    public void updateMetadata() {
        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                       currentTitle.isEmpty() ? currentStation : currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentStation)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Web Radio Auto")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1)
            .build();

        mediaSession.setMetadata(metadata);
    }

    public void updateNotification() {
        updateMetadata();
        updatePlaybackState();

        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Action prevAction = new NotificationCompat.Action(
            android.R.drawable.ic_media_previous, "Precedente",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));

        NotificationCompat.Action playPauseAction;
        if (isPlaying) {
            playPauseAction = new NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pausa",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PAUSE));
        } else {
            playPauseAction = new NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY));
        }

        NotificationCompat.Action nextAction = new NotificationCompat.Action(
            android.R.drawable.ic_media_next, "Successiva",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentStation)
            .setContentText(currentTitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    public void stopService() {
        isPlaying = false;
        if (silentPlayer != null) {
            try { silentPlayer.stop(); silentPlayer.release(); } catch (Exception e) {}
            silentPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        if (audioManager != null && audioFocusRequest != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        stopForeground(true);
        stopSelf();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName,
                                  int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(new ArrayList<>());
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (silentPlayer != null) {
            try { silentPlayer.stop(); silentPlayer.release(); } catch (Exception e) {}
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }
}
