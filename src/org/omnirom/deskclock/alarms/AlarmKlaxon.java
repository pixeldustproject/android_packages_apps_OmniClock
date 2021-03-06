/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnirom.deskclock.alarms;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.preference.PreferenceManager;

import org.omnirom.deskclock.LogUtils;
import org.omnirom.deskclock.SettingsActivity;
import org.omnirom.deskclock.Utils;
import org.omnirom.deskclock.provider.AlarmInstance;

/**
 * Manages playing ringtone and vibrating the device.
 */
public class AlarmKlaxon {
    private static final long[] sVibratePattern = new long[]{500, 500};

    // Volume suggested by media team for in-call alarms.
    private static final float IN_CALL_VOLUME = 0.125f;

    private static final int INCREASING_VOLUME_START = 1;
    private static final int INCREASING_VOLUME_DELTA = 1;

    private static boolean sStarted = false;
    private static AudioManager sAudioManager = null;
    private static MediaPlayer sMediaPlayer = null;
    private static boolean sPreAlarmMode = false;
    private static List<Uri> mSongs = new ArrayList<Uri>();
    private static Uri mCurrentTone;
    private static int sCurrentIndex;
    private static int sCurrentVolume = INCREASING_VOLUME_START;
    private static int sSavedVolume;
    private static int sMaxVolume;
    private static boolean sIncreasingVolume;
    private static boolean sRandomPlayback;
    private static long sVolumeIncreaseSpeed;
    private static boolean sFirstFile;
    private static Context sContext;
    private static boolean sRandomMusicMode;
    private static boolean sLocalMediaMode;
    private static boolean sPlayFallbackAlarm;

    // Internal messages
    private static final int INCREASING_VOLUME = 1001;

    private static Handler sHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case INCREASING_VOLUME:
                    if (sStarted) {
                        sCurrentVolume += INCREASING_VOLUME_DELTA;
                        if (sCurrentVolume <= sMaxVolume) {
                            LogUtils.v("Increasing alarm volume to " + sCurrentVolume);
                            sAudioManager.setStreamVolume(
                                    getAudioStream(sContext), sCurrentVolume, 0);
                            sHandler.sendEmptyMessageDelayed(INCREASING_VOLUME,
                                    sVolumeIncreaseSpeed);
                        }
                    }
                    break;
            }
        }
    };

    private static int getAudioStream(Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String stream = prefs.getString(SettingsActivity.KEY_AUDIO_STREAM, "1");
        int streamInt = Integer.decode(stream).intValue();
        return streamInt == 0 ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_ALARM;
    }

    public static void stop(Context context) {
        if (sStarted) {
            LogUtils.v("AlarmKlaxon.stop()");

            sStarted = false;
            sHandler.removeMessages(INCREASING_VOLUME);
            // reset to default from before
            sAudioManager.setStreamVolume(getAudioStream(context),
                    sSavedVolume, 0);
            sAudioManager.abandonAudioFocus(null);

            // Stop audio playing
            if (sMediaPlayer != null) {
                sMediaPlayer.stop();
                sMediaPlayer.reset();
                sMediaPlayer.release();
                sMediaPlayer = null;
            }
            sPreAlarmMode = false;

            ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE))
                    .cancel();
        }
    }

    public static void start(final Context context, AlarmInstance instance) {
        sContext = context;

        // Make sure we are stop before starting
        stop(context);

        LogUtils.v("AlarmKlaxon.start() " + instance);

        sPreAlarmMode = false;
        if (instance.mAlarmState == AlarmInstance.PRE_ALARM_STATE) {
            sPreAlarmMode = true;
        }

        sVolumeIncreaseSpeed = getVolumeChangeDelay(context);
        LogUtils.v("Volume increase interval " + sVolumeIncreaseSpeed);

        final Context appContext = context.getApplicationContext();
        sAudioManager = (AudioManager) appContext
                .getSystemService(Context.AUDIO_SERVICE);
        // save current value
        sSavedVolume = sAudioManager.getStreamVolume(getAudioStream(context));
        sIncreasingVolume = instance.getIncreasingVolume(sPreAlarmMode);
        sRandomPlayback = instance.getRandomMode(sPreAlarmMode);
        sFirstFile = true;

        if (sPreAlarmMode) {
            sMaxVolume = calcNormalizedVolume(context, instance.mPreAlarmVolume);
        } else {
            sMaxVolume = calcNormalizedVolume(context, instance.mAlarmVolume);
        }
        if (sMaxVolume == -1) {
            // calc from current alarm volume
            sMaxVolume = calcNormalizedVolumeFromCurrentAlarm(context);
        }

        Uri alarmNoise = null;
        sRandomMusicMode = false;
        sLocalMediaMode = false;
        sPlayFallbackAlarm = false;

        sCurrentIndex = 0;
        if (sPreAlarmMode) {
            alarmNoise = instance.mPreAlarmRingtone;
        } else {
            alarmNoise = instance.mRingtone;
        }
        if (alarmNoise != null && Utils.isSpotifyUri(alarmNoise.toString())) {
            alarmNoise = null;
        }
        if (alarmNoise != null) {
            if (Utils.isRandomUri(alarmNoise.toString())) {
                sRandomMusicMode = true;
                sRandomPlayback = true;
                mSongs = Utils.getRandomMusicFiles(context, 50);
                if (mSongs.size() != 0) {
                    alarmNoise = mSongs.get(0);
                } else {
                    // fallback
                    alarmNoise = null;
                    sRandomMusicMode = false;
                }
            } else if (Utils.isLocalPlaylistType(alarmNoise.toString())) {
                // can fail if no external storage permissions
                try {
                    sLocalMediaMode = true;
                    if (Utils.isLocalAlbumUri(alarmNoise.toString())) {
                        collectAlbumSongs(context, alarmNoise);
                    }
                    if (Utils.isLocalArtistUri(alarmNoise.toString())) {
                        collectArtistSongs(context, alarmNoise);
                    }
                    if (Utils.isFolderUri(alarmNoise.toString())) {
                        collectFiles(context, alarmNoise);
                    }
                    if (mSongs.size() != 0) {
                        alarmNoise = mSongs.get(0);
                    } else {
                        // fallback
                        alarmNoise = null;
                        sLocalMediaMode = false;
                    }
                } catch (Exception ex) {
                    LogUtils.e("Error accessing media contents", ex);
                    // fallback
                    alarmNoise = null;
                    sLocalMediaMode = false;
                }
            }
        }
        if (alarmNoise == null) {
            LogUtils.e("Play default alarm");
            sPlayFallbackAlarm = true;
        } else if (AlarmInstance.NO_RINGTONE_URI.equals(alarmNoise)) {
            // silent
            alarmNoise = null;
        }
        boolean playSound = alarmNoise != null || sPlayFallbackAlarm;
        boolean vibrate = instance.mVibrate;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NotificationManager noMan = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            int filter = noMan.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                playSound = false;
                vibrate = false;
            }
        }
        if (playSound) {
            playAlarm(context, alarmNoise);
        }
        if (vibrate) {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.vibrate(sVibratePattern, 0);
        }
        sStarted = true;
    }

    private static void playAlarm(final Context context, final Uri alarmNoise) {

        sMediaPlayer = new MediaPlayer();
        sMediaPlayer.setOnErrorListener(new OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                LogUtils.e("Error playing " + alarmNoise);
                if (sLocalMediaMode || sRandomMusicMode) {
                    LogUtils.e("Skipping file");
                    mSongs.remove(alarmNoise);
                    nextSong(context);
                } else {
                    sPlayFallbackAlarm = true;
                    playAlarm(context, null);
                }
                return true;
            }
        });

        if (sLocalMediaMode || sRandomMusicMode) {
            sMediaPlayer.setOnCompletionListener(new OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    nextSong(context);
                }
            });
        }

        try {
            if (sPlayFallbackAlarm || alarmNoise == null) {
                LogUtils.e("Using the fallback ringtone");
                setDataSourceFromResource(context, sMediaPlayer, org.omnirom.deskclock.R.raw.fallbackring);
            } else {
                sMediaPlayer.setDataSource(context, alarmNoise);
                mCurrentTone = alarmNoise;
                LogUtils.v("next song:" + mCurrentTone);
            }
            startAlarm(context, sMediaPlayer);
        } catch (Exception ex) {
            LogUtils.e("Error playing " + alarmNoise, ex);
            if (sLocalMediaMode || sRandomMusicMode) {
                LogUtils.e("Skipping file");
                mSongs.remove(alarmNoise);
                nextSong(context);
            } else {
                LogUtils.e("Using the fallback ringtone");
                // The alarmNoise may be on the sd card which could be busy right
                // now. Use the fallback ringtone.
                try {
                    // Must reset the media player to clear the error state.
                    sMediaPlayer.reset();
                    setDataSourceFromResource(context, sMediaPlayer, org.omnirom.deskclock.R.raw.fallbackring);
                    startAlarm(context, sMediaPlayer);
                } catch (Exception ex2) {
                    // At this point we just don't play anything.
                    LogUtils.e("Failed to play fallback ringtone", ex2);
                }
            }
        }
    }

    // Do the common stuff when starting the alarm.
    private static void startAlarm(Context context, MediaPlayer player) throws IOException {
        // do not play alarms if alarm volume is 0
        // this can only happen if "use system alarm volume" is used
        if (sMaxVolume != 0) {
            // only start volume handling on the first invocation
            if (sFirstFile) {
                if (sIncreasingVolume) {
                    sCurrentVolume = INCREASING_VOLUME_START;
                    sAudioManager.setStreamVolume(getAudioStream(context),
                            sCurrentVolume, 0);
                    LogUtils.v("Starting alarm volume " + sCurrentVolume
                            + " max volume " + sMaxVolume);

                    if (sCurrentVolume < sMaxVolume) {
                        sHandler.sendEmptyMessageDelayed(INCREASING_VOLUME,
                                sVolumeIncreaseSpeed);
                    }
                } else {
                    sAudioManager.setStreamVolume(getAudioStream(context),
                            sMaxVolume, 0);
                    LogUtils.v("Alarm volume " + sMaxVolume);
                }
                sFirstFile = false;
            }

            LogUtils.v("Using audio stream " + (getAudioStream(context) == AudioManager.STREAM_MUSIC ? "Music" : "Alarm"));

            player.setAudioStreamType(getAudioStream(context));
            if (!sRandomMusicMode && !sLocalMediaMode) {
                player.setLooping(true);
            }
            player.prepare();
            sAudioManager.requestAudioFocus(null, getAudioStream(context),
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            player.start();
        }
    }

    private static void setDataSourceFromResource(Context context,
                                                  MediaPlayer player, int res) throws IOException {
        AssetFileDescriptor afd = context.getResources().openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
                    afd.getLength());
            afd.close();
        }
    }

    private static long getVolumeChangeDelay(Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String speed = prefs.getString(SettingsActivity.KEY_VOLUME_INCREASE_SPEED, "5");
        int speedInt = Integer.decode(speed).intValue();
        return speedInt * 1000;
    }

    /**
     * if we use the current alarm volume to play on the music stream
     * we must scale the alarm volume inside the music volume range
     */
    private static int calcNormalizedVolumeFromCurrentAlarm(Context context) {
        int alarmVolume = sAudioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        if (alarmVolume == 0) {
            return 0;
        }
        final int audioStream = getAudioStream(context);
        if (audioStream == AudioManager.STREAM_MUSIC) {
            int maxMusicVolume = sAudioManager.getStreamMaxVolume(audioStream);
            int maxAlarmVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            return (int) (((float) alarmVolume / (float) maxAlarmVolume) * (float) maxMusicVolume);
        }
        return alarmVolume;
    }

    /**
     * volume is stored based on music volume steps
     * so if we use the alarm stream to play we must scale the
     * volume inside the alarm volume range
     */
    private static int calcNormalizedVolume(Context context, int alarmVolume) {
        if (alarmVolume == -1) {
            // use system alarm volume calculated later
            return alarmVolume;
        }
        final int audioStream = getAudioStream(context);
        if (audioStream == AudioManager.STREAM_ALARM) {
            int maxMusicVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int maxAlarmVolume = sAudioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            return (int) (((float) alarmVolume / (float) maxMusicVolume) * (float) maxAlarmVolume) + 1;
        }
        return alarmVolume;
    }

    private static void nextSong(final Context context) {
        if (mSongs.size() == 0) {
            sRandomMusicMode = false;
            sLocalMediaMode = false;
            // something bad happend to our play list
            // just fall back to the default
            LogUtils.e("Using the fallback ringtone");
            sPlayFallbackAlarm = true;
            playAlarm(context, null);
            return;
        }
        sCurrentIndex++;
        // restart if on end
        if (sCurrentIndex >= mSongs.size()) {
            sCurrentIndex = 0;
            if (sRandomPlayback) {
                Collections.shuffle(mSongs);
            }
        }
        Uri song = mSongs.get(sCurrentIndex);
        playAlarm(context, song);
    }

    private static void collectFiles(Context context, Uri folderUri) {
        mSongs.clear();

        File folder = new File(folderUri.getPath());
        if (folder.exists() && folder.isDirectory()) {
            for (final File fileEntry : folder.listFiles()) {
                if (!fileEntry.isDirectory()) {
                    if (Utils.isValidAudioFile(fileEntry.getName())) {
                        mSongs.add(Uri.fromFile(fileEntry));
                    }
                } else {
                    collectSub(context, fileEntry);
                }
            }
            if (sRandomPlayback) {
                Collections.shuffle(mSongs);
            } else {
                Collections.sort(mSongs);
            }
        }
    }

    private static void collectSub(Context context, File folder) {
        if (folder.exists() && folder.isDirectory()) {
            for (final File fileEntry : folder.listFiles()) {
                if (!fileEntry.isDirectory()) {
                    if (Utils.isValidAudioFile(fileEntry.getName())) {
                        mSongs.add(Uri.fromFile(fileEntry));
                    }
                } else {
                    collectSub(context, fileEntry);
                }
            }
        }
    }

    private static void collectAlbumSongs(Context context, Uri albumUri) {
        mSongs.clear();
        mSongs = Utils.getAlbumSongs(context, albumUri);
        if (sRandomPlayback) {
            Collections.shuffle(mSongs);
        }
    }

    private static void collectArtistSongs(Context context, Uri artistUri) {
        mSongs.clear();
        mSongs = Utils.getArtistSongs(context, artistUri);
        if (sRandomPlayback) {
            Collections.shuffle(mSongs);
        }
    }
}
