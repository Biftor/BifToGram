/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ClearCacheService;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.query.BotQuery;
import org.telegram.messenger.support.widget.LinearLayoutManager;
import org.telegram.messenger.support.widget.RecyclerView;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;

public class CacheControlActivity extends BaseFragment {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int databaseRow;
    private int databaseInfoRow;
    private int keepMediaRow;
    private int keepMediaInfoRow;
    private int cacheRow;
    private int cacheInfoRow;
    private int rowCount;

    private long databaseSize = -1;
    private long cacheSize = -1;
    private long documentsSize = -1;
    private long audioSize = -1;
    private long musicSize = -1;
    private long photoSize = -1;
    private long videoSize = -1;
    private long totalSize = -1;
    private boolean clear[] = new boolean[6];
    private boolean calculating = true;

    private volatile boolean canceled = false;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        rowCount = 0;
        keepMediaRow = rowCount++;
        keepMediaInfoRow = rowCount++;
        cacheRow = rowCount++;
        cacheInfoRow = rowCount++;

        databaseRow = rowCount++;
        databaseInfoRow = rowCount++;

        File file = new File(ApplicationLoader.getFilesDirFixed(), "cache4.db");
        databaseSize = file.length();

        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                cacheSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_CACHE), 0);
                if (canceled) {
                    return;
                }
                photoSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_IMAGE), 0);
                if (canceled) {
                    return;
                }
                videoSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_VIDEO), 0);
                if (canceled) {
                    return;
                }
                documentsSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_DOCUMENT), 1);
                if (canceled) {
                    return;
                }
                musicSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_DOCUMENT), 2);
                if (canceled) {
                    return;
                }
                audioSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_AUDIO), 0);
                totalSize = cacheSize + videoSize + audioSize + photoSize + documentsSize + musicSize;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        calculating = false;
                        if (listAdapter != null) {
                            listAdapter.notifyDataSetChanged();
                        }
                    }
                });
            }
        });

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        canceled = true;
    }

    private long getDirectorySize(File dir, int documentsMusicType) {
        if (dir == null || canceled) {
            return 0;
        }
        long size = 0;
        if (dir.isDirectory()) {
            try {
                File[] array = dir.listFiles();
                if (array != null) {
                    for (int a = 0; a < array.length; a++) {
                        if (canceled) {
                            return 0;
                        }
                        File file = array[a];
                        if (documentsMusicType == 1 || documentsMusicType == 2) {
                            String name = file.getName().toLowerCase();
                            if (name.endsWith(".mp3") || name.endsWith(".m4a")) {
                                if (documentsMusicType == 1) {
                                    continue;
                                }
                            } else if (documentsMusicType == 2) {
                                continue;
                            }
                        }
                        if (file.isDirectory()) {
                            size += getDirectorySize(file, documentsMusicType);
                        } else {
                            size += file.length();
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        } else if (dir.isFile()) {
            size += dir.length();
        }
        return size;
    }

    private void cleanupFolders() {
        final AlertDialog progressDialog = new AlertDialog(getParentActivity(), 1);
        progressDialog.setMessage(LocaleController.getString("Loading", R.string.Loading));
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setCancelable(false);
        progressDialog.show();
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                boolean imagesCleared = false;
                for (int a = 0; a < 6; a++) {
                    if (!clear[a]) {
                        continue;
                    }
                    int type = -1;
                    int documentsMusicType = 0;
                    if (a == 0) {
                        type = FileLoader.MEDIA_DIR_IMAGE;
                    } else if (a == 1) {
                        type = FileLoader.MEDIA_DIR_VIDEO;
                    } else if (a == 2) {
                        type = FileLoader.MEDIA_DIR_DOCUMENT;
                        documentsMusicType = 1;
                    } else if (a == 3) {
                        type = FileLoader.MEDIA_DIR_DOCUMENT;
                        documentsMusicType = 2;
                    } else if (a == 4) {
                        type = FileLoader.MEDIA_DIR_AUDIO;
                    } else if (a == 5) {
                        type = FileLoader.MEDIA_DIR_CACHE;
                    }
                    if (type == -1) {
                        continue;
                    }
                    File file = FileLoader.getInstance().checkDirectory(type);
                    if (file != null) {
                        try {
                            File[] array = file.listFiles();
                            if (array != null) {
                                for (int b = 0; b < array.length; b++) {
                                    String name = array[b].getName().toLowerCase();
                                    if (documentsMusicType == 1 || documentsMusicType == 2) {
                                        if (name.endsWith(".mp3") || name.endsWith(".m4a")) {
                                            if (documentsMusicType == 1) {
                                                continue;
                                            }
                                        } else if (documentsMusicType == 2) {
                                            continue;
                                        }
                                    }
                                    if (name.equals(".nomedia")) {
                                        continue;
                                    }
                                    if (array[b].isFile()) {
                                        array[b].delete();
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                    if (type == FileLoader.MEDIA_DIR_CACHE) {
                        cacheSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_CACHE), documentsMusicType);
                        imagesCleared = true;
                    } else if (type == FileLoader.MEDIA_DIR_AUDIO) {
                        audioSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_AUDIO), documentsMusicType);
                    } else if (type == FileLoader.MEDIA_DIR_DOCUMENT) {
                        if (documentsMusicType == 1) {
                            documentsSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_DOCUMENT), documentsMusicType);
                        } else {
                            musicSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_DOCUMENT), documentsMusicType);
                        }
                    } else if (type == FileLoader.MEDIA_DIR_IMAGE) {
                        imagesCleared = true;
                        photoSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_IMAGE), documentsMusicType);
                    } else if (type == FileLoader.MEDIA_DIR_VIDEO) {
                        videoSize = getDirectorySize(FileLoader.getInstance().checkDirectory(FileLoader.MEDIA_DIR_VIDEO), documentsMusicType);
                    }
                }
                final boolean imagesClearedFinal = imagesCleared;
                totalSize = cacheSize + videoSize + audioSize + photoSize + documentsSize + musicSize;
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (imagesClearedFinal) {
                            ImageLoader.getInstance().clearMemory();
                        }
                        if (listAdapter != null) {
                            listAdapter.notifyDataSetChanged();
                        }
                        try {
                            progressDialog.dismiss();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
            }
        });
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString("StorageUsage", R.string.StorageUsage));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                if (getParentActivity() == null) {
                    return;
                }
                if (position == keepMediaRow) {
                    BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
                    builder.setItems(new CharSequence[]{LocaleController.formatPluralString("Days", 3), LocaleController.formatPluralString("Weeks", 1), LocaleController.formatPluralString("Months", 1), LocaleController.getString("KeepMediaForever", R.string.KeepMediaForever)}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, final int which) {
                            SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit();
                            if (which == 0) {
                                editor.putInt("keep_media", 3).commit();
                            } else if (which == 1) {
                                editor.putInt("keep_media", 0).commit();
                            } else if (which == 2) {
                                editor.putInt("keep_media", 1).commit();
                            } else if (which == 3) {
                                editor.putInt("keep_media", 2).commit();
                            }
                            if (listAdapter != null) {
                                listAdapter.notifyDataSetChanged();
                            }
                            PendingIntent pintent = PendingIntent.getService(ApplicationLoader.applicationContext, 0, new Intent(ApplicationLoader.applicationContext, ClearCacheService.class), 0);
                            AlarmManager alarmManager = (AlarmManager) ApplicationLoader.applicationContext.getSystemService(Context.ALARM_SERVICE);
                            if (which == 2) {
                                alarmManager.cancel(pintent);
                            } else {
                                alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, AlarmManager.INTERVAL_DAY, AlarmManager.INTERVAL_DAY, pintent);
                            }
                        }
                    });
                    showDialog(builder.create());
                } else if (position == databaseRow) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
                    builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                    builder.setMessage(LocaleController.getString("LocalDatabaseClear", R.string.LocalDatabaseClear));
                    builder.setPositiveButton(LocaleController.getString("CacheClear", R.string.CacheClear), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            final AlertDialog progressDialog = new AlertDialog(getParentActivity(), 1);
                            progressDialog.setMessage(LocaleController.getString("Loading", R.string.Loading));
                            progressDialog.setCanceledOnTouchOutside(false);
                            progressDialog.setCancelable(false);
                            progressDialog.show();
                            MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        SQLiteDatabase database = MessagesStorage.getInstance().getDatabase();
                                        ArrayList<Long> dialogsToCleanup = new ArrayList<>();
                                        SQLiteCursor cursor = database.queryFinalized("SELECT did FROM dialogs WHERE 1");
                                        StringBuilder ids = new StringBuilder();
                                        while (cursor.next()) {
                                            long did = cursor.longValue(0);
                                            int lower_id = (int) did;
                                            int high_id = (int) (did >> 32);
                                            if (lower_id != 0 && high_id != 1) {
                                                dialogsToCleanup.add(did);
                                            }
                                        }
                                        cursor.dispose();

                                        SQLitePreparedStatement state5 = database.executeFast("REPLACE INTO messages_holes VALUES(?, ?, ?)");
                                        SQLitePreparedStatement state6 = database.executeFast("REPLACE INTO media_holes_v2 VALUES(?, ?, ?, ?)");

                                        database.beginTransaction();
                                        for (int a = 0; a < dialogsToCleanup.size(); a++) {
                                            Long did = dialogsToCleanup.get(a);
                                            int messagesCount = 0;
                                            cursor = database.queryFinalized("SELECT COUNT(mid) FROM messages WHERE uid = " + did);
                                            if (cursor.next()) {
                                                messagesCount = cursor.intValue(0);
                                            }
                                            cursor.dispose();
                                            if (messagesCount <= 2) {
                                                continue;
                                            }

                                            cursor = database.queryFinalized("SELECT last_mid_i, last_mid FROM dialogs WHERE did = " + did);
                                            int messageId = -1;
                                            if (cursor.next()) {
                                                long last_mid_i = cursor.longValue(0);
                                                long last_mid = cursor.longValue(1);
                                                SQLiteCursor cursor2 = database.queryFinalized("SELECT data FROM messages WHERE uid = " + did + " AND mid IN (" + last_mid_i + "," + last_mid + ")");
                                                try {
                                                    while (cursor2.next()) {
                                                        NativeByteBuffer data = cursor2.byteBufferValue(0);
                                                        if (data != null) {
                                                            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                                            data.reuse();
                                                            if (message != null) {
                                                                messageId = message.id;
                                                            }
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    FileLog.e(e);
                                                }
                                                cursor2.dispose();

                                                database.executeFast("DELETE FROM messages WHERE uid = " + did + " AND mid != " + last_mid_i + " AND mid != " + last_mid).stepThis().dispose();
                                                database.executeFast("DELETE FROM messages_holes WHERE uid = " + did).stepThis().dispose();
                                                database.executeFast("DELETE FROM bot_keyboard WHERE uid = " + did).stepThis().dispose();
                                                database.executeFast("DELETE FROM media_counts_v2 WHERE uid = " + did).stepThis().dispose();
                                                database.executeFast("DELETE FROM media_v2 WHERE uid = " + did).stepThis().dispose();
                                                database.executeFast("DELETE FROM media_holes_v2 WHERE uid = " + did).stepThis().dispose();
                                                BotQuery.clearBotKeyboard(did, null);
                                                if (messageId != -1) {
                                                    MessagesStorage.createFirstHoles(did, state5, state6, messageId);
                                                }
                                            }
                                            cursor.dispose();
                                        }
                                        state5.dispose();
                                        state6.dispose();
                                        database.commitTransaction();
                                        database.executeFast("VACUUM").stepThis().dispose();
                                    } catch (Exception e) {
                                        FileLog.e(e);
                                    } finally {
                                        AndroidUtilities.runOnUIThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    progressDialog.dismiss();
                                                } catch (Exception e) {
                                                    FileLog.e(e);
                                                }
                                                if (listAdapter != null) {
                                                    File file = new File(ApplicationLoader.getFilesDirFixed(), "cache4.db");
                                                    databaseSize = file.length();
                                                    listAdapter.notifyDataSetChanged();
                                                }
                                            }
                                        });
                                    }
                                }
                            });
                        }
                    });
                    showDialog(builder.create());
                } else if (position == cacheRow) {
                    if (totalSize <= 0 || getParentActivity() == null) {
                        return;
                    }
                    BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
                    builder.setApplyTopPadding(false);
                    builder.setApplyBottomPadding(false);
                    LinearLayout linearLayout = new LinearLayout(getParentActivity());
                    linearLayout.setOrientation(LinearLayout.VERTICAL);
                    for (int a = 0; a < 6; a++) {
                        long size = 0;
                        String name = null;
                        if (a == 0) {
                            size = photoSize;
                            name = LocaleController.getString("LocalPhotoCache", R.string.LocalPhotoCache);
                        } else if (a == 1) {
                            size = videoSize;
                            name = LocaleController.getString("LocalVideoCache", R.string.LocalVideoCache);
                        } else if (a == 2) {
                            size = documentsSize;
                            name = LocaleController.getString("LocalDocumentCache", R.string.LocalDocumentCache);
                        } else if (a == 3) {
                            size = musicSize;
                            name = LocaleController.getString("LocalMusicCache", R.string.LocalMusicCache);
                        } else if (a == 4) {
                            size = audioSize;
                            name = LocaleController.getString("LocalAudioCache", R.string.LocalAudioCache);
                        } else if (a == 5) {
                            size = cacheSize;
                            name = LocaleController.getString("LocalCache", R.string.LocalCache);
                        }
                        if (size > 0) {
                            clear[a] = true;
                            CheckBoxCell checkBoxCell = new CheckBoxCell(getParentActivity(), true);
                            checkBoxCell.setTag(a);
                            checkBoxCell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
                            linearLayout.addView(checkBoxCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
                            checkBoxCell.setText(name, AndroidUtilities.formatFileSize(size), true, true);
                            checkBoxCell.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                            checkBoxCell.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    CheckBoxCell cell = (CheckBoxCell) v;
                                    int num = (Integer) cell.getTag();
                                    clear[num] = !clear[num];
                                    cell.setChecked(clear[num], true);
                                }
                            });
                        } else {
                            clear[a] = false;
                        }
                    }
                    BottomSheet.BottomSheetCell cell = new BottomSheet.BottomSheetCell(getParentActivity(), 1);
                    cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
                    cell.setTextAndIcon(LocaleController.getString("ClearMediaCache", R.string.ClearMediaCache).toUpperCase(), 0);
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteRedText));
                    cell.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                if (visibleDialog != null) {
                                    visibleDialog.dismiss();
                                }
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                            cleanupFolders();
                        }
                    });
                    linearLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
                    builder.setCustomView(linearLayout);
                    showDialog(builder.create());
                }
            }
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == databaseRow || position == cacheRow && totalSize > 0 || position == keepMediaRow;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                default:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0:
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    if (position == databaseRow) {
                        textCell.setTextAndValue(LocaleController.getString("LocalDatabase", R.string.LocalDatabase), AndroidUtilities.formatFileSize(databaseSize), false);
                    } else if (position == cacheRow) {
                        if (calculating) {
                            textCell.setTextAndValue(LocaleController.getString("ClearMediaCache", R.string.ClearMediaCache), LocaleController.getString("CalculatingSize", R.string.CalculatingSize), false);
                        } else {
                            textCell.setTextAndValue(LocaleController.getString("ClearMediaCache", R.string.ClearMediaCache), totalSize == 0 ? LocaleController.getString("CacheEmpty", R.string.CacheEmpty) : AndroidUtilities.formatFileSize(totalSize), false);
                        }
                    } else if (position == keepMediaRow) {
                        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                        int keepMedia = preferences.getInt("keep_media", 2);
                        String value;
                        if (keepMedia == 0) {
                            value = LocaleController.formatPluralString("Weeks", 1);
                        } else if (keepMedia == 1) {
                            value = LocaleController.formatPluralString("Months", 1);
                        } else if (keepMedia == 3) {
                            value = LocaleController.formatPluralString("Days", 3);
                        } else {
                            value = LocaleController.getString("KeepMediaForever", R.string.KeepMediaForever);
                        }
                        textCell.setTextAndValue(LocaleController.getString("KeepMedia", R.string.KeepMedia), value, false);
                    }
                    break;
                case 1:
                    TextInfoPrivacyCell privacyCell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == databaseInfoRow) {
                        privacyCell.setText(LocaleController.getString("LocalDatabaseInfo", R.string.LocalDatabaseInfo));
                        privacyCell.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == cacheInfoRow) {
                        privacyCell.setText("");
                        privacyCell.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == keepMediaInfoRow) {
                        privacyCell.setText(AndroidUtilities.replaceTags(LocaleController.getString("KeepMediaInfo", R.string.KeepMediaInfo)));
                        privacyCell.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int i) {
            if (i == databaseInfoRow || i == cacheInfoRow || i == keepMediaInfoRow) {
                return 1;
            }
            return 0;
        }
    }

    @Override
    public ThemeDescription[] getThemeDescriptions() {
        return new ThemeDescription[]{
                new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite),
                new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray),

                new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault),
                new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle),
                new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector),

                new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector),

                new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText),
                new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText),

                new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow),
                new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4),
        };
    }
}
