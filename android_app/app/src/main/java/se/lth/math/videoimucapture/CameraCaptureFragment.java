package se.lth.math.videoimucapture;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import se.lth.math.videoimucapture.gles.FullFrameRect;
import se.lth.math.videoimucapture.gles.Texture2dProgram;

import static android.content.Context.WINDOW_SERVICE;

public class CameraCaptureFragment extends Fragment
        implements SurfaceTexture.OnFrameAvailableListener, TextureMovieEncoder.EncoderListener {

    public static final String TAG = "VIMUC-CaptureFragment";
    private static final boolean VERBOSE = false;
    private SampleGLView mGLView;
    private CameraSurfaceRenderer mRenderer;
    private TextView mCaptureResultText;
    private AspectFrameLayout mAspectFrameLayout;

    private boolean mRecordingEnabled;      // controls button state
    private FloatingActionButton mRecordingButton;
    private FloatingActionButton mWarningButton;

    private int mCameraPreviewWidth, mCameraPreviewHeight;

    //Owned by the activity
    private CameraCaptureActivity.CameraHandler getmCameraHandler() {
        return ((CameraCaptureActivity) getActivity()).getmCameraHandler();
    };
    private TextureMovieEncoder getsVideoEncoder() {
        return ((CameraCaptureActivity) getActivity()).getsVideoEncoder();
    };
    private RecordingWriter getsRecordingWriter() {
        return ((CameraCaptureActivity) getActivity()).getsRecordingWriter();
    };
    private IMUManager getmImuManager() {
        return ((CameraCaptureActivity) getActivity()).getmImuManager();
    };
    private Camera2Proxy getmCamera2Proxy() {
        return ((CameraCaptureActivity) getActivity()).getmCamera2Proxy();
    };
    private CameraSettingsManager getmCameraSettingsManager() {
        return ((CameraCaptureActivity) getActivity()).getmCameraSettingsManager();
    };

    private String renewOutputDir() {
        SimpleDateFormat dateFormat =
                new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        String folderName = dateFormat.format(new Date());

        String dir = ((CameraCaptureActivity) getActivity()).getResultRoot();

        String outputDir = dir + File.separator + folderName;
        (new File(outputDir)).mkdirs();
        return outputDir;
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate: " + this);

        mRecordingEnabled = getsVideoEncoder().isRecording();
        getsVideoEncoder().setEncoderListener(this);

        Log.d(TAG, "onCreate complete: " + this);
    }

    // View initialization logic
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: " + this);
        return inflater.inflate(R.layout.capture_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: " + this);

        // Hook to aspectframe
        mAspectFrameLayout = view.findViewById(R.id.cameraPreview_afl);

        // Setup buttons
        mRecordingButton = view.findViewById(R.id.toggleRecording_button);
        mRecordingButton.setOnClickListener(this::clickToggleRecording);

        mWarningButton = view.findViewById(R.id.OIS_warning_button);
        mWarningButton.setOnClickListener(this::clickWarning);

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        mGLView = view.findViewById(R.id.cameraPreview_surfaceView);
        mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(
                getmCameraHandler(), getsVideoEncoder());
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mGLView.setTouchListener((event, width, height) -> {
            if (getmCameraHandler() != null) {
                getmCameraHandler().changeManualFocusPoint(
                        event.getX(), event.getY(), width, height);
            }
        });

        mCaptureResultText = view.findViewById(R.id.captureResult_text);

    }


    // updates mCameraPreviewWidth/Height
    public void setLayoutAspectRatio(Size cameraPreviewSize) {
        if (mAspectFrameLayout == null) {
            return;
        }
        Display display = ((WindowManager) getActivity().getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        mCameraPreviewWidth = cameraPreviewSize.getWidth();
        mCameraPreviewHeight = cameraPreviewSize.getHeight();
        if (display.getRotation() == Surface.ROTATION_0) {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else if (display.getRotation() == Surface.ROTATION_180) {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        }
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        ((CameraCaptureActivity) getActivity()).initializeCamera();
        Log.d(TAG, "Keeping screen on for previewing recording.");
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateControls();

        mGLView.onResume();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight, getmCamera2Proxy().getSwappedDimensions());
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mRecordingEnabled) {
            stopRecording();
        }

        ((CameraCaptureActivity) getActivity()).releaseCamera();
        Log.d(TAG, "onPause -- Pause Camera preview");
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.notifyStopPreview();
            }
        });
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });
        mGLView.onPause();

    }

    //Callback from encoder when it is finished and thread is shutting down.
    public void onEncodingFinished() {
        Log.d(TAG, "Got Encoder listener call");
        mRecordingEnabled = false;
        getActivity().runOnUiThread(() -> updateControls());
    }

    /**
     * onClick handler for "record" button.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        mRecordingEnabled = !mRecordingEnabled;
        if (mRecordingEnabled) {
            startRecording();
            updateControls();
        } else {
            // disable button until recording finish
            if (mRecordingButton != null){
                mRecordingButton.setEnabled(false);
            }
            stopRecording();
        }
    }

    public void clickWarning(View view) {
        //Display warning dialog
        CameraSettingsManager cameraSettingsManager = getmCameraSettingsManager();

        Bundle args = new Bundle();
        args.putInt("title", R.string.warning_dialog_title);

        StringBuilder builder = new StringBuilder();
        if (cameraSettingsManager.OISEnabled()) {
            builder.append("- ");
            if (cameraSettingsManager.OISDataEnabled())
                builder.append(getResources().getString(R.string.warning_text_ois_with_data));
            else
                builder.append(getResources().getString(R.string.warning_text_ois_no_data));
            builder.append("\n\n");
        }
        if (cameraSettingsManager.DVSEnabled()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_dvs));
        }
        if (cameraSettingsManager.DistortionCorrectionEnabled()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_distortion));
        }
        if (!getmImuManager().sensorsExist()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_imu_missing));
        }

        args.putString("message", builder.toString());

        InfoDialogFragment newFragment = new InfoDialogFragment();
        newFragment.setArguments(args);
        newFragment.show(getActivity().getSupportFragmentManager(), "warning");
    }

    private void enableWarning(Boolean enable) {
        if (mWarningButton == null) {
            return;
        }
        if (enable) {
            Animation anim = new AlphaAnimation(0.8f, 1.0f);
            anim.setDuration(500); //Manage the blinking time with this parameter
            anim.setStartOffset(20);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setRepeatCount(Animation.INFINITE);
            mWarningButton.setAnimation(anim);
            mWarningButton.setVisibility(View.VISIBLE);
        } else {
            mWarningButton.clearAnimation();
            mWarningButton.setVisibility(View.GONE);
        }
    }

    private void startRecording() {
        Camera2Proxy camera2Proxy = getmCamera2Proxy();
        String outputDir = renewOutputDir();
        String outputFile = outputDir + File.separator + "video_recording.mp4";
        String metaFile = outputDir + File.separator + "video_meta.pb3";
        RecordingWriter recordingWriter = getsRecordingWriter();
        try {
            recordingWriter.startRecording(metaFile);
        } catch (IOException e) {
            throw new RuntimeException("Could not start meta data recording:" + e);
        }

        mRenderer.resetOutputFiles(outputFile, recordingWriter); // this will not cause sync issues
        getmImuManager().startRecording(recordingWriter);

        if (camera2Proxy != null) {
            camera2Proxy.startRecordingCaptureResult(recordingWriter);
        } else {
            throw new RuntimeException("mCamera2Proxy should not be null upon toggling record button");
        }
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(true);
            }
        });
    }

    private void stopRecording() {
        Log.d(TAG, "Stop recording");
        Camera2Proxy camera2Proxy = getmCamera2Proxy();
        if (camera2Proxy != null) {
            camera2Proxy.stopRecordingCaptureResult();
        }
        getmImuManager().stopRecording();

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(false);
            }
        });
        getsRecordingWriter().stopRecording();
    }


    public void updateCaptureResultPanel(
            final Float fl,
            final Long exposureTimeNs) {
        final String sfl = String.format(Locale.getDefault(), "FL: %.3f", fl);
        final String sexpotime =
                exposureTimeNs == null ?
                        "null ms" :
                        String.format(Locale.getDefault(), "Exp: %.2f ms",
                                exposureTimeNs / 1000000.0);
        final String imuHz = String.format(Locale.getDefault(),  "IMU: %.0fHz",
                getmImuManager().getSensorFrequency());

        getActivity().runOnUiThread(() -> {
            if (mCaptureResultText != null) {
                mCaptureResultText.setText("|" + sfl + "|" + sexpotime + "|" + imuHz + "|");
            }
        });
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    public void updateControls() {
        if (mRecordingButton != null) {
            int id = mRecordingEnabled ?
                    R.drawable.ic_stop_record : R.drawable.ic_start_record;
            Log.d(TAG, "DRAWING: " + id);
            mRecordingButton.setImageResource(id);
            mRecordingButton.setEnabled(true);
        }

        CameraSettingsManager cameraSettingsManager = getmCameraSettingsManager();
        if ((cameraSettingsManager == null) || !cameraSettingsManager.isInitialized()) {
            // Camera settings not ready yet, may be due to slow start or waiting for camera permission. Post delayed call.
            getmCameraHandler().sendMessageDelayed(
                    getmCameraHandler().obtainMessage(CameraCaptureActivity.CameraHandler.MSG_UPDATE_WARNING),
                    200
            );
            enableWarning(false);
        } else {
            // We have camera settings, update warning accordingly.
            enableWarning(cameraSettingsManager.OISEnabled()
                    || cameraSettingsManager.DVSEnabled()
                    || cameraSettingsManager.DistortionCorrectionEnabled()
                    || !getmImuManager().sensorsExist());
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        mGLView.requestRender();

        final String sfps = String.format(Locale.getDefault(), "%.1f FPS",
                getsVideoEncoder().mFrameRate);
        String previewFacts = mCameraPreviewWidth + "x" + mCameraPreviewHeight + "@" + sfps;

        View fragmentView = getView();

        if (fragmentView != null) {
            TextView text = (TextView) fragmentView.findViewById(R.id.cameraParams_text);
            text.setText(previewFacts);
        }

    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    public void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        Camera2Proxy camera2Proxy = getmCamera2Proxy();

        if (camera2Proxy != null) {
            camera2Proxy.setPreviewSurfaceTexture(st);
            camera2Proxy.openCamera();
        } else {
            throw new RuntimeException(
                    "Try to set surface texture while camera2proxy is null");
        }
    }

    public void handleDisableSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(null);
    }

}

/**
 * Renderer object for our GLSurfaceView.
 * <p>
 * Do not call any methods here directly from another thread -- use the
 * GLSurfaceView#queueEvent() call.
 */
class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = CameraCaptureActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    private CameraCaptureActivity.CameraHandler mCameraHandler;
    private TextureMovieEncoder mVideoEncoder;
    private String mOutputFile;
    private RecordingWriter mMetadataRecorder;

    private FullFrameRect mFullScreen;

    private final float[] mSTMatrix = new float[16];
    private int mTextureId;

    private SurfaceTexture mSurfaceTexture;
    private boolean mRecordingEnabled;
    private int mRecordingStatus;
    private int mFrameCount;

    // width/height of the incoming camera preview frames
    private boolean mIncomingSizeUpdated;
    private int mIncomingWidth;
    private int mIncomingHeight;
    private boolean mSwappedVideoDimensions;


    /**
     * Constructs CameraSurfaceRenderer.
     * <p>
     *
     * @param cameraHandler Handler for communicating with UI thread
     * @param movieEncoder  video encoder object
     */
    public CameraSurfaceRenderer(CameraCaptureActivity.CameraHandler cameraHandler,
                                 TextureMovieEncoder movieEncoder) {
        mCameraHandler = cameraHandler;
        mVideoEncoder = movieEncoder;
        mTextureId = -1;

        mRecordingStatus = -1;
        mRecordingEnabled = false;
        mFrameCount = -1;

        mIncomingSizeUpdated = false;
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void resetOutputFiles(String outputFile, RecordingWriter metaRecorder) {
        mOutputFile = outputFile;
        mMetadataRecorder = metaRecorder;
    }

    /**
     * Notifies the renderer thread that the activity is pausing.
     * <p>
     * For best results, call this *after* disabling Camera preview.
     */
    public void notifyPausing() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
            mFullScreen = null;             //  to be destroyed
        }
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void notifyStopPreview() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "Stop preview");
            mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                    CameraCaptureActivity.CameraHandler.MSG_DISABLE_SURFACE_TEXTURE, mSurfaceTexture));
        }
    }

    /**
     * Notifies the renderer that we want to stop or start recording.
     */
    public void changeRecordingState(boolean isRecording) {
        Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
        mRecordingEnabled = isRecording;
        //Extra state update, in case no frame comes. May happen when recording stops.
        updateState();
    }

    /**
     * Records the size of the incoming camera preview frames.
     * <p>
     * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
     * so we assume it could go either way.  (Fortunately they both run on the same thread,
     * so we at least know that they won't execute concurrently.)
     */
    public void setCameraPreviewSize(int width, int height, boolean swappedDimensions) {
        Log.d(TAG, "setCameraPreviewSize");
        mIncomingWidth = width;
        mIncomingHeight = height;
        mSwappedVideoDimensions = swappedDimensions;
        mIncomingSizeUpdated = true;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");

        // We're starting up or coming back.  Either way we've got a new EGLContext that will
        // need to be shared with the video encoder, so figure out if a recording is already
        // in progress.
        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            mRecordingStatus = RECORDING_OFF;
        }

        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

        mTextureId = mFullScreen.createTextureObject();

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        mSurfaceTexture = new SurfaceTexture(mTextureId);

        // Tell the UI thread to enable the camera preview.
        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                CameraCaptureActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onDrawFrame(GL10 unused) {
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        boolean showBox = false;

        // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
        // was there before.
        mSurfaceTexture.updateTexImage();

        // If the recording state is changing, take care of it here.  Ideally we wouldn't
        // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
        // makes it hard to do elsewhere.
        updateState();

        // Set the video encoder's texture name.  We only need to do this once, but in the
        // current implementation it has to happen after the video encoder is started, so
        // we just do it here.
        //
        // TODO: be less lame.
        mVideoEncoder.setTextureId(mTextureId);

        // Tell the video encoder thread that a new frame is available.
        // This will be ignored if we're not actually recording.
        mVideoEncoder.frameAvailable(mSurfaceTexture);

        if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
            // Texture size isn't set yet.  This is only used for the filters, but to be
            // safe we can just skip drawing while we wait for the various races to resolve.
            // (This seems to happen if you toggle the screen off/on with power button.)
            Log.i(TAG, "Drawing before incoming texture size set; skipping");
            return;
        }

        if (mIncomingSizeUpdated) {
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            mIncomingSizeUpdated = false;
        }

        // Draw the video frame.
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        mFullScreen.drawFrame(mTextureId, mSTMatrix);

        // Draw a flashing box if we're recording.  This only appears on screen.
        showBox = (mRecordingStatus == RECORDING_ON);
        if (showBox && (++mFrameCount & 0x04) == 0) {
            drawBox();
        }
    }

    void updateState() {
        if (mRecordingEnabled) {
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    Log.d(TAG, "START recording");
                    mVideoEncoder.startRecording(
                            new TextureMovieEncoder.EncoderConfig(
                                    mOutputFile,
                                    mSwappedVideoDimensions ? mIncomingHeight : mIncomingWidth,
                                    mSwappedVideoDimensions ? mIncomingWidth : mIncomingHeight,
                                    CameraUtils.calcBitRate(mIncomingWidth,
                                            mIncomingHeight,
                                            VideoEncoderCore.FRAME_RATE),
                                    EGL14.eglGetCurrentContext(),
                                    mMetadataRecorder));
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    Log.d(TAG, "RESUME recording");
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // stop recording
                    Log.d(TAG, "STOP recording");
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }
    }

    /**
     * Draws a red box in the corner.
     */
    private void drawBox() {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, 100, 100);
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}