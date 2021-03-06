package com.juick.android;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.*;
import android.widget.Gallery;

import java.util.ArrayList;

/**
 * Created with IntelliJ IDEA.
 * User: san
 * Date: 8/11/12
 * Time: 1:18 AM
 * To change this template use File | Settings | File Templates.
 */
public class ImageGallery extends Gallery {

    private final int slop;
    private float initialX;
    private float initialY;
    Handler handler;

    private ScaleGestureDetector mScaleDetector;

    public ImageGallery(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        init();
    }

    public ImageGallery(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        init();
    }

    public ImageGallery(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        init();
    }

    void init() {
        handler = new Handler();
        if (Build.VERSION.SDK_INT >= 8 && mScaleDetector == null) {
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    public void recycled() {
    }

    @Override
    public void onLongPress(MotionEvent e) {
        // nothing here
    }

    public boolean showContextMenuForChild(View originalView) {
        // long press/scroll on gallery inside outer list
        // default gallery implementation passes it upper, to list
        return false;
    }

    protected void detachAllViewsFromParent() {
        int childCount = getChildCount();
        ArrayList<View> children = new ArrayList<View>();
        for(int i=0; i<childCount; i++) {
            children.add(getChildAt(i));
        }
        super.detachAllViewsFromParent();
        for (View child : children) {
            removeDetachedView(child, false);
        }
    }

//    @Override
    public boolean z_onInterceptTouchEvent(MotionEvent ev) {
        if (getAdapter().getCount() == 1) {
            return true; // dont pass horizontal scrolls to single item
        }
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                    /*
                     * Kludge: Both the gallery and the child need to see the
                     * gesture, until we know enough about it to decide who gets it.
                     */
                onTouchEvent(ev);

                initialX = ev.getX();
                initialY = ev.getY();

                return false;

            case MotionEvent.ACTION_MOVE:
                float distX = Math.abs(ev.getX() - initialX);
                float distY = Math.abs(ev.getY() - initialY);

                if (distY > distX && distY > slop)
                        /* Vertical scroll, child takes the gesture. */
                    return false;

                    /*
                     * If a horizontal scroll, we take the gesture, otherwise keep
                     * peeking.
                     */
                return true; // distX > slop;

            default:
                return false;
        }
    }

    @Override
    public boolean hasFocusable() {
        return false;
    }


    public boolean blockLayoutRequest = false;

    @Override
    public void requestLayout() {
        if (blockLayoutRequest) return;
        super.requestLayout();    //To change body of overridden methods use File | Settings | File Templates.
    }

    public void cleanup() {
        for (View view : views) {
            view.setOnTouchListener(null);
            if (view instanceof MyImageView) {
                ((MyImageView)view).destroy();
                view.setTag(MyImageView.DESTROYED_TAG, Boolean.FALSE);
            }
            if (view instanceof GIFView) {
                ((GIFView)view).setMovieFile(null);
                view.setTag(MyImageView.DESTROYED_TAG, Boolean.FALSE);
            }
            if (view instanceof IGifView) {
                ((IGifView)view).release();
            }
        }
        views.clear();
        //removeAllViewsInLayout();
    }

    /**
     * add a list of views to be destroyed on owner's recycle
     */
    ArrayList<View> views = new ArrayList<View>();
    public void addInitializedView(View wv) {
        views.add(wv);
    }

    @Override
    protected void onDetachedFromWindow() {
        cleanup();
        super.onDetachedFromWindow();    //To change body of overridden methods use File | Settings | File Templates.
    }
}
