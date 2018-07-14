/**
 * Copyright (c) 2015-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.views.scroll;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.view.ViewCompat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.OverScroller;
import android.widget.ScrollView;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.uimanager.MeasureSpecAssertions;
import com.facebook.react.uimanager.ReactClippingViewGroup;
import com.facebook.react.uimanager.ReactClippingViewGroupHelper;
import com.facebook.react.uimanager.events.NativeGestureUtil;
import com.facebook.react.views.view.ReactViewBackgroundManager;
import java.lang.reflect.Field;
import javax.annotation.Nullable;

/**
 * A simple subclass of ScrollView that doesn't dispatch measure and layout to its children and has
 * a scroll listener to send scroll events to JS.
 *
 * <p>ReactScrollView only supports vertical scrolling. For horizontal scrolling,
 * use {@link ReactHorizontalScrollView}.
 */
@TargetApi(11)
public class ReactScrollView extends ScrollView implements ReactClippingViewGroup, ViewGroup.OnHierarchyChangeListener {


  private boolean mActivelyScrolling;
  private boolean mPagingEnabled = false;
  private @Nullable Runnable mPostTouchRunnable;
  private int mSnapInterval = 0;




  private static @Nullable Field sScrollerField;
  private static boolean sTriedToGetScrollerField = false;

  private final OnScrollDispatchHelper mOnScrollDispatchHelper = new OnScrollDispatchHelper();
  // private final @Nullable OverScroller mScroller;
  private final VelocityHelper mVelocityHelper = new VelocityHelper();

  private @Nullable Rect mClippingRect;
  private boolean mDoneFlinging;
  private boolean mDragging;
  private boolean mFlinging;
  private boolean mRemoveClippedSubviews;
  private boolean mScrollEnabled = true;
  private boolean mSendMomentumEvents;
  private @Nullable FpsListener mFpsListener = null;
  private @Nullable String mScrollPerfTag;
  private @Nullable Drawable mEndBackground;
  private int mEndFillColor = Color.TRANSPARENT;
  private View mContentView;
  private ReactViewBackgroundManager mReactBackgroundManager;

  public ReactScrollView(ReactContext context) {
    this(context, null);
    int currentY = getScrollY();
    Log.v("EFRRinitY","="+currentY);
  }

  public ReactScrollView(ReactContext context, @Nullable FpsListener fpsListener) {
    super(context);
    int currentY = getScrollY();
    Log.v("EFRRinitY","="+currentY);
    mFpsListener = fpsListener;
    mReactBackgroundManager = new ReactViewBackgroundManager(this);

    setOnHierarchyChangeListener(this);
    setScrollBarStyle(SCROLLBARS_OUTSIDE_OVERLAY);
  }

  // @Nullable
  // private OverScroller getOverScrollerFromParent() {
  //   OverScroller scroller;

  //   if (!sTriedToGetScrollerField) {
  //     sTriedToGetScrollerField = true;
  //     try {
  //       sScrollerField = ScrollView.class.getDeclaredField("mScroller");
  //       sScrollerField.setAccessible(true);
  //     } catch (NoSuchFieldException e) {
  //       Log.w(
  //         ReactConstants.TAG,
  //         "Failed to get mScroller field for ScrollView! " +
  //           "This app will exhibit the bounce-back scrolling bug :(");
  //     }
  //   }

  //   if (sScrollerField != null) {
  //     try {
  //       Object scrollerValue = sScrollerField.get(this);
  //       if (scrollerValue instanceof OverScroller) {
  //         scroller = (OverScroller) scrollerValue;
  //       } else {
  //         Log.w(
  //           ReactConstants.TAG,
  //           "Failed to cast mScroller field in ScrollView (probably due to OEM changes to AOSP)! " +
  //             "This app will exhibit the bounce-back scrolling bug :(");
  //         scroller = null;
  //       }
  //     } catch (IllegalAccessException e) {
  //       throw new RuntimeException("Failed to get mScroller from ScrollView!", e);
  //     }
  //   } else {
  //     scroller = null;
  //   }

  //   return scroller;
  // }

  public void setSendMomentumEvents(boolean sendMomentumEvents) {
    mSendMomentumEvents = sendMomentumEvents;
  }

  public void setScrollPerfTag(@Nullable String scrollPerfTag) {
    mScrollPerfTag = scrollPerfTag;
  }

  public void setScrollEnabled(boolean scrollEnabled) {
    mScrollEnabled = scrollEnabled;
  }
public void setSnapInterval(int snapInterval) {
    mSnapInterval = snapInterval;
  }
public void setPagingEnabled(boolean pagingEnabled) {
    mPagingEnabled = pagingEnabled;
  }
  public void flashScrollIndicators() {
    awakenScrollBars();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    MeasureSpecAssertions.assertExplicitMeasureSpec(widthMeasureSpec, heightMeasureSpec);

    setMeasuredDimension(
        MeasureSpec.getSize(widthMeasureSpec),
        MeasureSpec.getSize(heightMeasureSpec));
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    // Call with the present values in order to re-layout if necessary
    scrollTo(getScrollX(), getScrollY());
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onScrollChanged(int x, int y, int oldX, int oldY) {
    super.onScrollChanged(x, y, oldX, oldY);
mActivelyScrolling = true;
    if (mOnScrollDispatchHelper.onScrollChanged(x, y)) {
      if (mRemoveClippedSubviews) {
        updateClippingRect();
      }

     ReactScrollViewHelper.emitScrollEvent(
        this,
        mOnScrollDispatchHelper.getXFlingVelocity(),
        mOnScrollDispatchHelper.getYFlingVelocity());
    }
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (!mScrollEnabled) {
      return false;
    }

    try {
      if (super.onInterceptTouchEvent(ev)) {
        NativeGestureUtil.notifyNativeGestureStarted(this, ev);
        ReactScrollViewHelper.emitScrollBeginDragEvent(this);
        mDragging = true;
        enableFpsListener();
        return true;
      }
    } catch (IllegalArgumentException e) {
      // Log and ignore the error. This seems to be a bug in the android SDK and
      // this is the commonly accepted workaround.
      // https://tinyurl.com/mw6qkod (Stack Overflow)
      Log.w(ReactConstants.TAG, "Error intercepting touch event.", e);
    }

    return false;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!mScrollEnabled) {
      return false;
    }

    mVelocityHelper.calculateVelocity(ev);
    int action = ev.getAction() & MotionEvent.ACTION_MASK;
    if (action == MotionEvent.ACTION_UP && mDragging) {
      float velocityX = mVelocityHelper.getXVelocity();
      float velocityY = mVelocityHelper.getYVelocity();
      
      ReactScrollViewHelper.emitScrollEndDragEvent(
        this,
        velocityX,
        velocityY);
      mDragging = false;
     
      handlePostTouchScrolling(Math.round(velocityX), Math.round(velocityY));
    }

    return super.onTouchEvent(ev);
  }

  @Override
  public void setRemoveClippedSubviews(boolean removeClippedSubviews) {
    if (removeClippedSubviews && mClippingRect == null) {
      mClippingRect = new Rect();
    }
    mRemoveClippedSubviews = removeClippedSubviews;
    updateClippingRect();
  }

  @Override
  public boolean getRemoveClippedSubviews() {
    return mRemoveClippedSubviews;
  }

  @Override
  public void updateClippingRect() {
    if (!mRemoveClippedSubviews) {
      return;
    }

    Assertions.assertNotNull(mClippingRect);

    ReactClippingViewGroupHelper.calculateClippingRect(this, mClippingRect);
    View contentView = getChildAt(0);
    if (contentView instanceof ReactClippingViewGroup) {
      ((ReactClippingViewGroup) contentView).updateClippingRect();
    }
  }

  @Override
  public void getClippingRect(Rect outClippingRect) {
    outClippingRect.set(Assertions.assertNotNull(mClippingRect));
  }
 private int getSnapInterval() {
    if (mSnapInterval != 0) {
      return mSnapInterval;
    }
    return getHeight();
  }
  @Override
  public void fling(int velocityY) {
    if (mPagingEnabled) {
      smoothScrollToPage(velocityY);
    } else {
      super.fling(velocityY);
    }
    handlePostTouchScrolling(0,velocityY);
  }

  private void enableFpsListener() {
    if (isScrollPerfLoggingEnabled()) {
      Assertions.assertNotNull(mFpsListener);
      Assertions.assertNotNull(mScrollPerfTag);
      mFpsListener.enable(mScrollPerfTag);
    }
  }

  private void disableFpsListener() {
    if (isScrollPerfLoggingEnabled()) {
      Assertions.assertNotNull(mFpsListener);
      Assertions.assertNotNull(mScrollPerfTag);
      mFpsListener.disable(mScrollPerfTag);
    }
  }

  private boolean isScrollPerfLoggingEnabled() {
    return mFpsListener != null && mScrollPerfTag != null && !mScrollPerfTag.isEmpty();
  }

  

  @Override
  public void draw(Canvas canvas) {
    if (mEndFillColor != Color.TRANSPARENT) {
      final View content = getChildAt(0);
      if (mEndBackground != null && content != null && content.getBottom() < getHeight()) {
        mEndBackground.setBounds(0, content.getBottom(), getWidth(), getHeight());
        mEndBackground.draw(canvas);
      }
    }
    super.draw(canvas);
  }
private void handlePostTouchScrolling(int velocityX, int velocityY) {
    // If we aren't going to do anything (send events or snap to page), we can early out.
    if (!mSendMomentumEvents && !mPagingEnabled && !isScrollPerfLoggingEnabled()) {
      return;
    }

    // Check if we are already handling this which may occur if this is called by both the touch up
    // and a fling call
    if (mPostTouchRunnable != null) {
      return;
    }

    if (mSendMomentumEvents) {
      ReactScrollViewHelper.emitScrollMomentumBeginEvent(this, velocityX, velocityY);
    }

    mActivelyScrolling = false;
    mPostTouchRunnable = new Runnable() {

      private boolean mSnappingToPage = false;

      @Override
      public void run() {
        if (mActivelyScrolling) {
          // We are still scrolling so we just post to check again a frame later
          mActivelyScrolling = false;
          ViewCompat.postOnAnimationDelayed(ReactScrollView.this,
            this,
            ReactScrollViewHelper.MOMENTUM_DELAY);
        } else {
          if (mPagingEnabled && !mSnappingToPage) {
            // Only if we have pagingEnabled and we have not snapped to the page do we
            // need to continue checking for the scroll.  And we cause that scroll by asking for it
            mSnappingToPage = true;
            smoothScrollToPage(0);
            ViewCompat.postOnAnimationDelayed(ReactScrollView.this,
              this,
              ReactScrollViewHelper.MOMENTUM_DELAY);
          } else {
            if (mSendMomentumEvents) {
              ReactScrollViewHelper.emitScrollMomentumEndEvent(ReactScrollView.this);
            }
            ReactScrollView.this.mPostTouchRunnable = null;
            disableFpsListener();
          }
        }
      }
    };
    ViewCompat.postOnAnimationDelayed(ReactScrollView.this,
      mPostTouchRunnable,
      ReactScrollViewHelper.MOMENTUM_DELAY);
  }
 private void smoothScrollToPage(int velocity) {
    int height = getSnapInterval();
    int currentY = getScrollY();
    int predictedY = currentY + velocity;
    int page = currentY / height;
    if (predictedY > page * height + height / 2) {
      page = page + 1;
    }
    smoothScrollTo(getScrollX(),page * height);
  }
  public void setEndFillColor(int color) {
    if (color != mEndFillColor) {
      mEndFillColor = color;
      mEndBackground = new ColorDrawable(mEndFillColor);
    }
  }

  @Override
  protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
    // if (mScroller != null) {
    //   // FB SCROLLVIEW CHANGE

    //   // This is part two of the reimplementation of fling to fix the bounce-back bug. See #fling() for
    //   // more information.

    //   if (!mScroller.isFinished() && mScroller.getCurrY() != mScroller.getFinalY()) {
    //     int scrollRange = getMaxScrollY();
    //     if (scrollY >= scrollRange) {
    //       mScroller.abortAnimation();
    //       scrollY = scrollRange;
    //       Log.v("EFRR","scrollRange"+scrollRange);
    //     }
    //   }

    //   // END FB SCROLLVIEW CHANGE
    // }

    super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
  }

  @Override
  public void onChildViewAdded(View parent, View child) {
    mContentView = child;
    // mContentView.addOnLayoutChangeListener(this);
  }

  @Override
  public void onChildViewRemoved(View parent, View child) {
    // mContentView.removeOnLayoutChangeListener(this);
    mContentView = null;
  }

  /**
   * Called when a mContentView's layout has changed. Fixes the scroll position if it's too large
   * after the content resizes. Without this, the user would see a blank ScrollView when the scroll
   * position is larger than the ScrollView's max scroll position after the content shrinks.
   */
  

  @Override
  public void setBackgroundColor(int color) {
    mReactBackgroundManager.setBackgroundColor(color);
  }

  public void setBorderWidth(int position, float width) {
    mReactBackgroundManager.setBorderWidth(position, width);
  }

  public void setBorderColor(int position, float color, float alpha) {
    mReactBackgroundManager.setBorderColor(position, color, alpha);
  }

  public void setBorderRadius(float borderRadius) {
    mReactBackgroundManager.setBorderRadius(borderRadius);
  }

  public void setBorderRadius(float borderRadius, int position) {
    mReactBackgroundManager.setBorderRadius(borderRadius, position);
  }

  public void setBorderStyle(@Nullable String style) {
    mReactBackgroundManager.setBorderStyle(style);
  }

}
