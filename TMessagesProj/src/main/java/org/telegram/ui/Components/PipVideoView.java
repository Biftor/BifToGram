/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.exoplayer2.ui.AspectRatioFrameLayout;
import org.telegram.ui.ActionBar.ActionBar;

import java.util.ArrayList;

public class PipVideoView {

    private FrameLayout windowView;
    private EmbedBottomSheet parentSheet;
    private Activity parentActivity;
    private View controlsView;
    private int videoWidth;
    private int videoHeight;

    private WindowManager.LayoutParams windowLayoutParams;
    private WindowManager windowManager;
    private SharedPreferences preferences;
    private DecelerateInterpolator decelerateInterpolator;

    private class MiniControlsView extends FrameLayout {

        private boolean isVisible = true;
        private AnimatorSet currentAnimation;
        private ImageView inlineButton;
        private Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                show(false, true);
            }
        };

        public MiniControlsView(Context context) {
            super(context);
            setWillNotDraw(false);

            inlineButton = new ImageView(context);
            inlineButton.setScaleType(ImageView.ScaleType.CENTER);
            inlineButton.setImageResource(R.drawable.ic_outinline);
            addView(inlineButton, LayoutHelper.createFrame(56, 48, Gravity.RIGHT | Gravity.TOP));
            inlineButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (parentSheet == null) {
                        return;
                    }
                    parentSheet.exitFromPip();
                }
            });
        }

        public void show(boolean value, boolean animated) {
            if (isVisible == value) {
                return;
            }
            isVisible = value;
            if (currentAnimation != null) {
                currentAnimation.cancel();
            }
            if (isVisible) {
                if (animated) {
                    currentAnimation = new AnimatorSet();
                    currentAnimation.playTogether(ObjectAnimator.ofFloat(this, "alpha", 1.0f));
                    currentAnimation.setDuration(150);
                    currentAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animator) {
                            currentAnimation = null;
                        }
                    });
                    currentAnimation.start();
                } else {
                    setAlpha(1.0f);
                }
            } else {
                if (animated) {
                    currentAnimation = new AnimatorSet();
                    currentAnimation.playTogether(ObjectAnimator.ofFloat(this, "alpha", 0.0f));
                    currentAnimation.setDuration(150);
                    currentAnimation.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animator) {
                            currentAnimation = null;
                        }
                    });
                    currentAnimation.start();
                } else {
                    setAlpha(0.0f);
                }
            }
            checkNeedHide();
        }

        private void checkNeedHide() {
            AndroidUtilities.cancelRunOnUIThread(hideRunnable);
            if (isVisible) {
                AndroidUtilities.runOnUIThread(hideRunnable, 3000);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                if (!isVisible) {
                    show(true, true);
                    return true;
                } else {
                    checkNeedHide();
                }
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept);
            checkNeedHide();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            checkNeedHide();
        }
    }

    public TextureView show(Activity activity, EmbedBottomSheet sheet, View controls, float aspectRatio, int rotation, WebView webview) {
        windowView = new FrameLayout(activity) {

            private float startX;
            private float startY;
            private boolean dragging;

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                float x = event.getRawX();
                float y = event.getRawY();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = x;
                    startY = y;
                } else if (event.getAction() == MotionEvent.ACTION_MOVE && !dragging) {
                    if (Math.abs(startX - x) >= AndroidUtilities.getPixelsInCM(0.3f, true) || Math.abs(startY - y) >= AndroidUtilities.getPixelsInCM(0.3f, false)) {
                        dragging = true;
                        startX = x;
                        startY = y;
                        if (controlsView != null) {
                            ((ViewParent) controlsView).requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    }
                }
                return super.onInterceptTouchEvent(event);
            }

            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                super.requestDisallowInterceptTouchEvent(disallowIntercept);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (!dragging) {
                    return false;
                }
                float x = event.getRawX();
                float y = event.getRawY();
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = (x - startX);
                    float dy = (y - startY);
                    windowLayoutParams.x += dx;
                    windowLayoutParams.y += dy;
                    int maxDiff = videoWidth / 2;
                    if (windowLayoutParams.x < -maxDiff) {
                        windowLayoutParams.x = -maxDiff;
                    } else if (windowLayoutParams.x > AndroidUtilities.displaySize.x - windowLayoutParams.width + maxDiff) {
                        windowLayoutParams.x = AndroidUtilities.displaySize.x - windowLayoutParams.width + maxDiff;
                    }
                    float alpha = 1.0f;
                    if (windowLayoutParams.x < 0) {
                        alpha = 1.0f + windowLayoutParams.x / (float) maxDiff * 0.5f;
                    } else if (windowLayoutParams.x > AndroidUtilities.displaySize.x - windowLayoutParams.width) {
                        alpha = 1.0f - (windowLayoutParams.x - AndroidUtilities.displaySize.x + windowLayoutParams.width) / (float) maxDiff * 0.5f;
                    }
                    if (windowView.getAlpha() != alpha) {
                        windowView.setAlpha(alpha);
                    }
                    maxDiff = 0;
                    if (windowLayoutParams.y < -maxDiff) {
                        windowLayoutParams.y = -maxDiff;
                    } else if (windowLayoutParams.y > AndroidUtilities.displaySize.y - windowLayoutParams.height + maxDiff) {
                        windowLayoutParams.y = AndroidUtilities.displaySize.y - windowLayoutParams.height + maxDiff;
                    }
                    windowManager.updateViewLayout(windowView, windowLayoutParams);
                    startX = x;
                    startY = y;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    dragging = false;
                    animateToBoundsMaybe();
                }
                return true;
            }
        };

        if (aspectRatio > 1) {
            videoWidth = AndroidUtilities.dp(192);
            videoHeight = (int) (videoWidth / aspectRatio);
        } else {
            videoHeight = AndroidUtilities.dp(192);
            videoWidth = (int) (videoHeight * aspectRatio);
        }

        AspectRatioFrameLayout aspectRatioFrameLayout = new AspectRatioFrameLayout(activity);
        aspectRatioFrameLayout.setAspectRatio(aspectRatio, rotation);
        windowView.addView(aspectRatioFrameLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        TextureView textureView;
        if (webview != null) {
            ViewGroup parent = (ViewGroup) webview.getParent();
            if (parent != null) {
                parent.removeView(webview);
            }
            aspectRatioFrameLayout.addView(webview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            textureView = null;
        } else {
            textureView = new TextureView(activity);
            aspectRatioFrameLayout.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        if (controls == null) {
            controlsView = new MiniControlsView(activity);
        } else {
            controlsView = controls;
        }
        windowView.addView(controlsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        windowManager = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);

        preferences = ApplicationLoader.applicationContext.getSharedPreferences("pipconfig", Context.MODE_PRIVATE);

        int sidex = preferences.getInt("sidex", 1);
        int sidey = preferences.getInt("sidey", 0);
        float px = preferences.getFloat("px", 0);
        float py = preferences.getFloat("py", 0);

        try {
            windowLayoutParams = new WindowManager.LayoutParams();
            windowLayoutParams.width = videoWidth;
            windowLayoutParams.height = videoHeight;
            windowLayoutParams.x = getSideCoord(true, sidex, px, videoWidth);
            windowLayoutParams.y = getSideCoord(false, sidey, py, videoHeight);
            windowLayoutParams.format = PixelFormat.TRANSLUCENT;
            windowLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            if (Build.VERSION.SDK_INT >= 26) {
                windowLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                windowLayoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }
            windowLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            windowManager.addView(windowView, windowLayoutParams);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        parentSheet = sheet;
        parentActivity = activity;

        return textureView;
    }

    private static int getSideCoord(boolean isX, int side, float p, int sideSize) {
        int total;
        if (isX) {
            total = AndroidUtilities.displaySize.x - sideSize;
        } else {
            total = AndroidUtilities.displaySize.y - sideSize - ActionBar.getCurrentActionBarHeight();
        }
        int result;
        if (side == 0) {
            result = AndroidUtilities.dp(10);
        } else if (side == 1) {
            result = total - AndroidUtilities.dp(10);
        } else {
            result = Math.round((total - AndroidUtilities.dp(20)) * p) + AndroidUtilities.dp(10);
        }
        if (!isX) {
            result += ActionBar.getCurrentActionBarHeight();
        }
        return result;
    }

    public void close() {
        try {
            windowManager.removeView(windowView);
        } catch (Exception e) {
            //don't promt
        }
        parentSheet = null;
        parentActivity = null;
    }

    public void onConfigurationChanged() {
        int sidex = preferences.getInt("sidex", 1);
        int sidey = preferences.getInt("sidey", 0);
        float px = preferences.getFloat("px", 0);
        float py = preferences.getFloat("py", 0);
        windowLayoutParams.x = getSideCoord(true, sidex, px, videoWidth);
        windowLayoutParams.y = getSideCoord(false, sidey, py, videoHeight);
        windowManager.updateViewLayout(windowView, windowLayoutParams);
    }

    private void animateToBoundsMaybe() {
        int startX = getSideCoord(true, 0, 0, videoWidth);
        int endX = getSideCoord(true, 1, 0, videoWidth);
        int startY = getSideCoord(false, 0, 0, videoHeight);
        int endY = getSideCoord(false, 1, 0, videoHeight);
        ArrayList<Animator> animators = null;
        SharedPreferences.Editor editor = preferences.edit();
        int maxDiff = AndroidUtilities.dp(20);
        boolean slideOut = false;
        if (Math.abs(startX - windowLayoutParams.x) <= maxDiff || windowLayoutParams.x < 0 && windowLayoutParams.x > -videoWidth / 4) {
            if (animators == null) {
                animators = new ArrayList<>();
            }
            editor.putInt("sidex", 0);
            if (windowView.getAlpha() != 1.0f) {
                animators.add(ObjectAnimator.ofFloat(windowView, "alpha", 1.0f));
            }
            animators.add(ObjectAnimator.ofInt(this, "x", startX));
        } else if (Math.abs(endX - windowLayoutParams.x) <= maxDiff || windowLayoutParams.x > AndroidUtilities.displaySize.x - videoWidth && windowLayoutParams.x < AndroidUtilities.displaySize.x - videoWidth / 4 * 3) {
            if (animators == null) {
                animators = new ArrayList<>();
            }
            editor.putInt("sidex", 1);
            if (windowView.getAlpha() != 1.0f) {
                animators.add(ObjectAnimator.ofFloat(windowView, "alpha", 1.0f));
            }
            animators.add(ObjectAnimator.ofInt(this, "x", endX));
        } else if (windowView.getAlpha() != 1.0f) {
            if (animators == null) {
                animators = new ArrayList<>();
            }
            if (windowLayoutParams.x < 0) {
                animators.add(ObjectAnimator.ofInt(this, "x", -videoWidth));
            } else {
                animators.add(ObjectAnimator.ofInt(this, "x", AndroidUtilities.displaySize.x));
            }
            slideOut = true;
        } else {
            editor.putFloat("px", (windowLayoutParams.x - startX) / (float) (endX - startX));
            editor.putInt("sidex", 2);
        }
        if (!slideOut) {
            if (Math.abs(startY - windowLayoutParams.y) <= maxDiff || windowLayoutParams.y <= ActionBar.getCurrentActionBarHeight()) {
                if (animators == null) {
                    animators = new ArrayList<>();
                }
                editor.putInt("sidey", 0);
                animators.add(ObjectAnimator.ofInt(this, "y", startY));
            } else if (Math.abs(endY - windowLayoutParams.y) <= maxDiff) {
                if (animators == null) {
                    animators = new ArrayList<>();
                }
                editor.putInt("sidey", 1);
                animators.add(ObjectAnimator.ofInt(this, "y", endY));
            } else {
                editor.putFloat("py", (windowLayoutParams.y - startY) / (float) (endY - startY));
                editor.putInt("sidey", 2);
            }
            editor.commit();
        }
        if (animators != null) {
            if (decelerateInterpolator == null) {
                decelerateInterpolator = new DecelerateInterpolator();
            }
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.setInterpolator(decelerateInterpolator);
            animatorSet.setDuration(150);
            if (slideOut) {
                animators.add(ObjectAnimator.ofFloat(windowView, "alpha", 0.0f));
                animatorSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (parentSheet != null) {
                            parentSheet.destroy();
                        }
                    }
                });
            }
            animatorSet.playTogether(animators);
            animatorSet.start();
        }
    }

    public static Rect getPipRect(float aspectRatio) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("pipconfig", Context.MODE_PRIVATE);
        int sidex = preferences.getInt("sidex", 1);
        int sidey = preferences.getInt("sidey", 0);
        float px = preferences.getFloat("px", 0);
        float py = preferences.getFloat("py", 0);

        int videoWidth;
        int videoHeight;
        if (aspectRatio > 1) {
            videoWidth = AndroidUtilities.dp(192);
            videoHeight = (int) (videoWidth / aspectRatio);
        } else {
            videoHeight = AndroidUtilities.dp(192);
            videoWidth = (int) (videoHeight * aspectRatio);
        }

        return new Rect(getSideCoord(true, sidex, px, videoWidth), getSideCoord(false, sidey, py, videoHeight), videoWidth, videoHeight);
    }

    public int getX() {
        return windowLayoutParams.x;
    }

    public int getY() {
        return windowLayoutParams.y;
    }

    public void setX(int value) {
        windowLayoutParams.x = value;
        windowManager.updateViewLayout(windowView, windowLayoutParams);
    }

    public void setY(int value) {
        windowLayoutParams.y = value;
        windowManager.updateViewLayout(windowView, windowLayoutParams);
    }
}
