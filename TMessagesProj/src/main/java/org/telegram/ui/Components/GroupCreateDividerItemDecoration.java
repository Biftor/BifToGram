/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.ui.Components;

import android.graphics.*;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.support.widget.RecyclerView;
import org.telegram.ui.ActionBar.Theme;

public class GroupCreateDividerItemDecoration extends RecyclerView.ItemDecoration {

    private boolean searching;
    private boolean single;

    public void setSearching(boolean value) {
        searching = value;
    }

    public void setSingle(boolean value) {
        single = value;
    }

    @Override
    public void onDraw(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
        int width = parent.getWidth();
        int top;
        int childCount = parent.getChildCount() - (single ? 0 : 1);
        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            top = child.getBottom();
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(72), top, width - (LocaleController.isRTL ? AndroidUtilities.dp(72) : 0), top, Theme.dividerPaint);
        }
    }

    @Override
    public void getItemOffsets(android.graphics.Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        super.getItemOffsets(outRect, view, parent, state);
        /*int position = parent.getChildAdapterPosition(view);
        if (position == 0 || !searching && position == 1) {
            return;
        }*/
        outRect.top = 1;
    }
}
