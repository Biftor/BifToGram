/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class StatsController {

    public static final int TYPE_MOBILE = 0;
    public static final int TYPE_WIFI = 1;
    public static final int TYPE_ROAMING = 2;

    public static final int TYPE_CALLS = 0;
    public static final int TYPE_MESSAGES = 1;
    public static final int TYPE_VIDEOS = 2;
    public static final int TYPE_AUDIOS = 3;
    public static final int TYPE_PHOTOS = 4;
    public static final int TYPE_FILES = 5;
    public static final int TYPE_TOTAL = 6;
    private static final int TYPES_COUNT = 7;

    private long sentBytes[][] = new long[3][TYPES_COUNT];
    private long receivedBytes[][] = new long[3][TYPES_COUNT];
    private int sentItems[][] = new int[3][TYPES_COUNT];
    private int receivedItems[][] = new int[3][TYPES_COUNT];
    private long resetStatsDate[] = new long[3];
    private int callsTotalTime[] = new int[3];
    private SharedPreferences.Editor editor;
    private DispatchQueue statsSaveQueue = new DispatchQueue("statsSaveQueue");

    private static final ThreadLocal<Long> lastStatsSaveTime = new ThreadLocal<Long>() {
        @Override
        protected Long initialValue() {
            return System.currentTimeMillis() - 1000;
        }
    };

    private static volatile StatsController Instance = null;

    public static StatsController getInstance() {
        StatsController localInstance = Instance;
        if (localInstance == null) {
            synchronized (StatsController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new StatsController();
                }
            }
        }
        return localInstance;
    }

    private StatsController() {
        SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE);
        boolean save = false;
        editor = sharedPreferences.edit();
        for (int a = 0; a < 3; a++) {
            callsTotalTime[a] = sharedPreferences.getInt("callsTotalTime" + a, 0);
            resetStatsDate[a] = sharedPreferences.getLong("resetStatsDate" + a, 0);
            for (int b = 0; b < TYPES_COUNT; b++) {
                sentBytes[a][b] = sharedPreferences.getLong("sentBytes" + a + "_" + b, 0);
                receivedBytes[a][b] = sharedPreferences.getLong("receivedBytes" + a + "_" + b, 0);
                sentItems[a][b] = sharedPreferences.getInt("sentItems" + a + "_" + b, 0);
                receivedItems[a][b] = sharedPreferences.getInt("receivedItems" + a + "_" + b, 0);
            }
            if (resetStatsDate[a] == 0) {
                save = true;
                resetStatsDate[a] = System.currentTimeMillis();
            }
        }
        if (save) {
            saveStats();
        }
    }

    public void incrementReceivedItemsCount(int networkType, int dataType, int value) {
        receivedItems[networkType][dataType] += value;
        saveStats();
    }

    public void incrementSentItemsCount(int networkType, int dataType, int value) {
        sentItems[networkType][dataType] += value;
        saveStats();
    }

    public void incrementReceivedBytesCount(int networkType, int dataType, long value) {
        receivedBytes[networkType][dataType] += value;
        saveStats();
    }

    public void incrementSentBytesCount(int networkType, int dataType, long value) {
        sentBytes[networkType][dataType] += value;
        saveStats();
    }

    public void incrementTotalCallsTime(int networkType, int value) {
        callsTotalTime[networkType] += value;
        saveStats();
    }

    public int getRecivedItemsCount(int networkType, int dataType) {
        return receivedItems[networkType][dataType];
    }

    public int getSentItemsCount(int networkType, int dataType) {
        return sentItems[networkType][dataType];
    }

    public long getSentBytesCount(int networkType, int dataType) {
        if (dataType == TYPE_MESSAGES) {
            return sentBytes[networkType][TYPE_TOTAL] - sentBytes[networkType][TYPE_FILES] - sentBytes[networkType][TYPE_AUDIOS] - sentBytes[networkType][TYPE_VIDEOS] - sentBytes[networkType][TYPE_PHOTOS];
        }
        return sentBytes[networkType][dataType];
    }

    public long getReceivedBytesCount(int networkType, int dataType) {
        if (dataType == TYPE_MESSAGES) {
            return receivedBytes[networkType][TYPE_TOTAL] - receivedBytes[networkType][TYPE_FILES] - receivedBytes[networkType][TYPE_AUDIOS] - receivedBytes[networkType][TYPE_VIDEOS] - receivedBytes[networkType][TYPE_PHOTOS];
        }
        return receivedBytes[networkType][dataType];
    }

    public int getCallsTotalTime(int networkType) {
        return callsTotalTime[networkType];
    }

    public long getResetStatsDate(int networkType) {
        return resetStatsDate[networkType];
    }

    public void resetStats(int networkType) {
        resetStatsDate[networkType] = System.currentTimeMillis();
        for (int a = 0; a < TYPES_COUNT; a++) {
            sentBytes[networkType][a] = 0;
            receivedBytes[networkType][a] = 0;
            sentItems[networkType][a] = 0;
            receivedItems[networkType][a] = 0;
        }
        callsTotalTime[networkType] = 0;
        saveStats();
    }

    private void saveStats() {
        long newTime = System.currentTimeMillis();
        if (Math.abs(newTime - lastStatsSaveTime.get()) >= 1000) {
            lastStatsSaveTime.set(newTime);
            statsSaveQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    for (int networkType = 0; networkType < 3; networkType++) {
                        for (int a = 0; a < TYPES_COUNT; a++) {
                            editor.putInt("receivedItems" + networkType + "_" + a, receivedItems[networkType][a]);
                            editor.putInt("sentItems" + networkType + "_" + a, sentItems[networkType][a]);
                            editor.putLong("receivedBytes" + networkType + "_" + a, receivedBytes[networkType][a]);
                            editor.putLong("sentBytes" + networkType + "_" + a, sentBytes[networkType][a]);
                        }
                        editor.putInt("callsTotalTime" + networkType, callsTotalTime[networkType]);
                        editor.putLong("resetStatsDate" + networkType, resetStatsDate[networkType]);
                    }
                    try {
                        editor.apply();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            });
        }
    }
}
