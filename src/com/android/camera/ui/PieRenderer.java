/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2017 Paranoid Android
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

package com.android.camera.ui;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.util.FloatMath;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import com.android.camera.CameraActivity;
import com.android.camera.drawable.TextDrawable;
import com.android.camera.ui.ProgressRenderer.VisibilityListener;
import org.codeaurora.snapcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * An overlay renderer that is used to display focus state and progress state.
 */
public class PieRenderer extends OverlayRenderer
        implements FocusIndicator {

    private static final String TAG = "PieRenderer";

    // Sometimes continuous autofocus starts and stops several times quickly.
    // These states are used to make sure the animation is run for at least some
    // time.
    private volatile int mState;
    private ValueAnimator mAnimator;
    private ValueAnimator mAlphaAnimator;
    private static final int STATE_IDLE = 0;
    private static final int STATE_FOCUSING = 1;
    private static final int STATE_FINISHING = 2;
    private static final int STATE_PIE = 8;

    private Runnable mDisappear = new Disappear();
    private static final int DISAPPEAR_TIMEOUT = 200;
    // fade out timings
    private static final int PIE_FADE_OUT_DURATION = 600;

    private static final long PIE_FADE_IN_DURATION = 200;
    private static final long PIE_XFADE_DURATION = 200;
    private static final long PIE_OPEN_SUB_DELAY = 400;
    private static final long PIE_SLICE_DURATION = 80;

    private static final int MSG_OPEN = 0;
    private static final int MSG_CLOSE = 1;
    private static final int MSG_OPENSUBMENU = 2;

    protected static float CENTER = (float) Math.PI / 2;
    private static final float RAD24 = (float) (24 * Math.PI / 180);
    private static final float SWEEP_SLICE = 0.14f;
    private static final float SWEEP_ARC = 0.23f;

    private static final int FOCUS_INNER_CIRCLE_ALPHA = (int) (255f * 0.6f);

    // geometry
    private int mRadiusInc;

    // the detection if touch is inside a slice is offset
    // inbounds by this amount to allow the selection to show before the
    // finger covers it
    private int mTouchOffset;

    private List<PieItem> mOpen;

    private Paint mSelectedPaint;
    private Paint mMenuArcPaint;

    // touch handling
    private PieItem mCurrentItem;

    private Paint mFocusOuterCirclePaint;
    private Paint mFocusInnerCirclePaint;
    private final int mFocusCircleRadius;
    private final int mFocusCircleRadiusOffset;
    private final int mFocusInnerCircleRadius;
    private int mFocusOuterRadius;
    private int mFocusInnerRadius;
    private int mFocusX;
    private int mFocusY;
    private int mCenterX;
    private int mCenterY;
    private int mArcCenterY;
    private int mSliceCenterY;
    private int mPieCenterX;
    private int mPieCenterY;
    private int mSliceRadius;
    private int mArcRadius, mMaxArcRadius;
    private int mArcOffset;

    private boolean mTapMode;
    private boolean mBlockFocus;
    private int mTouchSlopSquared;
    private Point mDown;
    private boolean mOpening;
    private ValueAnimator mXFade;
    private ValueAnimator mFadeIn;
    private ValueAnimator mFadeOut;
    private ValueAnimator mSlice;
    private PointF mPolar = new PointF();
    private TextDrawable mLabel;
    private int mDeadZone;
    private int mAngleZone;
    private float mCenterAngle;

    private ProgressRenderer mProgressRenderer;

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case MSG_OPEN:
                if (mListener != null) {
                    mListener.onPieOpened(mPieCenterX, mPieCenterY);
                }
                break;
            case MSG_CLOSE:
                if (mListener != null) {
                    mListener.onPieClosed();
                }
                break;
            case MSG_OPENSUBMENU:
                onEnterOpen();
                break;
            }

        }
    };

    private PieListener mListener;

    public interface PieListener {
        public void onPieOpened(int centerX, int centerY);

        public void onPieClosed();
    }

    public void setPieListener(PieListener pl) {
        mListener = pl;
    }

    public PieRenderer(Context context) {
        setVisible(false);
        mOpen = new ArrayList<>();
        mOpen.add(new PieItem(null, 0));
        Resources res = context.getResources();
        mRadiusInc = res.getDimensionPixelSize(R.dimen.pie_radius_increment);
        mTouchOffset = res.getDimensionPixelSize(R.dimen.pie_touch_offset);
        mSelectedPaint = new Paint();
        mSelectedPaint.setColor(Color.argb(255, 51, 181, 229));
        mSelectedPaint.setAntiAlias(true);

        mFocusOuterCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFocusOuterCirclePaint.setColor(Color.WHITE);
        mFocusOuterCirclePaint.setStyle(Paint.Style.STROKE);
        mFocusOuterCirclePaint.setStrokeWidth(res.getDimensionPixelSize(R.dimen.focus_outer_stroke));

        mFocusInnerCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFocusInnerCirclePaint.setColor(Color.WHITE);
        mFocusInnerCirclePaint.setStyle(Paint.Style.FILL);

        mFocusCircleRadius = res.getDimensionPixelSize(R.dimen.focus_circle_radius);
        mFocusCircleRadiusOffset = res.getDimensionPixelSize(R.dimen.focus_circle_radius_offset);
        mFocusInnerCircleRadius = res.getDimensionPixelSize(R.dimen.focus_inner_circle_radius);

        mState = STATE_IDLE;
        mBlockFocus = false;
        mTouchSlopSquared = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquared = mTouchSlopSquared * mTouchSlopSquared;
        mDown = new Point();
        mMenuArcPaint = new Paint();
        mMenuArcPaint.setAntiAlias(true);
        mMenuArcPaint.setColor(Color.argb(140, 255, 255, 255));
        mMenuArcPaint.setStrokeWidth(10);
        mMenuArcPaint.setStyle(Paint.Style.STROKE);
        mSliceRadius = res.getDimensionPixelSize(R.dimen.pie_item_radius);
        mArcRadius = res.getDimensionPixelSize(R.dimen.pie_arc_radius);
        mMaxArcRadius = mArcRadius;
        mArcOffset = res.getDimensionPixelSize(R.dimen.pie_arc_offset);
        mLabel = new TextDrawable(res);
        mLabel.setDropShadow(true);
        mDeadZone = res.getDimensionPixelSize(R.dimen.pie_deadzone_width);
        mAngleZone = res.getDimensionPixelSize(R.dimen.pie_anglezone_width);
        mProgressRenderer = new ProgressRenderer(context);
    }

    private PieItem getRoot() {
        return mOpen.get(0);
    }

    public boolean showsItems() {
        return mTapMode;
    }

    public void addItem(PieItem item) {
        // add the item to the pie itself
        getRoot().addItem(item);
    }

    public void clearItems() {
        getRoot().clearItems();
    }

    public void showInCenter() {
        if ((mState == STATE_PIE) && isVisible()) {
            mTapMode = false;
            show(false);
        } else {
            if (mState != STATE_IDLE) {
                cancelFocus();
            }
            mState = STATE_PIE;
            resetPieCenter();
            setCenter(mPieCenterX, mPieCenterY);
            mTapMode = true;
            show(true);
        }
    }

    public void hide() {
        show(false);
    }

    /**
     * guaranteed has center set
     *
     * @param show
     */
    private void show(boolean show) {
        if (show) {
            if (mXFade != null) {
                mXFade.cancel();
            }
            mState = STATE_PIE;
            // ensure clean state
            mCurrentItem = null;
            PieItem root = getRoot();
            for (PieItem openItem : mOpen) {
                if (openItem.hasItems()) {
                    for (PieItem item : openItem.getItems()) {
                        item.setSelected(false);
                    }
                }
            }
            mLabel.setText("");
            mOpen.clear();
            mOpen.add(root);
            layoutPie();
            fadeIn();
        } else {
            mState = STATE_IDLE;
            mTapMode = false;
            if (mXFade != null) {
                mXFade.cancel();
            }
            if (mLabel != null) {
                mLabel.setText("");
            }
        }
        setVisible(show);
        mHandler.sendEmptyMessage(show ? MSG_OPEN : MSG_CLOSE);
    }

    public boolean isOpen() {
        return mState == STATE_PIE && isVisible();
    }

    public void setProgress(int percent) {
        mProgressRenderer.setProgress(percent);
    }

    private void fadeIn() {
        mFadeIn = new ValueAnimator();
        mFadeIn.setFloatValues(0f, 1f);
        mFadeIn.setDuration(PIE_FADE_IN_DURATION);
        // linear interpolation
        mFadeIn.setInterpolator(null);
        mFadeIn.addListener(new AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mFadeIn = null;
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
            }

            @Override
            public void onAnimationCancel(Animator arg0) {
            }
        });
        mFadeIn.start();
    }

    public void setCenter(int x, int y) {
        mPieCenterX = x;
        mPieCenterY = y;
        mSliceCenterY = y + mSliceRadius - mArcOffset;
        mArcCenterY = y - mArcOffset + mArcRadius;
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        super.layout(l, t, r, b);
        if (mCenterX == 0) {
            mCenterX = (r - l) / 2 + l;
        }
        if (mCenterY == 0) {
            mCenterY = (b - t) / 2 + t;
        }

        int layoutWidth = r - l;
        if ((layoutWidth > 0) && ((mMaxArcRadius + mCenterX) > layoutWidth)) {
            mArcRadius = layoutWidth - mCenterX;
        } else {
            mArcRadius = mMaxArcRadius;
        }
        mFocusX = mCenterX;
        mFocusY = mCenterY;
        resetPieCenter();
        if (isVisible() && mState == STATE_PIE) {
            setCenter(mPieCenterX, mPieCenterY);
            layoutPie();
        }
    }

    private void resetPieCenter() {
        mPieCenterX = mCenterX;
        mPieCenterY = (int) (getHeight() - 2.5f * mDeadZone);
    }

    private void layoutPie() {
        mCenterAngle = getCenterAngle();
        layoutItems(0, getRoot().getItems());
        layoutLabel(getLevel());
    }

    private void layoutLabel(int level) {
        int x = mPieCenterX - (int) (FloatMath.sin(mCenterAngle - CENTER)
                * (mArcRadius + (level + 2) * mRadiusInc));
        int y = mArcCenterY - mArcRadius - (level + 2) * mRadiusInc;
        int w = mLabel.getIntrinsicWidth();
        int h = mLabel.getIntrinsicHeight();
        mLabel.setBounds(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
    }

    private void layoutItems(int level, List<PieItem> items) {
        int extend = 1;
        Path path = makeSlice(getDegrees(0) + extend, getDegrees(SWEEP_ARC) - extend,
                mArcRadius, mArcRadius + mRadiusInc + mRadiusInc / 4,
                mPieCenterX, mArcCenterY - level * mRadiusInc);
        final int count = items.size();
        int pos = 0;
        for (PieItem item : items) {
            // shared between items
            item.setPath(path);
            float angle = getArcCenter(item, pos, count);
            int w = item.getIntrinsicWidth();
            int h = item.getIntrinsicHeight();
            // move views to outer border
            int r = mArcRadius + mRadiusInc * 2 / 3;
            int x = (int) (r * Math.cos(angle));
            int y = mArcCenterY - (level * mRadiusInc) - (int) (r * Math.sin(angle)) - h / 2;
            x = mPieCenterX + x - w / 2;
            item.setBounds(x, y, x + w, y + h);
            item.setLevel(level);
            if (item.hasItems()) {
                layoutItems(level + 1, item.getItems());
            }
            pos++;
        }
    }

    private Path makeSlice(float start, float end, int inner, int outer, int cx, int cy) {
        RectF bb =
                new RectF(cx - outer, cy - outer, cx + outer,
                        cy + outer);
        RectF bbi =
                new RectF(cx - inner, cy - inner, cx + inner,
                        cy + inner);
        Path path = new Path();
        path.arcTo(bb, start, end - start, true);
        path.arcTo(bbi, end, start - end);
        path.close();
        return path;
    }

    private float getArcCenter(PieItem item, int pos, int count) {
        return getCenter(pos, count, SWEEP_ARC);
    }

    private float getSliceCenter(PieItem item, int pos, int count) {
        float center = (getCenterAngle() - CENTER) * 0.5f + CENTER;
        return center + (count - 1) * SWEEP_SLICE / 2f
                - pos * SWEEP_SLICE;
    }

    private float getCenter(int pos, int count, float sweep) {
        return mCenterAngle + (count - 1) * sweep / 2f - pos * sweep;
    }

    private float getCenterAngle() {
        float center = CENTER;
        if (mPieCenterX < mDeadZone + mAngleZone) {
            center = CENTER - (mAngleZone - mPieCenterX + mDeadZone) * RAD24
                    / (float) mAngleZone;
        } else if (mPieCenterX > getWidth() - mDeadZone - mAngleZone) {
            center = CENTER + (mPieCenterX - (getWidth() - mDeadZone - mAngleZone)) * RAD24
                    / (float) mAngleZone;
        }
        return center;
    }

    /**
     * converts a
     *
     * @param angle from 0..PI to Android degrees (clockwise starting at 3 o'clock)
     * @return skia angle
     */
    private float getDegrees(double angle) {
        return (float) (360 - 180 * angle / Math.PI);
    }

    private void startFadeOut(final PieItem item) {
        if (mFadeIn != null) {
            mFadeIn.cancel();
        }
        if (mXFade != null) {
            mXFade.cancel();
        }
        mFadeOut = new ValueAnimator();
        mFadeOut.setFloatValues(1f, 0f);
        mFadeOut.setDuration(PIE_FADE_OUT_DURATION);
        mFadeOut.addListener(new AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                item.performClick();
                mFadeOut = null;
                deselect();
                show(false);
                mOverlay.setAlpha(1);
            }

            @Override
            public void onAnimationRepeat(Animator animator) {
            }

            @Override
            public void onAnimationCancel(Animator animator) {
            }

        });
        mFadeOut.start();
    }

    // root does not count
    private boolean hasOpenItem() {
        return mOpen.size() > 1;
    }

    // pop an item of the open item stack
    private PieItem closeOpenItem() {
        PieItem item = getOpenItem();
        mOpen.remove(mOpen.size() - 1);
        return item;
    }

    private PieItem getOpenItem() {
        return mOpen.get(mOpen.size() - 1);
    }

    // return the children either the root or parent of the current open item
    private PieItem getParent() {
        return mOpen.get(Math.max(0, mOpen.size() - 2));
    }

    private int getLevel() {
        return mOpen.size() - 1;
    }

    @Override
    public void onDraw(Canvas canvas) {
        mProgressRenderer.onDraw(canvas, mFocusX, mFocusY);

        float alpha = 1;
        if (mXFade != null) {
            alpha = (Float) mXFade.getAnimatedValue();
        } else if (mFadeIn != null) {
            alpha = (Float) mFadeIn.getAnimatedValue();
        } else if (mFadeOut != null) {
            alpha = (Float) mFadeOut.getAnimatedValue();
        }
        int state = canvas.save();
        if (mFadeIn != null) {
            float sf = 0.9f + alpha * 0.1f;
            canvas.scale(sf, sf, mPieCenterX, mPieCenterY);
        }
        if (mState != STATE_PIE) {
            drawFocus(canvas);
        }
        if (mState == STATE_FINISHING) {
            canvas.restoreToCount(state);
            return;
        }
        if (mState != STATE_PIE) return;
        if (!hasOpenItem() || (mXFade != null)) {
            // draw base menu
            drawArc(canvas, getLevel(), getParent());
            List<PieItem> items = getParent().getItems();
            final int count = items.size();
            int pos = 0;
            for (PieItem item : getParent().getItems()) {
                drawItem(Math.max(0, mOpen.size() - 2), pos, count, canvas, item, alpha);
                pos++;
            }
            mLabel.draw(canvas);
        }
        if (hasOpenItem()) {
            int level = getLevel();
            drawArc(canvas, level, getOpenItem());
            List<PieItem> items = getOpenItem().getItems();
            final int count = items.size();
            int pos = 0;
            for (PieItem inner : items) {
                if (mFadeOut != null) {
                    drawItem(level, pos, count, canvas, inner, alpha);
                } else {
                    drawItem(level, pos, count, canvas, inner, (mXFade != null) ? (1 - 0.5f * alpha) : 1);
                }
                pos++;
            }
            mLabel.draw(canvas);
        }
        canvas.restoreToCount(state);
    }

    private void drawArc(Canvas canvas, int level, PieItem item) {
        // arc
        if (mState == STATE_PIE) {
            final int count = item.getItems().size();
            float start = mCenterAngle + (count * SWEEP_ARC / 2f);
            float end =  mCenterAngle - (count * SWEEP_ARC / 2f);
            int cy = mArcCenterY - level * mRadiusInc;
            canvas.drawArc(new RectF(mPieCenterX - mArcRadius, cy - mArcRadius,
                    mPieCenterX + mArcRadius, cy + mArcRadius),
                    getDegrees(end), getDegrees(start) - getDegrees(end), false, mMenuArcPaint);
        }
    }

    private void drawItem(int level, int pos, int count, Canvas canvas, PieItem item, float alpha) {
        if (mState == STATE_PIE) {
            if (item.getPath() != null) {
                int y = mArcCenterY - level * mRadiusInc;
                if (item.isSelected()) {
                    Paint p = mSelectedPaint;
                    int state = canvas.save();
                    float angle = 0;
                    if (mSlice != null) {
                        angle = (Float) mSlice.getAnimatedValue();
                    } else {
                        angle = getArcCenter(item, pos, count) - SWEEP_ARC / 2f;
                    }
                    angle = getDegrees(angle);
                    canvas.rotate(angle, mPieCenterX, y);
                    if (mFadeOut != null) {
                        p.setAlpha((int) (255 * alpha));
                    }
                    canvas.drawPath(item.getPath(), p);
                    if (mFadeOut != null) {
                        p.setAlpha(255);
                    }
                    canvas.restoreToCount(state);
                }
                if (mFadeOut == null) {
                    alpha = alpha * (item.isEnabled() ? 1 : 0.3f);
                    // draw the item view
                    item.setAlpha(alpha);
                }
                item.draw(canvas);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent evt) {
        if (!CameraActivity.isPieMenuEnabled())
            return false;
        float x = evt.getX();
        float y = evt.getY();
        int action = evt.getActionMasked();
        getPolar(x, y, !mTapMode, mPolar);
        if (MotionEvent.ACTION_DOWN == action) {
            if ((x < mDeadZone) || (x > getWidth() - mDeadZone)) {
                return false;
            }
            mDown.x = (int) evt.getX();
            mDown.y = (int) evt.getY();
            mOpening = false;
            if (mTapMode) {
                PieItem item = findItem(mPolar);
                if ((item != null) && (mCurrentItem != item)) {
                    mState = STATE_PIE;
                    onEnter(item);
                }
            } else {
                setCenter((int) x, (int) y);
                show(true);
            }
            return true;
        } else if (MotionEvent.ACTION_UP == action) {
            if (isVisible()) {
                PieItem item = mCurrentItem;
                if (mTapMode) {
                    item = findItem(mPolar);
                    if (mOpening) {
                        mOpening = false;
                        return true;
                    }
                }
                if (item == null) {
                    mTapMode = false;
                    show(false);
                } else if (!mOpening && !item.hasItems()) {
                        startFadeOut(item);
                        mTapMode = false;
                } else {
                    mTapMode = true;
                }
                return true;
            }
        } else if (MotionEvent.ACTION_CANCEL == action) {
            if (isVisible() || mTapMode) {
                show(false);
            }
            deselect();
            mHandler.removeMessages(MSG_OPENSUBMENU);
            return false;
        } else if (MotionEvent.ACTION_MOVE == action) {
            if (pulledToCenter(mPolar)) {
                mHandler.removeMessages(MSG_OPENSUBMENU);
                if (hasOpenItem()) {
                    if (mCurrentItem != null) {
                        mCurrentItem.setSelected(false);
                    }
                    closeOpenItem();
                    mCurrentItem = null;
                } else {
                    deselect();
                }
                mLabel.setText("");
                return false;
            }
            PieItem item = findItem(mPolar);
            boolean moved = hasMoved(evt);
            if ((item != null) && (mCurrentItem != item) && (!mOpening || moved)) {
                mHandler.removeMessages(MSG_OPENSUBMENU);
                // only select if we didn't just open or have moved past slop
                if (moved) {
                    // switch back to swipe mode
                    mTapMode = false;
                }
                onEnterSelect(item);
                mHandler.sendEmptyMessageDelayed(MSG_OPENSUBMENU, PIE_OPEN_SUB_DELAY);
            }
        }
        return false;
    }

    @Override
    public boolean isVisible() {
        return super.isVisible() || mProgressRenderer.isVisible();
    }

    private boolean pulledToCenter(PointF polarCoords) {
        return polarCoords.y < mArcRadius - mRadiusInc;
    }

    private boolean inside(PointF polar, PieItem item, int pos, int count) {
        float start = getSliceCenter(item, pos, count) - SWEEP_SLICE / 2f;
        return (mArcRadius < polar.y)
                && (start < polar.x)
                && (start + SWEEP_SLICE > polar.x)
                && (!mTapMode || (mArcRadius + mRadiusInc > polar.y));
    }

    private void getPolar(float x, float y, boolean useOffset, PointF res) {
        // get angle and radius from x/y
        res.x = (float) Math.PI / 2;
        x = x - mPieCenterX;
        float y1 = mSliceCenterY - getLevel() * mRadiusInc - y;
        float y2 = mArcCenterY - getLevel() * mRadiusInc - y;
        res.y = (float) Math.sqrt(x * x + y2 * y2);
        if (x != 0) {
            res.x = (float) Math.atan2(y1,  x);
            if (res.x < 0) {
                res.x = (float) (2 * Math.PI + res.x);
            }
        }
        res.y = res.y + (useOffset ? mTouchOffset : 0);
    }

    private boolean hasMoved(MotionEvent e) {
        return mTouchSlopSquared < (e.getX() - mDown.x) * (e.getX() - mDown.x)
                + (e.getY() - mDown.y) * (e.getY() - mDown.y);
    }

    private void onEnterSelect(PieItem item) {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (item != null && item.isEnabled()) {
            moveSelection(mCurrentItem, item);
            item.setSelected(true);
            mCurrentItem = item;
            mLabel.setText(mCurrentItem.getLabel());
            layoutLabel(getLevel());
        } else {
            mCurrentItem = null;
        }
    }

    private void onEnterOpen() {
        if ((mCurrentItem != null) && (mCurrentItem != getOpenItem()) && mCurrentItem.hasItems()) {
            openCurrentItem();
        }
    }

    /**
     * enter a slice for a view
     * updates model only
     *
     * @param item
     */
    private void onEnter(PieItem item) {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (item != null && item.isEnabled()) {
            item.setSelected(true);
            mCurrentItem = item;
            mLabel.setText(mCurrentItem.getLabel());
            if ((mCurrentItem != getOpenItem()) && mCurrentItem.hasItems()) {
                openCurrentItem();
                layoutLabel(getLevel());
            }
        } else {
            mCurrentItem = null;
        }
    }

    private void deselect() {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (hasOpenItem()) {
            PieItem item = closeOpenItem();
            onEnter(item);
        } else {
            mCurrentItem = null;
        }
    }

    private int getItemPos(PieItem target) {
        List<PieItem> items = getOpenItem().getItems();
        return items.indexOf(target);
    }

    private int getCurrentCount() {
        return getOpenItem().getItems().size();
    }

    private void moveSelection(PieItem from, PieItem to) {
        final int count = getCurrentCount();
        final int fromPos = getItemPos(from);
        final int toPos = getItemPos(to);
        if (fromPos != -1 && toPos != -1) {
            float startAngle = getArcCenter(from, getItemPos(from), count)
                    - SWEEP_ARC / 2f;
            float endAngle = getArcCenter(to, getItemPos(to), count)
                    - SWEEP_ARC / 2f;
            mSlice = new ValueAnimator();
            mSlice.setFloatValues(startAngle, endAngle);
            // linear interpolater
            mSlice.setInterpolator(null);
            mSlice.setDuration(PIE_SLICE_DURATION);
            mSlice.addListener(new AnimatorListener() {
                @Override
                public void onAnimationEnd(Animator arg0) {
                    mSlice = null;
                }

                @Override
                public void onAnimationRepeat(Animator arg0) {
                }

                @Override
                public void onAnimationStart(Animator arg0) {
                }

                @Override
                public void onAnimationCancel(Animator arg0) {
                }
            });
            mSlice.start();
        }
    }

    private void openCurrentItem() {
        if ((mCurrentItem != null) && mCurrentItem.hasItems()) {
            mOpen.add(mCurrentItem);
            layoutLabel(getLevel());
            mOpening = true;
            if (mFadeIn != null) {
                mFadeIn.cancel();
            }
            mXFade = new ValueAnimator();
            mXFade.setFloatValues(1f, 0f);
            mXFade.setDuration(PIE_XFADE_DURATION);
            // Linear interpolation
            mXFade.setInterpolator(null);
            final PieItem ci = mCurrentItem;
            mXFade.addListener(new AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mXFade = null;
                    ci.setSelected(false);
                    mOpening = false;
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }

                @Override
                public void onAnimationCancel(Animator arg0) {
                }
            });
            mXFade.start();
        }
    }

    /**
     * @param polar x: angle, y: dist
     * @return the item at angle/dist or null
     */
    private PieItem findItem(PointF polar) {
        // find the matching item:
        List<PieItem> items = getOpenItem().getItems();
        final int count = items.size();
        int pos = 0;
        for (PieItem item : items) {
            if (inside(polar, item, pos, count)) {
                return item;
            }
            pos++;
        }
        return null;
    }


    @Override
    public boolean handlesTouch() {
        return true;
    }

    // focus specific code

    public void setBlockFocus(boolean blocked) {
        mBlockFocus = blocked;
        if (blocked) {
            clear();
        }
    }

    public void setFocus(int x, int y) {
        mOverlay.removeCallbacks(mDisappear);
        mFocusX = x;
        mFocusY = y;
    }

    private void drawFocus(Canvas canvas) {
        if (mBlockFocus) return;
        canvas.drawCircle(mFocusX, mFocusY, mFocusOuterRadius, mFocusOuterCirclePaint);
        if (mState == STATE_PIE) return;
        canvas.drawCircle(mFocusX, mFocusY, mFocusInnerRadius, mFocusInnerCirclePaint);
    }

    @Override
    public void showStart() {
        if (mState == STATE_PIE) return;
        setVisible(true);
        cancelFocus();

        mState = STATE_FOCUSING;
        mFocusOuterCirclePaint.setAlpha(255);
        mFocusInnerCirclePaint.setAlpha(FOCUS_INNER_CIRCLE_ALPHA);
        mAnimator = ValueAnimator.ofInt(mFocusCircleRadius + mFocusCircleRadiusOffset,
                mFocusCircleRadius - mFocusCircleRadiusOffset, mFocusCircleRadius);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mFocusOuterRadius = (int) animation.getAnimatedValue();
                mFocusInnerRadius = mFocusOuterRadius;
                update();
            }
        });
        mAnimator.setDuration(300);
        mAnimator.start();
    }

    @Override
    public void showSuccess(final boolean timeout) {
        if (mState == STATE_FOCUSING) {
            setVisible(true);
            cancelFocusAnimation();
            mState = STATE_FINISHING;

            mAnimator = ValueAnimator.ofInt(mFocusCircleRadius,
                    mFocusInnerCircleRadius - mFocusCircleRadiusOffset,
                    mFocusInnerCircleRadius);
            mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mFocusInnerRadius = (int) animation.getAnimatedValue();
                    update();
                }
            });
            mAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (timeout) {
                        mOverlay.postDelayed(mDisappear, DISAPPEAR_TIMEOUT);
                    }
                }
            });
            mAnimator.setDuration(300);

            mAlphaAnimator = ValueAnimator.ofInt(mFocusInnerCirclePaint.getAlpha(), 255);
            mAlphaAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mFocusInnerCirclePaint.setAlpha((int) animation.getAnimatedValue());
                }
            });
            mAlphaAnimator.setDuration(300);

            mAnimator.start();
            mAlphaAnimator.start();
        }
    }

    public void setSurfaceArea(RectF bounds) {
        mCenterX = Math.round(bounds.width() / 2f + bounds.left);
        mCenterY = Math.round(bounds.height() / 2f + bounds.top);
    }

    @Override
    public void showFail(boolean timeout) {
        if (mState == STATE_FOCUSING) {
            cancelFocusAnimation();
            mState = STATE_FINISHING;
            update();
        }
    }

    private void cancelFocus() {
        mOverlay.removeCallbacks(mDisappear);
        cancelFocusAnimation();
        mState = STATE_IDLE;
    }

    private void cancelFocusAnimation() {
        if (mAnimator != null && mAnimator.isStarted()) {
            mAnimator.cancel();
        }
        if (mAlphaAnimator != null && mAlphaAnimator.isStarted()) {
            mAlphaAnimator.cancel();
        }
    }

    public void clear(boolean waitUntilProgressIsHidden) {
        if (mState == STATE_PIE) return;
        cancelFocus();

        if (waitUntilProgressIsHidden) {
            mProgressRenderer.setVisibilityListener(new VisibilityListener() {
                @Override
                public void onHidden() {
                    mOverlay.post(mDisappear);
                }
            });
        } else {
            mOverlay.post(mDisappear);
            mProgressRenderer.setVisibilityListener(null);
        }
    }

    @Override
    public void clear() {
        clear(false);
    }

    private class Disappear implements Runnable {

        private Runnable mEnding = new Runnable() {
            @Override
            public void run() {
                setVisible(false);
                mFocusX = mCenterX;
                mFocusY = mCenterY;
                mState = STATE_IDLE;
            }
        };

        @Override
        public void run() {
            if (mState == STATE_PIE) return;
            cancelFocusAnimation();
            mAnimator = ValueAnimator.ofInt(0, mFocusCircleRadiusOffset);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                    int offset = (int) animation.getAnimatedValue();
                    mFocusOuterRadius += offset;
                    mFocusInnerRadius += offset;
                    update();
            }
        });
            mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    mOverlay.post(mEnding);
            }
            });
            mAnimator.setDuration(300);

            mAlphaAnimator = ValueAnimator.ofInt(255, 0);
            mAlphaAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    int alpha = (int) animation.getAnimatedValue();
                    mFocusOuterCirclePaint.setAlpha(alpha);
                    mFocusInnerCirclePaint.setAlpha(alpha);
            }
        });
            mAlphaAnimator.setDuration(300);

            mAnimator.start();
            mAlphaAnimator.start();
        }
    }

}
