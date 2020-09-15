package se.lth.math.videoimucapture;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.NonNull;

import androidx.preference.PreferenceManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;

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
    private ImageReader mImageReader;
    private Surface mPreviewSurface;
    private SurfaceTexture mPreviewSurfaceTexture = null;

    private RecordingWriter mRecordingWriter = null;

    // https://stackoverflow.com/questions/3786825/volatile-boolean-vs-atomicboolean
    private volatile boolean mRecordingMetadata = false;

    private FocalLengthHelper mFocalLengthHelper = new FocalLengthHelper();

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

    @TargetApi(Build.VERSION_CODES.M)
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
            mFocalLengthHelper.setmImageSize(videoSize);


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

    @SuppressLint("MissingPermission")
    public void openCamera() {
        Log.v(TAG, "openCamera");
        startBackgroundThread();
        if (mCameraIdStr.isEmpty()) {
            Log.v(TAG, "openCamera - needs configuring");
            configureCamera();
        }
        try {
            mCameraManager.openCamera(mCameraIdStr, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    public void releaseCamera() {
        Log.v(TAG, "releaseCamera");
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        mPreviewSurfaceTexture = null;
        mCameraIdStr = "";
        stopRecordingCaptureResult();
        stopBackgroundThread();
    }

    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        mPreviewSurfaceTexture = surfaceTexture;
    }


    private class NumExpoIso {
        public Long mNumber;
        public Long mExposureNanos;
        public Integer mIso;

        public NumExpoIso(Long number, Long expoNanos, Integer iso) {
            mNumber = number;
            mExposureNanos = expoNanos;
            mIso = iso;
        }
    }

    private final int kMaxExpoSamples = 10;
    private ArrayList<NumExpoIso> expoStats = new ArrayList<>(kMaxExpoSamples);

    private void setExposureAndIso() {
        Long exposureNanos = CameraCaptureActivity.mDesiredExposureTime;
        Long desiredIsoL = 30L * 30000000L / exposureNanos;
        Integer desiredIso = desiredIsoL.intValue();
        if (!expoStats.isEmpty()) {
            int index = expoStats.size() / 2;
            Long actualExpo = expoStats.get(index).mExposureNanos;
            Integer actualIso = expoStats.get(index).mIso;
            if (actualExpo <= exposureNanos) {
                exposureNanos = actualExpo;
                desiredIso = actualIso;
            } else {
                desiredIsoL = actualIso * actualExpo / exposureNanos;
                desiredIso = desiredIsoL.intValue();
            }
        }

        // fix exposure
        mPreviewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
        Range<Long> exposureTimeRange = mCameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exposureTimeRange != null) {
            Log.d(TAG, "exposure time range " + exposureTimeRange.toString());
        }

        mPreviewRequestBuilder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos);
        Log.d(TAG, "Exposure time set to " + exposureNanos);

        // fix ISO
        Range<Integer> isoRange = mCameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) {
            Log.d(TAG, "ISO range " + isoRange.toString());
        }

        mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, desiredIso);
        Log.d(TAG, "ISO set to " + desiredIso);
    }

    private void initPreviewRequest() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Set control elements, we want auto white balance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

            // fix focus distance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            mPreviewRequestBuilder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
            Log.d(TAG, "Focus distance set to Infinity");

            mCameraSettingsManager.updateRequestBuilder(mPreviewRequestBuilder);

            if (mPreviewSurfaceTexture != null && mPreviewSurface == null) { // use texture view
                mPreviewSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight());
                mPreviewSurface = new Surface(mPreviewSurfaceTexture);
            }
            mPreviewRequestBuilder.addTarget(mPreviewSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(mPreviewSurface),
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
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void writeCameraInfo() {

        RecordingProtos.CameraInfo.Builder metaBuilder = RecordingProtos.CameraInfo.newBuilder()
                .setOpticalImageStabilization(mCameraSettingsManager.OISEnabled())
                .setVideoStabilization(mCameraSettingsManager.DVSEnabled());

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
            for (float e : intrinsics) {
                metaBuilder.addIntrinsicParams(e);
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


    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                @SuppressLint("NewApi")
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {

                    if (result.get(CaptureResult.CONTROL_AF_STATE) != CameraMetadata.CONTROL_AF_STATE_INACTIVE) {
                        // Auto focus has been triggered, reset trigger.
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        try {
                            mCaptureSession.setRepeatingRequest(
                                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }

                    }


                    Long number = result.getFrameNumber();
                    Long exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (expoStats.size() > kMaxExpoSamples) {
                        expoStats.subList(0, kMaxExpoSamples / 2).clear();
                    }
                    expoStats.add(new NumExpoIso(number, exposureTimeNs, iso));

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
                    ((CameraCaptureActivity) mActivity).getmCameraCaptureFragment().updateCaptureResultPanel(
                            focal_length_pix, exposureTimeNs, mCameraSettingsManager.OISEnabled(),  mCameraSettingsManager.DVSEnabled());
                }

                @Override
                public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                                CaptureResult partialResult) {
//                    Log.d(TAG, "mSessionCaptureCallback,  onCaptureProgressed");
                }
            };

    private void writeCaptureData(CaptureResult result, Float focal_length_pix) {
        RecordingProtos.VideoFrameMetaData.Builder frameBuilder = RecordingProtos.VideoFrameMetaData.newBuilder()
                .setTimeNs(result.get(CaptureResult.SENSOR_TIMESTAMP))
                .setExposureTimeNs(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                .setFrameDurationNs(result.get(CaptureResult.SENSOR_FRAME_DURATION))
                .setFrameReadoutNs(result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
                .setIso(result.get(CaptureResult.SENSOR_SENSITIVITY))
                .setFocalLengthMm(result.get(CaptureResult.LENS_FOCAL_LENGTH))
                .setFocusDistanceDiopters(result.get(CaptureResult.LENS_FOCUS_DISTANCE))
                .setEstFocalLengthPix(focal_length_pix);

        if (Build.VERSION.SDK_INT > 28) {
            OisSample[] oisSamples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES);
            if (oisSamples != null) {
                for (OisSample sample : oisSamples) {
                    RecordingProtos.VideoFrameMetaData.OISSample.Builder oisBuilder =
                            RecordingProtos.VideoFrameMetaData.OISSample.newBuilder()
                                    .setTimeNs(sample.getTimestamp())
                                    .setXShift(sample.getXshift())
                                    .setYShift(sample.getYshift());
                    frameBuilder.addOISSamples(oisBuilder);
                }
            }
        }

        mRecordingWriter.queueData(frameBuilder.build());

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
        } catch (CameraAccessException e) {
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

    void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
        final int y = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.height());
        final int x = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.width());
        final int halfTouchWidth = 400;
        final int halfTouchHeight = 400;
        MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                Math.max(y - halfTouchHeight, 0),
                halfTouchWidth * 2,
                halfTouchHeight * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                new MeteringRectangle[]{focusAreaTouch});
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        setExposureAndIso();

        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO);
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START);

        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(),
                    mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
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
