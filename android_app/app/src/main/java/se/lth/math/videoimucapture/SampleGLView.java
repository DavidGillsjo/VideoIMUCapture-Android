package se.lth.math.videoimucapture;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Created by sudamasayuki on 2018/03/14.
 */

public class SampleGLView extends GLSurfaceView implements View.OnTouchListener {

    public SampleGLView(Context context) {
        this(context, null);
    }

    public SampleGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnTouchListener(this);
    }

    private TouchListener touchListener;

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final int actionMasked = event.getActionMasked();
        if (actionMasked != MotionEvent.ACTION_DOWN) {
            return false;
        }

        if (touchListener != null) {
            touchListener.onTouch(event, v.getWidth(), v.getHeight());
        }
        return false;
    }

    public interface TouchListener {
        void onTouch(MotionEvent event, int width, int height);
    }

    public void setTouchListener(TouchListener touchListener) {
        this.touchListener = touchListener;
    }
}

