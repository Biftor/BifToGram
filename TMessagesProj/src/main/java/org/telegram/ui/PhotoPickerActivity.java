/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.support.widget.GridLayoutManager;
import org.telegram.messenger.support.widget.RecyclerView;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.PhotoPickerPhotoCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PickerBottomLayout;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class PhotoPickerActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    public interface PhotoPickerActivityDelegate {
        void selectedPhotosChanged();
        void actionButtonPressed(boolean canceled);
    }

    private int type;
    private HashMap<Object, Object> selectedPhotos;
    private ArrayList<Object> selectedPhotosOrder;
    private boolean allowIndices;

    private ArrayList<MediaController.SearchImage> recentImages;
    private ArrayList<MediaController.SearchImage> searchResult = new ArrayList<>();
    private HashMap<String, MediaController.SearchImage> searchResultKeys = new HashMap<>();
    private HashMap<String, MediaController.SearchImage> searchResultUrls = new HashMap<>();

    private TextView hintTextView;
    private Runnable hintHideRunnable;
    private AnimatorSet hintAnimation;

    private boolean searching;
    private boolean bingSearchEndReached = true;
    private boolean giphySearchEndReached = true;
    private String lastSearchString;
    private boolean loadingRecent;
    private int nextGiphySearchOffset;
    private int giphyReqId;
    private int lastSearchToken;
    private boolean allowCaption = true;
    private AsyncTask<Void, Void, JSONObject> currentBingTask;

    private MediaController.AlbumEntry selectedAlbum;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private GridLayoutManager layoutManager;
    private PickerBottomLayout pickerBottomLayout;
    private ImageView imageOrderToggleButton;
    private EmptyTextProgressView emptyView;
    private ActionBarMenuItem searchItem;
    private FrameLayout frameLayout;
    private int itemWidth = 100;
    private boolean sendPressed;
    private boolean singlePhoto;
    private ChatActivity chatActivity;

    private PhotoPickerActivityDelegate delegate;

    private PhotoViewer.PhotoViewerProvider provider = new PhotoViewer.EmptyPhotoViewerProvider() {
        @Override
        public boolean scaleToFill() {
            return false;
        }

        @Override
        public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
            PhotoPickerPhotoCell cell = getCellForIndex(index);
            if (cell != null) {
                int coords[] = new int[2];
                cell.photoImage.getLocationInWindow(coords);
                PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
                object.viewX = coords[0];
                object.viewY = coords[1] - (Build.VERSION.SDK_INT >= 21 ? 0 : AndroidUtilities.statusBarHeight);
                object.parentView = listView;
                object.imageReceiver = cell.photoImage.getImageReceiver();
                object.thumb = object.imageReceiver.getBitmap();
                object.scale = cell.photoImage.getScaleX();
                cell.showCheck(false);
                return object;
            }
            return null;
        }

        @Override
        public void updatePhotoAtIndex(int index) {
            PhotoPickerPhotoCell cell = getCellForIndex(index);
            if (cell != null) {
                if (selectedAlbum != null) {
                    cell.photoImage.setOrientation(0, true);
                    MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                    if (photoEntry.thumbPath != null) {
                        cell.photoImage.setImage(photoEntry.thumbPath, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                    } else if (photoEntry.path != null) {
                        cell.photoImage.setOrientation(photoEntry.orientation, true);
                        if (photoEntry.isVideo) {
                            cell.photoImage.setImage("vthumb://" + photoEntry.imageId + ":" + photoEntry.path, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                        } else {
                            cell.photoImage.setImage("thumb://" + photoEntry.imageId + ":" + photoEntry.path, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                        }
                    } else {
                        cell.photoImage.setImageResource(R.drawable.nophotos);
                    }
                } else {
                    ArrayList<MediaController.SearchImage> array;
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        array = recentImages;
                    } else {
                        array = searchResult;
                    }
                    MediaController.SearchImage photoEntry = array.get(index);
                    if (photoEntry.document != null && photoEntry.document.thumb != null) {
                        cell.photoImage.setImage(photoEntry.document.thumb.location, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                    } else if (photoEntry.thumbPath != null) {
                        cell.photoImage.setImage(photoEntry.thumbPath, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                    } else if (photoEntry.thumbUrl != null && photoEntry.thumbUrl.length() > 0) {
                        cell.photoImage.setImage(photoEntry.thumbUrl, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                    } else {
                        cell.photoImage.setImageResource(R.drawable.nophotos);
                    }
                }
            }
        }

        @Override
        public boolean allowCaption() {
            return allowCaption;
        }

        @Override
        public Bitmap getThumbForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
            PhotoPickerPhotoCell cell = getCellForIndex(index);
            if (cell != null) {
                return cell.photoImage.getImageReceiver().getBitmap();
            }
            return null;
        }

        @Override
        public void willSwitchFromPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View view = listView.getChildAt(a);
                if (view.getTag() == null) {
                    continue;
                }
                PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
                int num = (Integer) view.getTag();
                if (selectedAlbum != null) {
                    if (num < 0 || num >= selectedAlbum.photos.size()) {
                        continue;
                    }
                } else {
                    ArrayList<MediaController.SearchImage> array;
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        array = recentImages;
                    } else {
                        array = searchResult;
                    }
                    if (num < 0 || num >= array.size()) {
                        continue;
                    }
                }
                if (num == index) {
                    cell.showCheck(true);
                    break;
                }
            }
        }

        @Override
        public void willHidePhotoViewer() {
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View view = listView.getChildAt(a);
                if (view instanceof PhotoPickerPhotoCell) {
                    PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
                    cell.showCheck(true);
                }
            }
        }

        @Override
        public boolean isPhotoChecked(int index) {
            if (selectedAlbum != null) {
                return !(index < 0 || index >= selectedAlbum.photos.size()) && selectedPhotos.containsKey(selectedAlbum.photos.get(index).imageId);
            } else {
                ArrayList<MediaController.SearchImage> array;
                if (searchResult.isEmpty() && lastSearchString == null) {
                    array = recentImages;
                } else {
                    array = searchResult;
                }
                return !(index < 0 || index >= array.size()) && selectedPhotos.containsKey(array.get(index).id);
            }
        }

        @Override
        public int setPhotoChecked(int index, VideoEditedInfo videoEditedInfo) {
            boolean add = true;
            int num;
            if (selectedAlbum != null) {
                if (index < 0 || index >= selectedAlbum.photos.size()) {
                    return -1;
                }
                MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                if ((num = addToSelectedPhotos(photoEntry, -1)) == -1) {
                    photoEntry.editedInfo = videoEditedInfo;
                    num = selectedPhotosOrder.indexOf(photoEntry.imageId);
                } else {
                    add = false;
                    photoEntry.editedInfo = null;
                }
            } else {
                ArrayList<MediaController.SearchImage> array;
                if (searchResult.isEmpty() && lastSearchString == null) {
                    array = recentImages;
                } else {
                    array = searchResult;
                }
                if (index < 0 || index >= array.size()) {
                    return -1;
                }
                MediaController.SearchImage photoEntry = array.get(index);
                if ((num = addToSelectedPhotos(photoEntry, -1)) == -1) {
                    num = selectedPhotosOrder.indexOf(photoEntry.id);
                } else {
                    add = false;
                }
            }
            int count = listView.getChildCount();
            for (int a = 0; a < count; a++) {
                View view = listView.getChildAt(a);
                int tag = (Integer) view.getTag();
                if (tag == index) {
                    ((PhotoPickerPhotoCell) view).setChecked(allowIndices ? num : -1, add, false);
                    break;
                }
            }
            pickerBottomLayout.updateSelectedCount(selectedPhotos.size(), true);
            delegate.selectedPhotosChanged();
            return num;
        }

        @Override
        public boolean cancelButtonPressed() {
            delegate.actionButtonPressed(true);
            finishFragment();
            return true;
        }

        @Override
        public int getSelectedCount() {
            return selectedPhotos.size();
        }

        @Override
        public void sendButtonPressed(int index, VideoEditedInfo videoEditedInfo) {
            if (selectedPhotos.isEmpty()) {
                if (selectedAlbum != null) {
                    if (index < 0 || index >= selectedAlbum.photos.size()) {
                        return;
                    }
                    MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                    photoEntry.editedInfo = videoEditedInfo;
                    addToSelectedPhotos(photoEntry, -1);
                } else {
                    ArrayList<MediaController.SearchImage> array;
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        array = recentImages;
                    } else {
                        array = searchResult;
                    }
                    if (index < 0 || index >= array.size()) {
                        return;
                    }
                    addToSelectedPhotos(array.get(index), -1);
                }
            }
            sendSelectedPhotos();
        }

        @Override
        public void toggleGroupPhotosEnabled() {
            if (imageOrderToggleButton != null) {
                imageOrderToggleButton.setColorFilter(MediaController.getInstance().isGroupPhotosEnabled() ? new PorterDuffColorFilter(0xff66bffa, PorterDuff.Mode.MULTIPLY) : null);
            }
        }

        @Override
        public ArrayList<Object> getSelectedPhotosOrder() {
            return selectedPhotosOrder;
        }

        @Override
        public HashMap<Object, Object> getSelectedPhotos() {
            return selectedPhotos;
        }

        @Override
        public boolean allowGroupPhotos() {
            return imageOrderToggleButton != null;
        }
    };

    public PhotoPickerActivity(int type, MediaController.AlbumEntry selectedAlbum, HashMap<Object, Object> selectedPhotos, ArrayList<Object> selectedPhotosOrder, ArrayList<MediaController.SearchImage> recentImages, boolean onlyOnePhoto, boolean allowCaption, ChatActivity chatActivity) {
        super();
        this.selectedAlbum = selectedAlbum;
        this.selectedPhotos = selectedPhotos;
        this.selectedPhotosOrder = selectedPhotosOrder;
        this.type = type;
        this.recentImages = recentImages;
        this.singlePhoto = onlyOnePhoto;
        this.chatActivity = chatActivity;
        this.allowCaption = allowCaption;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.recentImagesDidLoaded);
        if (selectedAlbum == null) {
            if (recentImages.isEmpty()) {
                MessagesStorage.getInstance().loadWebRecent(type);
                loadingRecent = true;
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.recentImagesDidLoaded);
        if (currentBingTask != null) {
            currentBingTask.cancel(true);
            currentBingTask = null;
        }
        if (giphyReqId != 0) {
            ConnectionsManager.getInstance().cancelRequest(giphyReqId, true);
            giphyReqId = 0;
        }
        super.onFragmentDestroy();
    }

    @SuppressWarnings("unchecked")
    @Override
    public View createView(Context context) {
        actionBar.setBackgroundColor(Theme.ACTION_BAR_MEDIA_PICKER_COLOR);
        actionBar.setItemsBackgroundColor(Theme.ACTION_BAR_PICKER_SELECTOR_COLOR, false);
        actionBar.setTitleColor(0xffffffff);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        if (selectedAlbum != null) {
            actionBar.setTitle(selectedAlbum.bucketName);
        } else if (type == 0) {
            actionBar.setTitle(LocaleController.getString("SearchImagesTitle", R.string.SearchImagesTitle));
        } else if (type == 1) {
            actionBar.setTitle(LocaleController.getString("SearchGifsTitle", R.string.SearchGifsTitle));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        if (selectedAlbum == null) {
            ActionBarMenu menu = actionBar.createMenu();
            searchItem = menu.addItem(0, R.drawable.ic_ab_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchExpand() {

                }

                @Override
                public boolean canCollapseSearch() {
                    finishFragment();
                    return false;
                }

                @Override
                public void onTextChanged(EditText editText) {
                    if (editText.getText().length() == 0) {
                        searchResult.clear();
                        searchResultKeys.clear();
                        lastSearchString = null;
                        bingSearchEndReached = true;
                        giphySearchEndReached = true;
                        searching = false;
                        if (currentBingTask != null) {
                            currentBingTask.cancel(true);
                            currentBingTask = null;
                        }
                        if (giphyReqId != 0) {
                            ConnectionsManager.getInstance().cancelRequest(giphyReqId, true);
                            giphyReqId = 0;
                        }
                        if (type == 0) {
                            emptyView.setText(LocaleController.getString("NoRecentPhotos", R.string.NoRecentPhotos));
                        } else if (type == 1) {
                            emptyView.setText(LocaleController.getString("NoRecentGIFs", R.string.NoRecentGIFs));
                        }
                        updateSearchInterface();
                    }
                }

                @Override
                public void onSearchPressed(EditText editText) {
                    if (editText.getText().toString().length() == 0) {
                        return;
                    }
                    searchResult.clear();
                    searchResultKeys.clear();
                    bingSearchEndReached = true;
                    giphySearchEndReached = true;
                    if (type == 0) {
                        searchBingImages(editText.getText().toString(), 0, 53);
                    } else if (type == 1) {
                        nextGiphySearchOffset = 0;
                        searchGiphyImages(editText.getText().toString(), 0);
                    }
                    lastSearchString = editText.getText().toString();
                    if (lastSearchString.length() == 0) {
                        lastSearchString = null;
                        if (type == 0) {
                            emptyView.setText(LocaleController.getString("NoRecentPhotos", R.string.NoRecentPhotos));
                        } else if (type == 1) {
                            emptyView.setText(LocaleController.getString("NoRecentGIFs", R.string.NoRecentGIFs));
                        }
                    } else {
                        emptyView.setText(LocaleController.getString("NoResult", R.string.NoResult));
                    }
                    updateSearchInterface();
                }
            });
        }

        if (selectedAlbum == null) {
            if (type == 0) {
                searchItem.getSearchField().setHint(LocaleController.getString("SearchImagesTitle", R.string.SearchImagesTitle));
            } else if (type == 1) {
                searchItem.getSearchField().setHint(LocaleController.getString("SearchGifsTitle", R.string.SearchGifsTitle));
            }
        }

        fragmentView = new FrameLayout(context);

        frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(0xff000000);

        listView = new RecyclerListView(context);
        listView.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        listView.setClipToPadding(false);
        listView.setHorizontalScrollBarEnabled(false);
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setLayoutManager(layoutManager = new GridLayoutManager(context, 4) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);
                int total = state.getItemCount();
                int position = parent.getChildAdapterPosition(view);
                int spanCount = layoutManager.getSpanCount();
                int rowsCOunt = (int) Math.ceil(total / (float) spanCount);
                int row = position / spanCount;
                int col = position % spanCount;
                outRect.right = col != spanCount - 1 ? AndroidUtilities.dp(4) : 0;
                outRect.bottom = row != rowsCOunt - 1 ? AndroidUtilities.dp(4) : 0;
            }
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, singlePhoto ? 0 : 48));
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setGlowColor(0xff333333);
        listView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                ArrayList<Object> arrayList;
                if (selectedAlbum != null) {
                    arrayList = (ArrayList) selectedAlbum.photos;
                } else {
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        arrayList = (ArrayList) recentImages;
                    } else {
                        arrayList = (ArrayList) searchResult;
                    }
                }
                if (position < 0 || position >= arrayList.size()) {
                    return;
                }
                if (searchItem != null) {
                    AndroidUtilities.hideKeyboard(searchItem.getSearchField());
                }
                PhotoViewer.getInstance().setParentActivity(getParentActivity());
                PhotoViewer.getInstance().openPhotoForSelect(arrayList, position, singlePhoto ? 1 : 0, provider, chatActivity);
            }
        });

        if (selectedAlbum == null) {
            listView.setOnItemLongClickListener(new RecyclerListView.OnItemLongClickListener() {
                @Override
                public boolean onItemClick(View view, int position) {
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setTitle(LocaleController.getString("AppName", R.string.AppName));
                        builder.setMessage(LocaleController.getString("ClearSearch", R.string.ClearSearch));
                        builder.setPositiveButton(LocaleController.getString("ClearButton", R.string.ClearButton).toUpperCase(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                recentImages.clear();
                                if (listAdapter != null) {
                                    listAdapter.notifyDataSetChanged();
                                }
                                MessagesStorage.getInstance().clearWebRecent(type);
                            }
                        });
                        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
                        showDialog(builder.create());
                        return true;
                    }
                    return false;
                }
            });
        }

        emptyView = new EmptyTextProgressView(context);
        emptyView.setTextColor(0xff808080);
        emptyView.setProgressBarColor(0xffffffff);
        emptyView.setShowAtCenter(true);
        if (selectedAlbum != null) {
            emptyView.setText(LocaleController.getString("NoPhotos", R.string.NoPhotos));
        } else {
            if (type == 0) {
                emptyView.setText(LocaleController.getString("NoRecentPhotos", R.string.NoRecentPhotos));
            } else if (type == 1) {
                emptyView.setText(LocaleController.getString("NoRecentGIFs", R.string.NoRecentGIFs));
            }
        }
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, singlePhoto ? 0 : 48));

        if (selectedAlbum == null) {
            listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                    }
                }

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    int visibleItemCount = firstVisibleItem == RecyclerView.NO_POSITION ? 0 : Math.abs(layoutManager.findLastVisibleItemPosition() - firstVisibleItem) + 1;
                    if (visibleItemCount > 0) {
                        int totalItemCount = layoutManager.getItemCount();
                        if (visibleItemCount != 0 && firstVisibleItem + visibleItemCount > totalItemCount - 2 && !searching) {
                            if (type == 0 && !bingSearchEndReached) {
                                searchBingImages(lastSearchString, searchResult.size(), 54);
                            } else if (type == 1 && !giphySearchEndReached) {
                                searchGiphyImages(searchItem.getSearchField().getText().toString(), nextGiphySearchOffset);
                            }
                        }
                    }
                }
            });

            updateSearchInterface();
        }

        pickerBottomLayout = new PickerBottomLayout(context);
        frameLayout.addView(pickerBottomLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM));
        pickerBottomLayout.cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                delegate.actionButtonPressed(true);
                finishFragment();
            }
        });
        pickerBottomLayout.doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendSelectedPhotos();
            }
        });
        if (singlePhoto) {
            pickerBottomLayout.setVisibility(View.GONE);
        } else if ((selectedAlbum != null || type == 0) && chatActivity != null && chatActivity.allowGroupPhotos()) {
            imageOrderToggleButton = new ImageView(context);
            imageOrderToggleButton.setScaleType(ImageView.ScaleType.CENTER);
            imageOrderToggleButton.setImageResource(R.drawable.photos_group);
            pickerBottomLayout.addView(imageOrderToggleButton, LayoutHelper.createFrame(48, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
            imageOrderToggleButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MediaController.getInstance().toggleGroupPhotosEnabled();
                    imageOrderToggleButton.setColorFilter(MediaController.getInstance().isGroupPhotosEnabled() ? new PorterDuffColorFilter(0xff66bffa, PorterDuff.Mode.MULTIPLY) : null);
                    showHint(false, MediaController.getInstance().isGroupPhotosEnabled());
                    updateCheckedPhotoIndices();
                }
            });
            imageOrderToggleButton.setColorFilter(MediaController.getInstance().isGroupPhotosEnabled() ? new PorterDuffColorFilter(0xff66bffa, PorterDuff.Mode.MULTIPLY) : null);
        }
        allowIndices = (selectedAlbum != null || type == 0);

        listView.setEmptyView(emptyView);
        pickerBottomLayout.updateSelectedCount(selectedPhotos.size(), true);

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (searchItem != null) {
            searchItem.openSearch(true);
            getParentActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }
        fixLayout();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        fixLayout();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.closeChats) {
            removeSelfFromStack();
        } else if (id == NotificationCenter.recentImagesDidLoaded) {
            if (selectedAlbum == null && type == (Integer) args[0]) {
                recentImages = (ArrayList<MediaController.SearchImage>) args[1];
                loadingRecent = false;
                updateSearchInterface();
            }
        }
    }

    private void hideHint() {
        hintAnimation = new AnimatorSet();
        hintAnimation.playTogether(
                ObjectAnimator.ofFloat(hintTextView, "alpha", 0.0f)
        );
        hintAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation.equals(hintAnimation)) {
                    hintAnimation = null;
                    hintHideRunnable = null;
                    if (hintTextView != null) {
                        hintTextView.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (animation.equals(hintAnimation)) {
                    hintHideRunnable = null;
                    hintHideRunnable = null;
                }
            }
        });
        hintAnimation.setDuration(300);
        hintAnimation.start();
    }

    private void showHint(boolean hide, boolean enabled) {
        if (getParentActivity() == null || fragmentView == null || hide && hintTextView == null) {
            return;
        }
        if (hintTextView == null) {
            hintTextView = new TextView(getParentActivity());
            hintTextView.setBackgroundDrawable(Theme.createRoundRectDrawable(AndroidUtilities.dp(3), Theme.getColor(Theme.key_chat_gifSaveHintBackground)));
            hintTextView.setTextColor(Theme.getColor(Theme.key_chat_gifSaveHintText));
            hintTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            hintTextView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(7), AndroidUtilities.dp(8), AndroidUtilities.dp(7));
            hintTextView.setGravity(Gravity.CENTER_VERTICAL);
            hintTextView.setAlpha(0.0f);
            frameLayout.addView(hintTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 5, 0, 5, 48 + 3));
        }
        if (hide) {
            if (hintAnimation != null) {
                hintAnimation.cancel();
                hintAnimation = null;
            }
            AndroidUtilities.cancelRunOnUIThread(hintHideRunnable);
            hintHideRunnable = null;
            hideHint();
            return;
        }

        hintTextView.setText(enabled ? LocaleController.getString("GroupPhotosHelp", R.string.GroupPhotosHelp) : LocaleController.getString("SinglePhotosHelp", R.string.SinglePhotosHelp));

        if (hintHideRunnable != null) {
            if (hintAnimation != null) {
                hintAnimation.cancel();
                hintAnimation = null;
            } else {
                AndroidUtilities.cancelRunOnUIThread(hintHideRunnable);
                AndroidUtilities.runOnUIThread(hintHideRunnable = new Runnable() {
                    @Override
                    public void run() {
                        hideHint();
                    }
                }, 2000);
                return;
            }
        } else if (hintAnimation != null) {
            return;
        }

        hintTextView.setVisibility(View.VISIBLE);
        hintAnimation = new AnimatorSet();
        hintAnimation.playTogether(
                ObjectAnimator.ofFloat(hintTextView, "alpha", 1.0f)
        );
        hintAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation.equals(hintAnimation)) {
                    hintAnimation = null;
                    AndroidUtilities.runOnUIThread(hintHideRunnable = new Runnable() {
                        @Override
                        public void run() {
                            hideHint();
                        }
                    }, 2000);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (animation.equals(hintAnimation)) {
                    hintAnimation = null;
                }
            }
        });
        hintAnimation.setDuration(300);
        hintAnimation.start();
    }

    private void updateCheckedPhotoIndices() {
        if (selectedAlbum == null) {
            return;
        }
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View view = listView.getChildAt(a);
            if (view instanceof PhotoPickerPhotoCell) {
                PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
                Integer index = (Integer) cell.getTag();
                MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                cell.setNum(allowIndices ? selectedPhotosOrder.indexOf(photoEntry.imageId) : -1);
            }
        }
    }

    private PhotoPickerPhotoCell getCellForIndex(int index) {
        int count = listView.getChildCount();

        for (int a = 0; a < count; a++) {
            View view = listView.getChildAt(a);
            if (view instanceof PhotoPickerPhotoCell) {
                PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
                int num = (Integer) cell.photoImage.getTag();
                if (selectedAlbum != null) {
                    if (num < 0 || num >= selectedAlbum.photos.size()) {
                        continue;
                    }
                } else {
                    ArrayList<MediaController.SearchImage> array;
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        array = recentImages;
                    } else {
                        array = searchResult;
                    }
                    if (num < 0 || num >= array.size()) {
                        continue;
                    }
                }
                if (num == index) {
                    return cell;
                }
            }
        }
        return null;
    }

    private int addToSelectedPhotos(Object object, int index) {
        Object key = null;
        if (object instanceof MediaController.PhotoEntry) {
            key = ((MediaController.PhotoEntry) object).imageId;
        } else if (object instanceof MediaController.SearchImage) {
            key = ((MediaController.SearchImage) object).id;
        }
        if (key == null) {
            return -1;
        }
        if (selectedPhotos.containsKey(key)) {
            selectedPhotos.remove(key);
            int position = selectedPhotosOrder.indexOf(key);
            if (position >= 0) {
                selectedPhotosOrder.remove(position);
            }
            if (allowIndices) {
                updateCheckedPhotoIndices();
            }
            if (index >= 0) {
                if (object instanceof MediaController.PhotoEntry) {
                    ((MediaController.PhotoEntry) object).reset();
                } else if (object instanceof MediaController.SearchImage) {
                    ((MediaController.SearchImage) object).reset();
                }
                provider.updatePhotoAtIndex(index);
            }
            return position;
        } else {
            selectedPhotos.put(key, object);
            selectedPhotosOrder.add(key);
            return -1;
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && searchItem != null) {
            AndroidUtilities.showKeyboard(searchItem.getSearchField());
        }
    }

    private void updateSearchInterface() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (searching && searchResult.isEmpty() || loadingRecent && lastSearchString == null) {
            emptyView.showProgress();
        } else {
            emptyView.showTextView();
        }
    }

    private void searchGiphyImages(final String query, int offset) {
        if (searching) {
            searching = false;
            if (giphyReqId != 0) {
                ConnectionsManager.getInstance().cancelRequest(giphyReqId, true);
                giphyReqId = 0;
            }
            if (currentBingTask != null) {
                currentBingTask.cancel(true);
                currentBingTask = null;
            }
        }
        searching = true;
        TLRPC.TL_messages_searchGifs req = new TLRPC.TL_messages_searchGifs();
        req.q = query;
        req.offset = offset;
        final int token = ++lastSearchToken;
        giphyReqId = ConnectionsManager.getInstance().sendRequest(req, new RequestDelegate() {
            @Override
            public void run(final TLObject response, TLRPC.TL_error error) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (token != lastSearchToken) {
                            return;
                        }
                        int addedCount = 0;
                        if (response != null) {
                            boolean added = false;
                            TLRPC.TL_messages_foundGifs res = (TLRPC.TL_messages_foundGifs) response;
                            nextGiphySearchOffset = res.next_offset;
                            for (int a = 0; a < res.results.size(); a++) {
                                TLRPC.FoundGif gif = res.results.get(a);
                                if (searchResultKeys.containsKey(gif.url)) {
                                    continue;
                                }
                                added = true;
                                MediaController.SearchImage bingImage = new MediaController.SearchImage();
                                bingImage.id = gif.url;
                                if (gif.document != null) {
                                    for (int b = 0; b < gif.document.attributes.size(); b++) {
                                        TLRPC.DocumentAttribute attribute = gif.document.attributes.get(b);
                                        if (attribute instanceof TLRPC.TL_documentAttributeImageSize || attribute instanceof TLRPC.TL_documentAttributeVideo) {
                                            bingImage.width = attribute.w;
                                            bingImage.height = attribute.h;
                                            break;
                                        }
                                    }
                                } else {
                                    bingImage.width = gif.w;
                                    bingImage.height = gif.h;
                                }
                                bingImage.size = 0;
                                bingImage.imageUrl = gif.content_url;
                                bingImage.thumbUrl = gif.thumb_url;
                                bingImage.localUrl = gif.url + "|" + query;
                                bingImage.document = gif.document;
                                if (gif.photo != null && gif.document != null) {
                                    TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(gif.photo.sizes, itemWidth, true);
                                    if (size != null) {
                                        gif.document.thumb = size;
                                    }
                                }
                                bingImage.type = 1;
                                searchResult.add(bingImage);
                                addedCount++;
                                searchResultKeys.put(bingImage.id, bingImage);
                            }
                            giphySearchEndReached = !added;
                        }
                        searching = false;
                        if (addedCount != 0) {
                            listAdapter.notifyItemRangeInserted(searchResult.size(), addedCount);
                        } else if (giphySearchEndReached) {
                            listAdapter.notifyItemRemoved(searchResult.size() - 1);
                        }
                        if (searching && searchResult.isEmpty() || loadingRecent && lastSearchString == null) {
                            emptyView.showProgress();
                        } else {
                            emptyView.showTextView();
                        }
                    }
                });
            }
        });
        ConnectionsManager.getInstance().bindRequestToGuid(giphyReqId, classGuid);
    }

    private void searchBingImages(String query, int offset, int count) {
        if (searching) {
            searching = false;
            if (giphyReqId != 0) {
                ConnectionsManager.getInstance().cancelRequest(giphyReqId, true);
                giphyReqId = 0;
            }
            if (currentBingTask != null) {
                currentBingTask.cancel(true);
                currentBingTask = null;
            }
        }
        try {
            searching = true;

            boolean adult;
            String phone = UserConfig.getCurrentUser().phone;
            adult = phone.startsWith("44") || phone.startsWith("49") || phone.startsWith("43") || phone.startsWith("31") || phone.startsWith("1");
            final String url = String.format(Locale.US, "https://api.cognitive.microsoft.com/bing/v5.0/images/search?q='%s'&offset=%d&count=%d&$format=json&safeSearch=%s", URLEncoder.encode(query, "UTF-8"), offset, count, adult ? "Strict" : "Off");

            currentBingTask = new AsyncTask<Void, Void, JSONObject>() {

                private boolean canRetry = true;

                private String downloadUrlContent(String url) {
                    boolean canRetry = true;
                    InputStream httpConnectionStream = null;
                    boolean done = false;
                    StringBuilder result = null;
                    URLConnection httpConnection = null;
                    try {
                        URL downloadUrl = new URL(url);
                        httpConnection = downloadUrl.openConnection();
                        httpConnection.addRequestProperty("Ocp-Apim-Subscription-Key", BuildVars.BING_SEARCH_KEY);
                        httpConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)");
                        httpConnection.addRequestProperty("Accept-Language", "en-us,en;q=0.5");
                        httpConnection.addRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                        httpConnection.addRequestProperty("Accept-Charset", "ISO-8859-1,utf-8;q=0.7,*;q=0.7");
                        httpConnection.setConnectTimeout(5000);
                        httpConnection.setReadTimeout(5000);
                        if (httpConnection instanceof HttpURLConnection) {
                            HttpURLConnection httpURLConnection = (HttpURLConnection) httpConnection;
                            httpURLConnection.setInstanceFollowRedirects(true);
                            int status = httpURLConnection.getResponseCode();
                            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                                String newUrl = httpURLConnection.getHeaderField("Location");
                                String cookies = httpURLConnection.getHeaderField("Set-Cookie");
                                downloadUrl = new URL(newUrl);
                                httpConnection = downloadUrl.openConnection();
                                httpConnection.setRequestProperty("Cookie", cookies);
                                httpConnection.addRequestProperty("Ocp-Apim-Subscription-Key", BuildVars.BING_SEARCH_KEY);
                                httpConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)");
                                httpConnection.addRequestProperty("Accept-Language", "en-us,en;q=0.5");
                                httpConnection.addRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                                httpConnection.addRequestProperty("Accept-Charset", "ISO-8859-1,utf-8;q=0.7,*;q=0.7");
                            }
                        }
                        httpConnection.connect();
                        httpConnectionStream = httpConnection.getInputStream();
                    } catch (Throwable e) {
                        if (e instanceof SocketTimeoutException) {
                            if (ConnectionsManager.isNetworkOnline()) {
                                canRetry = false;
                            }
                        } else if (e instanceof UnknownHostException) {
                            canRetry = false;
                        } else if (e instanceof SocketException) {
                            if (e.getMessage() != null && e.getMessage().contains("ECONNRESET")) {
                                canRetry = false;
                            }
                        } else if (e instanceof FileNotFoundException) {
                            canRetry = false;
                        }
                        FileLog.e(e);
                    }

                    if (canRetry) {
                        try {
                            if (httpConnection != null && httpConnection instanceof HttpURLConnection) {
                                int code = ((HttpURLConnection) httpConnection).getResponseCode();
                                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                                    //canRetry = false;
                                }
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }

                        if (httpConnectionStream != null) {
                            try {
                                byte[] data = new byte[1024 * 32];
                                while (true) {
                                    if (isCancelled()) {
                                        break;
                                    }
                                    try {
                                        int read = httpConnectionStream.read(data);
                                        if (read > 0) {
                                            if (result == null) {
                                                result = new StringBuilder();
                                            }
                                            result.append(new String(data, 0, read, "UTF-8"));
                                        } else if (read == -1) {
                                            done = true;
                                            break;
                                        } else {
                                            break;
                                        }
                                    } catch (Exception e) {
                                        FileLog.e(e);
                                        break;
                                    }
                                }
                            } catch (Throwable e) {
                                FileLog.e(e);
                            }
                        }

                        try {
                            if (httpConnectionStream != null) {
                                httpConnectionStream.close();
                            }
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    }
                    return done ? result.toString() : null;
                }

                protected JSONObject doInBackground(Void... voids) {
                    String code = downloadUrlContent(url);
                    if (isCancelled()) {
                        return null;
                    }
                    try {
                        return new JSONObject(code);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(JSONObject response) {
                    int addedCount = 0;
                    if (response != null) {
                        try {
                            JSONArray result = response.getJSONArray("value");
                            boolean added = false;
                            for (int a = 0; a < result.length(); a++) {
                                try {
                                    JSONObject object = result.getJSONObject(a);
                                    String id = Utilities.MD5(object.getString("contentUrl"));
                                    if (searchResultKeys.containsKey(id)) {
                                        continue;
                                    }
                                    MediaController.SearchImage bingImage = new MediaController.SearchImage();
                                    bingImage.id = id;
                                    bingImage.width = object.getInt("width");
                                    bingImage.height = object.getInt("height");
                                    bingImage.size = Utilities.parseInt(object.getString("contentSize"));
                                    bingImage.imageUrl = object.getString("contentUrl");
                                    bingImage.thumbUrl = object.getString("thumbnailUrl");
                                    searchResult.add(bingImage);
                                    searchResultKeys.put(id, bingImage);
                                    addedCount++;
                                    added = true;
                                } catch (Exception e) {
                                    FileLog.e(e);
                                }
                            }
                            bingSearchEndReached = !added;
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        searching = false;
                    } else {
                        bingSearchEndReached = true;
                        searching = false;
                    }
                    if (addedCount != 0) {
                        listAdapter.notifyItemRangeInserted(searchResult.size(), addedCount);
                    } else if (giphySearchEndReached) {
                        listAdapter.notifyItemRemoved(searchResult.size() - 1);
                    }
                    if (searching && searchResult.isEmpty() || loadingRecent && lastSearchString == null) {
                        emptyView.showProgress();
                    } else {
                        emptyView.showTextView();
                    }
                }
            };
            currentBingTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null, null, null);
        } catch (Exception e) {
            FileLog.e(e);
            bingSearchEndReached = true;
            searching = false;
            listAdapter.notifyItemRemoved(searchResult.size() - 1);
            if (searching && searchResult.isEmpty() || loadingRecent && lastSearchString == null) {
                emptyView.showProgress();
            } else {
                emptyView.showTextView();
            }
        }
    }

    public void setDelegate(PhotoPickerActivityDelegate delegate) {
        this.delegate = delegate;
    }

    private void sendSelectedPhotos() {
        if (selectedPhotos.isEmpty() || delegate == null || sendPressed) {
            return;
        }
        sendPressed = true;
        delegate.actionButtonPressed(false);
        finishFragment();
    }

    private void fixLayout() {
        if (listView != null) {
            ViewTreeObserver obs = listView.getViewTreeObserver();
            obs.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    fixLayoutInternal();
                    if (listView != null) {
                        listView.getViewTreeObserver().removeOnPreDrawListener(this);
                    }
                    return true;
                }
            });
        }
    }

    private void fixLayoutInternal() {
        if (getParentActivity() == null) {
            return;
        }
        int position = layoutManager.findFirstVisibleItemPosition();
        WindowManager manager = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Activity.WINDOW_SERVICE);
        int rotation = manager.getDefaultDisplay().getRotation();

        int columnsCount;
        if (AndroidUtilities.isTablet()) {
            columnsCount = 3;
        } else {
            if (rotation == Surface.ROTATION_270 || rotation == Surface.ROTATION_90) {
                columnsCount = 5;
            } else {
                columnsCount = 3;
            }
        }
        layoutManager.setSpanCount(columnsCount);
        if (AndroidUtilities.isTablet()) {
            itemWidth = (AndroidUtilities.dp(490) - ((columnsCount + 1) * AndroidUtilities.dp(4))) / columnsCount;
        } else {
            itemWidth = (AndroidUtilities.displaySize.x - ((columnsCount + 1) * AndroidUtilities.dp(4))) / columnsCount;
        }

        listAdapter.notifyDataSetChanged();
        layoutManager.scrollToPosition(position);

        if (selectedAlbum == null) {
            emptyView.setPadding(0, 0, 0, (int) ((AndroidUtilities.displaySize.y - ActionBar.getCurrentActionBarHeight()) * 0.4f));
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            if (selectedAlbum == null) {
                int position = holder.getAdapterPosition();
                if (searchResult.isEmpty() && lastSearchString == null) {
                    return position < recentImages.size();
                } else {
                    return position < searchResult.size();
                }
            }
            return true;
        }

        @Override
        public int getItemCount() {
            if (selectedAlbum == null) {
                if (searchResult.isEmpty() && lastSearchString == null) {
                    return recentImages.size();
                } else if (type == 0) {
                    return searchResult.size() + (bingSearchEndReached ? 0 : 1);
                } else if (type == 1) {
                    return searchResult.size() + (giphySearchEndReached ? 0 : 1);
                }
            }
            return selectedAlbum.photos.size();
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    PhotoPickerPhotoCell cell = new PhotoPickerPhotoCell(mContext, true);
                    cell.checkFrame.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            int index = (Integer) ((View) v.getParent()).getTag();
                            if (selectedAlbum != null) {
                                MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                                boolean added = !selectedPhotos.containsKey(photoEntry.imageId);
                                int num = allowIndices && added ? selectedPhotosOrder.size() : -1;
                                ((PhotoPickerPhotoCell) v.getParent()).setChecked(num, added, true);
                                addToSelectedPhotos(photoEntry, index);
                            } else {
                                AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                                MediaController.SearchImage photoEntry;
                                if (searchResult.isEmpty() && lastSearchString == null) {
                                    photoEntry = recentImages.get((Integer) ((View) v.getParent()).getTag());
                                } else {
                                    photoEntry = searchResult.get((Integer) ((View) v.getParent()).getTag());
                                }
                                boolean added = !selectedPhotos.containsKey(photoEntry.id);
                                int num = allowIndices && added ? selectedPhotosOrder.size() : -1;
                                ((PhotoPickerPhotoCell) v.getParent()).setChecked(num, added, true);
                                addToSelectedPhotos(photoEntry, index);
                            }
                            pickerBottomLayout.updateSelectedCount(selectedPhotos.size(), true);
                            delegate.selectedPhotosChanged();
                        }
                    });
                    cell.checkFrame.setVisibility(singlePhoto ? View.GONE : View.VISIBLE);
                    view = cell;
                    break;
                case 1:
                default:
                    FrameLayout frameLayout = new FrameLayout(mContext);
                    view = frameLayout;
                    RadialProgressView progressBar = new RadialProgressView(mContext);
                    progressBar.setProgressColor(0xffffffff);
                    frameLayout.addView(progressBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0:
                    PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) holder.itemView;
                    cell.itemWidth = itemWidth;
                    BackupImageView imageView = cell.photoImage;
                    imageView.setTag(position);
                    cell.setTag(position);
                    boolean showing;
                    imageView.setOrientation(0, true);

                    if (selectedAlbum != null) {
                        MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(position);
                        if (photoEntry.thumbPath != null) {
                            imageView.setImage(photoEntry.thumbPath, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                        } else if (photoEntry.path != null) {
                            imageView.setOrientation(photoEntry.orientation, true);
                            if (photoEntry.isVideo) {
                                cell.videoInfoContainer.setVisibility(View.VISIBLE);
                                int minutes = photoEntry.duration / 60;
                                int seconds = photoEntry.duration - minutes * 60;
                                cell.videoTextView.setText(String.format("%d:%02d", minutes, seconds));
                                imageView.setImage("vthumb://" + photoEntry.imageId + ":" + photoEntry.path, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                            } else {
                                cell.videoInfoContainer.setVisibility(View.INVISIBLE);
                                imageView.setImage("thumb://" + photoEntry.imageId + ":" + photoEntry.path, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                            }
                        } else {
                            imageView.setImageResource(R.drawable.nophotos);
                        }
                        cell.setChecked(allowIndices ? selectedPhotosOrder.indexOf(photoEntry.imageId) : -1, selectedPhotos.containsKey(photoEntry.imageId), false);
                        showing = PhotoViewer.getInstance().isShowingImage(photoEntry.path);
                    } else {
                        MediaController.SearchImage photoEntry;
                        if (searchResult.isEmpty() && lastSearchString == null) {
                            photoEntry = recentImages.get(position);
                        } else {
                            photoEntry = searchResult.get(position);
                        }
                        if (photoEntry.thumbPath != null) {
                            imageView.setImage(photoEntry.thumbPath, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                        } else if (photoEntry.thumbUrl != null && photoEntry.thumbUrl.length() > 0) {
                            imageView.setImage(photoEntry.thumbUrl, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                        } else if (photoEntry.document != null && photoEntry.document.thumb != null) {
                            imageView.setImage(photoEntry.document.thumb.location, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                        } else {
                            imageView.setImageResource(R.drawable.nophotos);
                        }
                        cell.videoInfoContainer.setVisibility(View.INVISIBLE);
                        cell.setChecked(allowIndices ? selectedPhotosOrder.indexOf(photoEntry.id) : -1, selectedPhotos.containsKey(photoEntry.id), false);
                        if (photoEntry.document != null) {
                            showing = PhotoViewer.getInstance().isShowingImage(FileLoader.getPathToAttach(photoEntry.document, true).getAbsolutePath());
                        } else {
                            showing = PhotoViewer.getInstance().isShowingImage(photoEntry.imageUrl);
                        }
                    }
                    imageView.getImageReceiver().setVisible(!showing, true);
                    cell.checkBox.setVisibility(singlePhoto || showing ? View.GONE : View.VISIBLE);
                    break;
                case 1:
                    ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
                    if (params != null) {
                        params.width = itemWidth;
                        params.height = itemWidth;
                        holder.itemView.setLayoutParams(params);
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int i) {
            if (selectedAlbum != null || searchResult.isEmpty() && lastSearchString == null && i < recentImages.size() || i < searchResult.size()) {
                return 0;
            }
            return 1;
        }
    }
}
