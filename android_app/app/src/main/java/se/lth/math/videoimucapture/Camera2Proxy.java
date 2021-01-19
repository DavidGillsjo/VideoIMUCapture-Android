package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OisSample;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;

import androidx.preference.PreferenceManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.Collections;

import static java.lang.Math.abs;

public class Camera2Proxy {

    private static final String TAG = "Camera2Proxy";

    private Activity mActivity;

    private String mCameraIdStr = "";
    private Size mPreviewSize;
    private CameraManager mCameraManager;
    private CameraSettingsManager mCameraSettingsManager;
    private CameraCharacteristics mCameraCharacteristics;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Rect sensorArraySize;

    private CaptureRequest mPreviewRequest;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Surface mPreviewSurface;
    private SurfaceTexture mPreviewSurfaceTexture = null;

    private RecordingWriter mRecordingWriter = null;

    // https://stackoverflow.com/questions/3786825/volatile-boolean-vs-atomicboolean
    private volatile boolean mRecordingMetadata = false;
    private boolean mSwappedDimensions;
    private int mSensorOrientation;
    private boolean mExposureTriggered = false;
    private boolean mFocusTriggered = false;

    private FocalLengthHelper mFocalLengthHelper = new FocalLengthHelper();

    public boolean getSwappedDimensions() {return mSwappedDimensions;}

    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "onOpened");
            mCameraDevice = camera;
            initPreviewRequest();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "onDisconnected");
            releaseCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera Open failed, error: " + error);
            releaseCamera();
        }
    };

    public void startRecordingCaptureResult(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
        mRecordingMetadata = true;
        writeCameraInfo();
    }

    public void stopRecordingCaptureResult() {
        if (mRecordingMetadata) {
            mRecordingMetadata = false;
        }
    }

    public Camera2Proxy(Activity activity, CameraSettingsManager cameraSettingsManager) {
        mActivity = activity;
        mCameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraSettingsManager = cameraSettingsManager;
    }

    public Size configureCamera() {
        try {
            mCameraIdStr = CameraUtils.getRearCameraId(mCameraManager);
            mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraIdStr);

            // Update settings to reflect Characteristics
            mCameraSettingsManager.updateSettings(mCameraCharacteristics);

            Size videoSize = mCameraSettingsManager.getVideoSize();

            sensorArraySize = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);


            mFocalLengthHelper.setLensParams(mCameraCharacteristics);
            mFocalLengthHelper.setImageSize(videoSize);


            // Find out if we need to swap dimension to get the preview size relative to sensor coordinate.
            mSensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSwappedDimensions = (mSensorOrientation == 90 || mSensorOrientation == 270);


            StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP);

            mPreviewSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    videoSize.getWidth(), videoSize.getHeight(), videoSize);
            Log.d(TAG, "Video size " + videoSize.toString() +
                    " preview size " + mPreviewSize.toString());

            logAnalyticsConfig();

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return mPreviewSize;
    }

    public void openCamera() {
        Log.v(TAG, "openCamera");
        startBackgroundThread();
        if (mCameraIdStr.isEmpty()) {
            Log.v(TAG, "openCamera - needs configuring");
            configureCamera();
        }
        try {
            mCameraManager.openCamera(mCameraIdStr, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
        }
    }

    public void releaseCamera() {
        Log.v(TAG, "releaseCamera");
        stopRecordingCaptureResult();
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        mPreviewSurfaceTexture = null;
        mCameraIdStr = "";
        stopBackgroundThread();
    }

    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        mPreviewSurfaceTexture = surfaceTexture;
    }

    private void initPreviewRequest() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Set control elements, we want auto white balance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

            mCameraSettingsManager.updateRequestBuilder(mPreviewRequestBuilder);

            if (mPreviewSurfaceTexture != null && mPreviewSurface == null) { // use texture view
                mPreviewSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight());
                mPreviewSurface = new Surface(mPreviewSurfaceTexture);
            }
            mPreviewRequestBuilder.addTarget(mPreviewSurface);
            CameraCaptureSession.StateCallback cb =
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "ConfigureFailed. session: mCaptureSession");
                        }
                    };
            if (Build.VERSION.SDK_INT >= 28) {
                OutputConfiguration outputConfiguration = new OutputConfiguration(mPreviewSurface);
                mCameraSettingsManager.updateOutputConfiguration(outputConfiguration);
                mCameraDevice.createCaptureSession(new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Collections.singletonList(outputConfiguration),
                        r -> mBackgroundHandler.post(r),
                        cb));
            } else {
                mCameraDevice.createCaptureSession(
                        Collections.singletonList(mPreviewSurface),
                        cb,
                        mBackgroundHandler);
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void startPreview() {
        Log.v(TAG, "startPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "startPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequest, mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // IllegalStateException may happen if shutting down the camera session prior to
            // full initialization.
            e.printStackTrace();
        }
    }

    public void stopPreview() {
        Log.v(TAG, "stopPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "stopPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               TotalCaptureResult result) {


                    if (mCameraSettingsManager.focusOnTouch()) {
                        mFocusTriggered |= (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN);
                    }
                    if (mCameraSettingsManager.exposureOnTouch()) {
                        mExposureTriggered |= (result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_SEARCHING);
                    }

                    if (mFocusTriggered) {
                        //Log.d(TAG, "Focus state:" + result.get(CaptureResult.CONTROL_AF_STATE));
                        // We are handling auto-focus, cancel if focused to go back inactive state.
                        // Seems necessary on some phones, even though the documentation says otherwise.
                        if ((result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) ||
                                (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {

                            mFocusTriggered = false;

                            // Send single cancel event
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                            try {
                                mCaptureSession.capture(
                                        mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                            //Reset trigger for future calls
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        }

                    }

                    if (mCameraSettingsManager.exposureOnTouch() && !mFocusTriggered && mExposureTriggered) {
                        // We are handling auto-exposure, lock if converged.
                        // Wait for auto-focus to finish first
                        Log.d(TAG, "Exposure state:" + result.get(CaptureResult.CONTROL_AE_STATE));
                        if (result.get(CaptureResult.CONTROL_AE_STATE) != CaptureResult.CONTROL_AE_STATE_SEARCHING) {
                            mExposureTriggered = false;
                            //Lock AE
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                            try {
                                mCaptureSession.setRepeatingRequest(
                                        mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                        }
                    }

                    Long exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);

                    Float fl = result.get(CaptureResult.LENS_FOCAL_LENGTH);

                    Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

                    Rect rect = result.get(CaptureResult.SCALER_CROP_REGION);
                    mFocalLengthHelper.setmFocalLength(fl);
                    mFocalLengthHelper.setmFocusDistance(fd);
                    mFocalLengthHelper.setmCropRegion(rect);
                    Float focal_length_pix = mFocalLengthHelper.getFocalLengthPixel();

                    if (mRecordingMetadata) {
                        writeCaptureData(result, focal_length_pix);
                    }
                    ((CameraCaptureActivity) mActivity).getmCameraCaptureFragment()
                            .updateCaptureResultPanel(focal_length_pix, exposureTimeNs);
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
//                    Log.d(TAG, "mSessionCaptureCallback,  onCaptureProgressed");
                }
            };


    void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
        if (!mCameraSettingsManager.focusOnTouch() && !mCameraSettingsManager.exposureOnTouch()) {
            return;
        }
        // Set region for focus, must be present in all capture requests during auto focus.
        int x,y;
        if (mSwappedDimensions) {
            y = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.height());
            x = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.width());
        } else {
            y = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.height());
            x = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.width());
        }
        final int halfTouchWidth = 400;
        final int halfTouchHeight = 400;
        MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                Math.max(y - halfTouchHeight, 0),
                halfTouchWidth * 2,
                halfTouchHeight * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);

        // Set metering regions and AE mode
        if (mCameraSettingsManager.focusOnTouch()) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                    new MeteringRectangle[]{focusAreaTouch});
        }
        if (mCameraSettingsManager.exposureOnTouch()) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                    new MeteringRectangle[]{focusAreaTouch});
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        }
        // Update running requests with metering regions
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(),
                    mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        // Send triggers
        if (mCameraSettingsManager.focusOnTouch()) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);

            try {
                mCaptureSession.capture(
                        mPreviewRequestBuilder.build(),
                        mSessionCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            // Reset Trigger state for future requests
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        }
    }

    public void writeCameraInfo() {

        RecordingProtos.CameraInfo.Builder metaBuilder = RecordingProtos.CameraInfo.newBuilder()
                .setOpticalImageStabilization(mCameraSettingsManager.OISEnabled())
                .setVideoStabilization(mCameraSettingsManager.DVSEnabled())
                .setDistortionCorrection(mCameraSettingsManager.DistortionCorrectionEnabled())
                .setSensorOrientation(mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));

        Size resolution = mCameraSettingsManager.getVideoSize();
        metaBuilder.setResolution(
                RecordingProtos.CameraInfo.Size.newBuilder()
                        .setHeight(mSwappedDimensions ? resolution.getWidth() : resolution.getHeight())
                        .setWidth(mSwappedDimensions ?  resolution.getHeight() : resolution.getWidth())
        );
        Rect arraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        metaBuilder.setPreCorrectionActiveArraySize(
                RecordingProtos.CameraInfo.Size.newBuilder()
                        .setHeight(arraySize.height())
                        .setWidth(arraySize.width())
        );

        Integer timestamp_source = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (timestamp_source != null) {
            metaBuilder.setTimestampSourceValue(timestamp_source);
        }

        Integer focus_cal = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        if (focus_cal != null) {
            metaBuilder.setFocusCalibrationValue(focus_cal);
        }

        float[] lensTranslation = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
        if (lensTranslation != null) {
            for (float lT : lensTranslation) {
                metaBuilder.addLensPoseTranslation(lT);
            }
        }

        float[] lensRotation = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
        if (lensRotation != null) {
            for (float lR : lensRotation) {
                metaBuilder.addLensPoseRotation(lR);
            }
        }

        float[] intrinsics = mCameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if ((intrinsics != null) && (abs(intrinsics[0]) > 0)) {
            for (float e : mFocalLengthHelper.getTransformedIntrinsic()) {
                metaBuilder.addIntrinsicParams(e);
            }
            for (float e : intrinsics) {
                metaBuilder.addOriginalIntrinsicParams(e);
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            float[] distortion = mCameraCharacteristics.get(CameraCharacteristics.LENS_DISTORTION);
            if ((distortion != null) && (abs(distortion[0]) > 0)) {
                for (float e : distortion) {
                    metaBuilder.addDistortionParams(e);
                }
            }
            Integer lensPoseReference = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
            if (lensPoseReference != null) {
                metaBuilder.setLensPoseReferenceValue(lensPoseReference);
            }
        }
        mRecordingWriter.queueData(metaBuilder.build());

    }

    private void writeCaptureData(CaptureResult result, Float focal_length_pix) {
        RecordingProtos.VideoFrameMetaData.Builder frameBuilder = RecordingProtos.VideoFrameMetaData.newBuilder()
                .setTimeNs(result.get(CaptureResult.SENSOR_TIMESTAMP))
                .setFocalLengthMm(result.get(CaptureResult.LENS_FOCAL_LENGTH))
                .setEstFocalLengthPix(focal_length_pix);

        int focus_state = result.get(CaptureResult.CONTROL_AF_STATE);
        frameBuilder.setFocusLocked(focus_state != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                                 && focus_state != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN);

        // The following values are allowed to be null
        Long sExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (sExp != null) {
            frameBuilder.setExposureTimeNs(sExp);
        }

        Long sDur = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (sDur != null) {
            frameBuilder.setFrameDurationNs(sDur);
        }

        Long sRoll = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        if (sRoll != null) {
            frameBuilder.setFrameReadoutNs(sRoll);
        }

        Integer sSens = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sSens != null) {
            frameBuilder.setIso(sSens);
        }

        Float fDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (fDist != null) {
            frameBuilder.setFocusDistanceDiopters(fDist);
        }

        if (Build.VERSION.SDK_INT >= 28) {
            OisSample[] oisSamples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES);
            if (oisSamples != null) {
                for (OisSample sample : oisSamples) {
                    float[] scaledSample = mFocalLengthHelper.transformOISSample(sample);
                    RecordingProtos.VideoFrameMetaData.OISSample.Builder oisBuilder =
                            RecordingProtos.VideoFrameMetaData.OISSample.newBuilder()
                                    .setTimeNs(sample.getTimestamp())
                                    .setXShift(scaledSample[0])
                                    .setYShift(scaledSample[1]);
                    frameBuilder.addOISSamples(oisBuilder);
                }
            }
        }

        mRecordingWriter.queueData(frameBuilder.build());

    }

    private void logAnalyticsConfig() {
        Context context = mActivity;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        int versionCode = BuildConfig.VERSION_CODE;
        String CAMERA_CONFIG_VERSION_SENT = "CAMERA_CONFIG_VERSION_SENT";
        if (sharedPreferences.getInt(CAMERA_CONFIG_VERSION_SENT, 0) == versionCode) {
            Log.d(TAG, "logAnalyticsConfig already sent for this version: " + versionCode);
            return;
        }
        Log.d(TAG, "logAnalyticsConfig");
        Bundle params = new Bundle();
        params.putString("camera_id", mCameraIdStr);
        params.putString("manufacturer", Build.MANUFACTURER);
        params.putString("model", Build.MODEL);
        params.putString("sw_version", String.valueOf(Build.VERSION.SDK_INT));
        params.putString("sw_release", Build.VERSION.RELEASE);

        int camFacing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        params.putString("LENS_FACING", String.valueOf(camFacing));

        int [] camCap = mCameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        params.putString("REQUEST_AVAILABLE_CAPABILITIES", Arrays.toString(camCap));

        Integer hwLevel = mCameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        params.putString("INFO_SUPPORTED_HARDWARE_LEVEL", String.valueOf(hwLevel));

        int[] oisModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        params.putString("AVAILABLE_OPTICAL_STABILIZATION", Arrays.toString(oisModes));

        int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        params.putString("AVAILABLE_VIDEO_STABILIZATION_MODES", Arrays.toString(stabilizationModes));

        Integer calibQuality = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        params.putString("LENS_INFO_FOCUS_DISTANCE_CALIBRATION", String.valueOf(calibQuality));

        float[] intrinsicC = mCameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        params.putString("LENS_INTRINSIC_CALIBRATION", Arrays.toString(intrinsicC));

        float[] RadialD = mCameraCharacteristics.get(CameraCharacteristics.LENS_RADIAL_DISTORTION);
        params.putString("LENS_RADIAL_DISTORTION", Arrays.toString(RadialD));

        if (Build.VERSION.SDK_INT >= 28) {
            int[] oisDataModes = mCameraCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES);
            params.putString("STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES", Arrays.toString(oisDataModes));

            Integer poseRef = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
            params.putString("LENS_POSE_REFERENCE", String.valueOf(poseRef));

            float [] poseT = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
            params.putString("LENS_POSE_TRANSLATION", Arrays.toString(poseT));

            float [] poseR = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
            params.putString("LENS_POSE_ROTATION", Arrays.toString(poseR));
        }

        ((CameraCaptureActivity) mActivity).getmFirebaseAnalytics().logEvent("camera_config", params);
        sharedPreferences.edit().putInt(CAMERA_CONFIG_VERSION_SENT, versionCode).apply();
        Log.d(TAG, "Setting logAnalyticsConfig version to: " + versionCode);
    }

    private void startBackgroundThread() {
        if (mBackgroundThread == null || mBackgroundHandler == null) {
            Log.v(TAG, "startBackgroundThread");
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        Log.v(TAG, "stopBackgroundThread");
        try {
            if (mBackgroundThread != null) {
                mBackgroundThread.quitSafely();
                mBackgroundThread.join();
            }
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
