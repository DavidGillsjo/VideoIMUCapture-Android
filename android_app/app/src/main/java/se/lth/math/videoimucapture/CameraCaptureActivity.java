/*
 * Copyright 2013 Google Inc. All rights reserved.
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

package se.lth.math.videoimucapture;

import androidx.fragment.app.Fragment;

import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.util.Size;
import android.view.MenuItem;
import android.widget.Toast;

import com.google.firebase.analytics.FirebaseAnalytics;

import java.io.File;
import java.lang.ref.WeakReference;

import static androidx.core.content.FileProvider.getUriForFile;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 * recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 * app lifecycle changes.  In particular, we need to release and reacquire the Camera
 * so that, if the user switches away from us, we're not preventing another app from
 * using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 * SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 * Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 * thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 * the Camera preview external texture with the GLSurfaceView renderer, which means the
 * EGLContext in this thread must be created with a reference to the renderer thread's
 * context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 * is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 * thread startup/shutdown, though we could generate messages from the Activity for most
 * of these things.  The EGLContext created on this thread must be shared with the
 * video encoder, and must be used to create a SurfaceTexture that is used by the
 * Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 * updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 * which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends AppCompatActivity {
    public static final String TAG = "VIMUC-Main";
    private static final boolean VERBOSE = false;

    private Camera2Proxy mCamera2Proxy = null;
    private CameraHandler mCameraHandler;
    private CameraCaptureFragment mCameraCaptureFragment;
    private CameraSettingsManager mCameraSettingsManager;

    private FirebaseAnalytics mFirebaseAnalytics;

    // this is static so it survives activity restarts
    private static TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();
    private static IMUManager mImuManager;
    private static RecordingWriter sRecordingWriter = new RecordingWriter();

    public CameraSettingsManager getmCameraSettingsManager() {
        return mCameraSettingsManager;
    }
    public CameraCaptureFragment getmCameraCaptureFragment() {
        return mCameraCaptureFragment;
    }
    public TextureMovieEncoder getsVideoEncoder() {
        return sVideoEncoder;
    }
    public RecordingWriter getsRecordingWriter() {
        return sRecordingWriter;
    }
    public CameraHandler getmCameraHandler() {
        return mCameraHandler;
    }
    public IMUManager getmImuManager() {
        return mImuManager;
    }
    public Camera2Proxy getmCamera2Proxy() {
        return mCamera2Proxy;
    }
    public FirebaseAnalytics getmFirebaseAnalytics() { return mFirebaseAnalytics; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        mCameraHandler = new CameraHandler(this);
        mCameraSettingsManager = new CameraSettingsManager(this);

        mImuManager = new IMUManager(this);

        // Obtain the FirebaseAnalytics instance.
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

        if (savedInstanceState == null) {
            CameraCaptureFragment fragment = new CameraCaptureFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.main_content, fragment)
                    .commit();
        }

        Log.d(TAG, "onCreate complete: " + this);
    }


    @Override
    protected void onResume() {
        super.onResume();

        mImuManager.register();
        Log.d(TAG, "onResume complete: " + this);
    }

    public void initializeCamera() {
        Log.d(TAG, "acquiring camera");
        if (PermissionHelper.hasCameraPermission(this)) {
            if (mCamera2Proxy == null) {
                mCamera2Proxy = new Camera2Proxy(this, mCameraSettingsManager);
                Size previewSize =
                        mCamera2Proxy.configureCamera();
                mCameraCaptureFragment.setLayoutAspectRatio(previewSize);
            }
        } else {
            PermissionHelper.requestCameraPermission(this, false);
        }
    }

    public void releaseCamera() {
        Log.d(TAG, "releasing camera");
        if (mCamera2Proxy != null) {
            mCamera2Proxy.releaseCamera();
            mCamera2Proxy = null;
        }
    }

    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        // no more frame metadata will be saved during pause


        mImuManager.unregister();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        mCameraHandler.invalidateHandler();     // paranoia
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (fragment instanceof CameraCaptureFragment) {
            mCameraCaptureFragment = (CameraCaptureFragment) fragment;
        }
    }

    public void navigateToSettings(MenuItem unused) {
        Fragment fragment = new CaptureSettings();

        getSupportFragmentManager().beginTransaction()
                .addToBackStack(CaptureSettings.TAG)
                .replace(R.id.main_content, fragment, CaptureSettings.TAG)
                .commit();
    }

    public void displayInfo(MenuItem unused) {
        Bundle args = new Bundle();
        args.putInt("title", R.string.info_dialog_title);
        args.putString("message", String.format(
                getResources().getString(R.string.info_dialog_message),
                getResultRoot()
        ));

        InfoDialogFragment newFragment = new InfoDialogFragment();
        newFragment.setArguments(args);
        newFragment.show(getSupportFragmentManager(), "info");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            PermissionHelper.launchPermissionSettings(this);
            finish();
        } else {
            mCamera2Proxy = new Camera2Proxy(this, mCameraSettingsManager);
            Size previewSize = mCamera2Proxy.configureCamera();
            // Assumes the fragment is running
            mCameraCaptureFragment.setLayoutAspectRatio(previewSize);

        }
    }

    public String getResultRoot() {
        return getExternalFilesDir(null).getAbsolutePath();
    }


    /**
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.  Messages are
     * sent from other threads, using sendMessage().
     */
    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;
        public static final int MSG_DISABLE_SURFACE_TEXTURE = 1;
        public static final int MSG_MANUAL_FOCUS = 2;

        private int viewWidth = 0;
        private int viewHeight = 0;
        private float eventX = 0;
        private float eventY = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<CameraCaptureActivity> mWeakActivity;

        public CameraHandler(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.eventX = eventX;
            this.eventY = eventY;
            Log.d(TAG, "manual focus " + eventX + " " + eventY + " " + viewWidth + " " + viewHeight);
            sendMessage(obtainMessage(MSG_MANUAL_FOCUS));
        }


        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }
            Camera2Proxy camera2proxy = activity.getmCamera2Proxy();
            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    if (camera2proxy == null) {
                        //Wait for camera to be up, push back message
                        this.sendMessageDelayed(inputMessage, 10);
                        return;
                    }
                    activity.getmCameraCaptureFragment().handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                case MSG_DISABLE_SURFACE_TEXTURE:
                    activity.getmCameraCaptureFragment().handleDisableSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                case MSG_MANUAL_FOCUS:
                    if (camera2proxy != null) {
                        // make sure it won't cause sync issues with other Camera2Proxy methods
                        camera2proxy.changeManualFocusPoint(
                                eventX, eventY, viewWidth, viewHeight);
                    }
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}




