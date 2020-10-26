package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Camera;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.RequiresApi;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class CameraSettingsManager {
    private enum Setting {OIS, OIS_DATA, DVS, VIDEO_SIZE, FOCUS_MODE, EXPOSURE_MODE, ZOOM_RATIO, PHYSICAL_CAMERA};
    private Map<Setting, CameraSetting> mCameraSettings;
    private boolean mInitialized = false;

    public CameraSettingsManager(Activity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        CameraSetting.setSharedPreferences(preferences);
        CameraSetting.setActivity(activity);
        //CameraSetting.setRestoreDefault(); // For DEBUG
        mCameraSettings = new HashMap<>();
    }

    public void updateRequestBuilder(CaptureRequest.Builder builder) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            entry.getValue().updateCaptureRequest(builder);
        }
    }

    public void updateOutputConfiguration(OutputConfiguration outputConfiguration) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            entry.getValue().updateOutputConfiguration(outputConfiguration);
        }
    }

    public void updatePreferences(PreferenceScreen preferenceScreen) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            CameraSetting cameraSetting = entry.getValue();
            cameraSetting.updatePreferenceScreen(preferenceScreen);
        }
    }

    public void updateSettings(CameraCharacteristics cameraCharacteristics) {
        if (mInitialized) {
            return;
        }
        mCameraSettings.put(Setting.OIS,
                new CameraSettingBoolean(
                        "ois",
                        cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        false
                )
        );

        if (Build.VERSION.SDK_INT > 28) {
            mCameraSettings.put(Setting.OIS_DATA,
                    new CameraSettingBoolean(
                            "ois_data",
                            cameraCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES),
                            CameraMetadata.STATISTICS_OIS_DATA_MODE_ON,
                            CaptureRequest.STATISTICS_OIS_DATA_MODE,
                            false
                )
            );
        } else {
            mCameraSettings.put(Setting.OIS_DATA,
                    new CameraSettingBoolean("ois_data", null, 1, null, false)
            );
        }

        mCameraSettings.put(Setting.DVS,
                new CameraSettingBoolean(
                        "dvs",
                        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        false
                )
        );

        mCameraSettings.put(Setting.VIDEO_SIZE, new CameraSettingVideoSize(cameraCharacteristics));
        mCameraSettings.put(Setting.FOCUS_MODE, new CameraSettingFocusMode(cameraCharacteristics));
        mCameraSettings.put(Setting.EXPOSURE_MODE, new CameraSettingExposureMode(cameraCharacteristics));
        mCameraSettings.put(Setting.ZOOM_RATIO, new CameraSettingZoomRatio(cameraCharacteristics));
        mCameraSettings.put(Setting.PHYSICAL_CAMERA, new CameraSettingPhysicalCamera(cameraCharacteristics));

        mInitialized = true;

    }

    public Boolean isInitialized() {
        return mInitialized;
    }

    public Boolean OISEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.OIS)).isOn();
    }

    public Boolean DVSEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.DVS)).isOn();
    }

    public Boolean OISDataEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.OIS_DATA)).isOn();
    }

    public Size getVideoSize() {
        return ((CameraSettingVideoSize) mCameraSettings.get(Setting.VIDEO_SIZE)).getSize();
    }

    public Boolean focusOnTouch() {
        return ((CameraSettingFocusMode) mCameraSettings.get(Setting.FOCUS_MODE)).getMode()
                == CameraSettingFocusMode.FocusMode.TOUCH_AUTO;
    }
    
}

abstract class CameraSetting {
    protected Boolean mConfigurable;
    protected CaptureRequest.Key mRequestKey;
    protected static SharedPreferences mSharedPreferences;
    protected static Activity mActivity;
    protected static boolean mRestoreDefault = false;
    protected String mPrefKey;

    public static void setSharedPreferences(SharedPreferences preferences) {mSharedPreferences=preferences;};
    public static void setActivity(Activity activity) {mActivity=activity;};
    public static void setRestoreDefault() {mRestoreDefault=true;};

    public void updatePreferenceScreen(PreferenceScreen screen) {
        Log.d("Settingsmanager", mPrefKey);
        Preference pref = screen.findPreference(mPrefKey);
        if (pref != null) {
            updatePreference(pref);
        } else {
            throw new RuntimeException("Preference not found: " + mPrefKey);
        }
    }

    protected void updatePreference(Preference preference) {

        preference.setEnabled(mConfigurable);
        preference.setPersistent(true);
    }
    public void updateCaptureRequest(CaptureRequest.Builder builder) {}
    public void updateOutputConfiguration(OutputConfiguration outputConfiguration) {}

}

//Camera setting default is handled through the Preference XML file.
class CameraSettingBoolean extends CameraSetting {
    private int mOnValue;
    private Boolean mDefaultOn;

    public CameraSettingBoolean(String prefKey,
                                int[] modes,
                                int onValue,
                                CaptureRequest.Key requestKey,
                                Boolean defaultOn) {
        mPrefKey = prefKey;
        mRequestKey = requestKey;
        mOnValue = onValue;
        mConfigurable = false;
        boolean forceDefault = true;

        //Figure out valid default value
        if ((modes == null) || (modes.length == 0)) {
            mDefaultOn = false;
        } else if (modes.length == 1) {
            mDefaultOn = (modes[0] == mOnValue);
        } else {
            mDefaultOn = defaultOn;
            mConfigurable = true;
            forceDefault = mRestoreDefault;
        }
        //Set default if not present
        if (!mSharedPreferences.contains(prefKey) || forceDefault) {
            mSharedPreferences.edit().putBoolean(prefKey, mDefaultOn).apply();
        }
    }

    public Boolean isOn() {
        return mSharedPreferences.getBoolean(mPrefKey, false);
    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        if (!mConfigurable) {
            return;
        }
        if (isOn()) {
            builder.set(mRequestKey, mOnValue);
        } else {
            builder.set(mRequestKey, 1-mOnValue);
        }
    }

    @Override
    protected void updatePreference(Preference preference) {

        ((SwitchPreferenceCompat) preference).setChecked(isOn());
        super.updatePreference(preference);
    }
}

class CameraSettingVideoSize extends CameraSetting {

    private Size[] mValidSizes;
    private static Size DEFAULT_VIDEO_SIZE = new Size(1280, 960);
    private final String mSizePrefKey = "video_size";
    private final String mMaxSensorPrefKey = "use_full_sensor";
    private final boolean DEFAULT_MAX_SENSOR = true;
    private Rect mArraySensorSize;



    public CameraSettingVideoSize(CameraCharacteristics cameraCharacteristics) {

        mArraySensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        // Update Sizes
        StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics
                .SCALER_STREAM_CONFIGURATION_MAP);
        mConfigurable = (map != null);

        if (mConfigurable) {
            mValidSizes = map.getOutputSizes(MediaRecorder.class);

            // Check if valid, find closest size if not.
            Size currentSize = getSize();
            if (!Arrays.asList(mValidSizes).contains(currentSize)) {
                Size newSize = CameraUtils.chooseVideoSize(mValidSizes,
                        currentSize.getWidth(), currentSize.getHeight(), currentSize.getWidth());
                setSize(newSize);
            }
        }

        //Set default
        if (mRestoreDefault || !mSharedPreferences.contains(mSizePrefKey)) {
            setSize(DEFAULT_VIDEO_SIZE);
        }
        if (mRestoreDefault || !mSharedPreferences.contains(mMaxSensorPrefKey)) {
            setMaxSensor(DEFAULT_MAX_SENSOR);
        }
    }

    public Size getSize() {
        return Size.parseSize(mSharedPreferences.getString(mSizePrefKey, DEFAULT_VIDEO_SIZE.toString()));
    }

    private void setSize(Size size) {
        mSharedPreferences.edit().putString(mSizePrefKey, size.toString()).apply();
    }

    private boolean getMaxSensor() {
        return mSharedPreferences.getBoolean(mMaxSensorPrefKey, DEFAULT_MAX_SENSOR);
    }

    private void setMaxSensor(boolean enable) {
        mSharedPreferences.edit().putBoolean(mMaxSensorPrefKey, enable).apply();
    }

    private Size[] getValidSizes() {
        if (!getMaxSensor()) {
            return mValidSizes;
        }

        List<Size> validSizes = new LinkedList<>();
        for (Size size : mValidSizes) {
            if (size.getWidth() == size.getHeight() * mArraySensorSize.width() / mArraySensorSize.height()) {
                validSizes.add(size);
            }
        }

        return validSizes.toArray(new Size[0]);
    }

    public void updatePreferenceScreen(PreferenceScreen screen) {
        CheckBoxPreference maxSensorPref = screen.findPreference(mMaxSensorPrefKey);
        maxSensorPref.setEnabled(mConfigurable);
        maxSensorPref.setChecked(getMaxSensor());
        maxSensorPref.setPersistent(true);
        maxSensorPref.setOnPreferenceChangeListener((preference, newValue) -> {
            setMaxSensor((boolean) newValue);
            updatePreferenceScreen(screen);
            return false;
        });

        ListPreference listPreference = screen.findPreference(mSizePrefKey);
        listPreference.setEnabled(mConfigurable);
        if (mConfigurable) {
            Size[] validSizes = getValidSizes();

            String[] stringSizes = new String[validSizes.length];
            int defaultIndex = -1;
            Size defaultSize = getSize();
            for (int i = 0; i < validSizes.length; i++) {
                stringSizes[i] = validSizes[i].toString();
                if (defaultSize.equals(validSizes[i])) {
                    defaultIndex = i;
                }
            }
            listPreference.setEntryValues(stringSizes);
            listPreference.setEntries(stringSizes);
            listPreference.setValueIndex(defaultIndex);
            listPreference.setPersistent(true);
        }
    }
}

// If logical camera, enable choosing a specific lens.
class CameraSettingPhysicalCamera extends CameraSetting {

    private List<String> mCameraIds;
    private List<String> mCameraIdsDesc;
    private static String LOGICAL_ID = "Logical"; //Means the logical device

    public CameraSettingPhysicalCamera(CameraCharacteristics cameraCharacteristics) {
        mPrefKey = "camera_id";
        //Check physical stats
        int[] capabilities = cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean multi_camera = false;
        for (int cap :  capabilities) {
            if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                multi_camera = true;
                break;
            }
        }
        if (multi_camera && Build.VERSION.SDK_INT >=28) {
            List<String> physicalCameraIds =  new ArrayList<>(cameraCharacteristics.getPhysicalCameraIds());
            Collections.sort(physicalCameraIds);
            mCameraIds = new LinkedList<>();
            mCameraIds.add(LOGICAL_ID);
            mCameraIds.addAll(physicalCameraIds);
            mCameraIdsDesc = new LinkedList<>();
            mCameraIdsDesc.add(LOGICAL_ID);
            for (String id : physicalCameraIds) {
                mCameraIdsDesc.add("Physical ID " + id);
            }
            mConfigurable = true;
        } else {
            mConfigurable = false;
        }

        //Set default
        if (mRestoreDefault || !mSharedPreferences.contains(mPrefKey)) {
            mSharedPreferences.edit().putString(mPrefKey, LOGICAL_ID).apply();
        }
    }

    public String getId() {
        return mSharedPreferences.getString(mPrefKey, LOGICAL_ID);
    }

    public void updatePreference(Preference pref) {
        ListPreference idPref = (ListPreference)pref;
        if (mConfigurable) {
            idPref.setEntryValues(mCameraIds.toArray(new String[0]));
            idPref.setEntries(mCameraIdsDesc.toArray(new String[0]));
            idPref.setValueIndex(mCameraIds.indexOf(getId()));
            idPref.setVisible(true);
            super.updatePreference(pref);
        } else {
            idPref.setVisible(false);
        }
    }

    public void updateOutputConfiguration(OutputConfiguration outputConfiguration) {
        String id = getId();
        if (!id.equals(LOGICAL_ID) && Build.VERSION.SDK_INT >= 28) {
            outputConfiguration.setPhysicalCameraId(id);
        }
    }
}

class CameraSettingFocusMode extends CameraSetting {

    enum FocusMode {CONTINUOUS_AUTO, TOUCH_AUTO, MANUAL}
    private List<FocusMode> mValidModes = new ArrayList<>();
    private final FocusMode DEFAULT_FOCUS_MODE = FocusMode.TOUCH_AUTO;
    private final float DEFAULT_FOCUS_DISTANCE = 0;
    private final float FOCUS_RESOLUTION = 0.1f;
    private Float mMinFocusDistance;
    private final String mModePrefKey = "focus_mode";
    private final String mDistancePrefKey = "focus_distance";

    public CameraSettingFocusMode(CameraCharacteristics cameraCharacteristics) {
        //Check available options
        int[] availableModes = cameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        for (int m : availableModes) {
            switch (m) {
                case CameraCharacteristics.CONTROL_AF_MODE_OFF:
                    mValidModes.add(FocusMode.MANUAL);
                    break;
                case CameraCharacteristics.CONTROL_AF_MODE_AUTO:
                    mValidModes.add(FocusMode.TOUCH_AUTO);
                    break;
                case CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                    mValidModes.add(FocusMode.CONTINUOUS_AUTO);
                    break;
            }
        }
        Collections.sort(mValidModes);

        //Check valid range
        mMinFocusDistance = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        //Set default
        if (mRestoreDefault || !mSharedPreferences.contains(mModePrefKey)) {
            mSharedPreferences.edit().putString(mModePrefKey, DEFAULT_FOCUS_MODE.toString()).apply();
        }
        if (mRestoreDefault || !mSharedPreferences.contains(mDistancePrefKey)) {
            mSharedPreferences.edit().putFloat(mDistancePrefKey, DEFAULT_FOCUS_DISTANCE).apply();
        }
    }

    public FocusMode getMode() {
        return FocusMode.valueOf(getModeString());
    }

    private String getModeString() {
        return mSharedPreferences.getString(mModePrefKey, DEFAULT_FOCUS_MODE.toString());
    }

    private float getFocusDistance() {
        return mSharedPreferences.getFloat(mDistancePrefKey, DEFAULT_FOCUS_DISTANCE);
    }

    public void updatePreferenceScreen(PreferenceScreen prefScreen) {
        ListPreference modePref = prefScreen.findPreference(mModePrefKey);

        if (modePref != null) {
            String[] focusModeEnum = new String[mValidModes.size()];
            String[] focusModeDesc = new String[mValidModes.size()];
            String[] allFocusModeDesc = mActivity.getResources().getStringArray(R.array.focus_mode_desc);
            for (int i = 0; i < mValidModes.size() ; i++) {
                FocusMode m = mValidModes.get(i);
                String mString = m.toString();
                focusModeEnum[i] = mString;
                focusModeDesc[i] = allFocusModeDesc[m.ordinal()];
            }
            modePref.setEntries(focusModeDesc);
            modePref.setEntryValues(focusModeEnum);
            modePref.setValue(getModeString());
            modePref.setPersistent(true);
        }


        FloatSeekBarPreference distPref = prefScreen.findPreference(mDistancePrefKey);
        if (mMinFocusDistance != null && mValidModes.contains(FocusMode.MANUAL)) {
            distPref.setMax(mMinFocusDistance);
            distPref.setMin(0);
            distPref.setResolution(FOCUS_RESOLUTION);
            distPref.setValue(DEFAULT_FOCUS_DISTANCE);
            distPref.setPersistent(true);
            distPref.setEnabled(getMode() == FocusMode.MANUAL);

            modePref.setOnPreferenceChangeListener((preference, newValue) -> {
                distPref.setEnabled(FocusMode.valueOf((String) newValue) == FocusMode.MANUAL);
                return true;
            });
        } else {
            distPref.setVisible(false);
        }

    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        switch (getMode()) {
            case MANUAL:
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, getFocusDistance());
                break;
            case TOUCH_AUTO:
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, getFocusDistance());
                break;
            case CONTINUOUS_AUTO:
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                break;
        }
    }
}

class CameraSettingExposureMode extends CameraSetting {

    enum Mode {AUTO, MANUAL}
    private List<Mode> mValidModes = new ArrayList<>();
    private final Mode DEFAULT_MODE = Mode.AUTO;
    private float DEFAULT_EXPOSURE_MS = 5f;
    private float MAX_EXPOSURE_MS =100f; // 100ms, Much more does not make sense
    private int DEFAULT_ISO =200;
    private int MAX_ISO =2000; // Much more does not make sense
    private final float EXPOSURE_RESOLUTION = 1e-2f;
    private Range<Float> mExposureTimeRange = null;
    private Range<Integer> mISORange = null;
    private final String mModePrefKey = "exposure_mode";
    private final String mISOPrefKey = "iso";
    private final String mExposurePrefKey = "exposure";

    public CameraSettingExposureMode(CameraCharacteristics cameraCharacteristics) {
        //Check available modes
        int[] availableModes = cameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        for (int m : availableModes) {
            switch (m) {
                case CameraCharacteristics.CONTROL_AE_MODE_OFF:
                    mValidModes.add(Mode.MANUAL);
                    break;
                case CameraCharacteristics.CONTROL_AE_MODE_ON:
                    mValidModes.add(Mode.AUTO);
                    break;
            }
        }
        Collections.sort(mValidModes);

        //Set default value
        if (mRestoreDefault || !mSharedPreferences.contains(mModePrefKey)) {
            mSharedPreferences.edit().putString(mModePrefKey, DEFAULT_MODE.toString()).apply();
        }

        // If Mode OFF is supported, check ranges.
        if (mValidModes.contains(Mode.MANUAL)) {

            //Reduce ISO range and check default
            mISORange = cameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            mISORange = mISORange.intersect(mISORange.getLower(), MAX_ISO);
            DEFAULT_ISO = mISORange.clamp(DEFAULT_ISO);

            //Reduce Exposure range and check default
            Range<Long> exposureRangeNs = cameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            exposureRangeNs = exposureRangeNs.intersect(exposureRangeNs.getLower(),
                    exposureMsToNs(MAX_EXPOSURE_MS));
            mExposureTimeRange = new Range<>(
                    exposureNsToMs(exposureRangeNs.getLower()),
                    exposureNsToMs(exposureRangeNs.getUpper())
            );
            DEFAULT_EXPOSURE_MS = mExposureTimeRange.clamp(DEFAULT_EXPOSURE_MS);

            //Set default values
            if (mRestoreDefault || !mSharedPreferences.contains(mISOPrefKey)) {
                mSharedPreferences.edit().putInt(mISOPrefKey, DEFAULT_ISO).apply();
            }
            if (mRestoreDefault || !mSharedPreferences.contains(mExposurePrefKey)) {
                mSharedPreferences.edit().putFloat(mExposurePrefKey, DEFAULT_EXPOSURE_MS).apply();
            }
        }
    }

    private float exposureNsToMs(long expNs) {
        return expNs*1e-6f;
    }
    private long exposureMsToNs(float expMs) {
        return (long) (expMs*1e6f);
    }



    public Mode getMode() {
        return Mode.valueOf(getModeString());
    }

    private String getModeString() {
        return mSharedPreferences.getString(mModePrefKey, DEFAULT_MODE.toString());
    }

    private int getISO() {
        return mSharedPreferences.getInt(mISOPrefKey, DEFAULT_ISO);
    }

    private long getExposureNs() {
        return exposureMsToNs(getExposureMs());
    }

    private float getExposureMs() {
        return mSharedPreferences.getFloat(mExposurePrefKey, DEFAULT_EXPOSURE_MS);
    }

    public void updatePreferenceScreen(PreferenceScreen prefScreen) {

        ListPreference modePref = prefScreen.findPreference(mModePrefKey);

        if (modePref != null) {
            String[] ModeEnum = new String[mValidModes.size()];
            String[] ModeDesc = new String[mValidModes.size()];
            String[] allModeDesc = mActivity.getResources().getStringArray(R.array.exposure_mode_desc);
            for (int i = 0; i < mValidModes.size() ; i++) {
                Mode m = mValidModes.get(i);
                String mString = m.toString();
                ModeEnum[i] = mString;
                ModeDesc[i] = allModeDesc[m.ordinal()];
            }
            modePref.setEntries(ModeDesc);
            modePref.setEntryValues(ModeEnum);
            modePref.setValue(getModeString());
            modePref.setPersistent(true);
        }


        FloatSeekBarPreference expPref = prefScreen.findPreference(mExposurePrefKey);
        SeekBarPreference isoPref = prefScreen.findPreference(mISOPrefKey);
        if (mValidModes.contains(Mode.MANUAL)) {
            expPref.setMax(mExposureTimeRange.getUpper());
            expPref.setMin(mExposureTimeRange.getLower());
            expPref.setResolution(EXPOSURE_RESOLUTION);
            expPref.setValue(getExposureMs());
            expPref.setPersistent(true);
            expPref.setEnabled(getMode() == Mode.MANUAL);

            isoPref.setMax(mISORange.getUpper());
            isoPref.setMin(mISORange.getLower());
            isoPref.setValue(getISO());
            isoPref.setPersistent(true);
            isoPref.setEnabled(getMode() == Mode.MANUAL);

            modePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enable = Mode.valueOf((String) newValue) == Mode.MANUAL;
                expPref.setEnabled(enable);
                isoPref.setEnabled(enable);
                return true;
            });
        } else {
            expPref.setVisible(false);
            isoPref.setVisible(false);
            modePref.setEnabled(false);
        }
    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        switch (getMode()) {
            case MANUAL:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, getExposureNs());
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, getISO());
                break;
            case AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        }
    }
}

class CameraSettingZoomRatio extends CameraSetting {
    private final float DEFAULT_ZOOM_RATIO = 1.0f;
    private final float ZOOM_RESOLUTION = 0.1f;
    private Range<Float> mZoomRange;

    public CameraSettingZoomRatio(CameraCharacteristics cameraCharacteristics) {
        mPrefKey = "zoom_ratio";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mZoomRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            mConfigurable = true;
        } else {
            mConfigurable = false;
        }

        //Set default if not present
        if (!mSharedPreferences.contains(mPrefKey) || mRestoreDefault) {
            mSharedPreferences.edit().putFloat(mPrefKey, DEFAULT_ZOOM_RATIO).apply();
        }
    }

    private float getRatio() {
        return mSharedPreferences.getFloat(mPrefKey, DEFAULT_ZOOM_RATIO);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        if (mConfigurable) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, getRatio());
        }
    }

    @Override
    protected void updatePreference(Preference preference) {
        FloatSeekBarPreference seekbarPref = (FloatSeekBarPreference)preference;
        if(mConfigurable) {
            seekbarPref.setVisible(true);
            seekbarPref.setMax(mZoomRange.getUpper());
            seekbarPref.setMin(mZoomRange.getLower());
            seekbarPref.setResolution(ZOOM_RESOLUTION);
            seekbarPref.setValue(getRatio());
            super.updatePreference(preference);
        } else {
            seekbarPref.setVisible(false);
        }
    }
}
