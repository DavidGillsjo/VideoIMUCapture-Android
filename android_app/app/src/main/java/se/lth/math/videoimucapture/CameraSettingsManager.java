package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Range;
import android.util.Size;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class CameraSettingsManager {
    private enum Setting {OIS, OIS_DATA, DVS, VIDEO_SIZE, FOCUS_MODE, EXPOSURE_MODE};
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

        mCameraSettings.put(Setting.VIDEO_SIZE,
                new CameraSettingVideoSize(
                        "video_size",
                        cameraCharacteristics
                )
        );

        mCameraSettings.put(Setting.FOCUS_MODE, new CameraSettingFocusMode(cameraCharacteristics));
        mCameraSettings.put(Setting.EXPOSURE_MODE, new CameraSettingExposureMode(cameraCharacteristics));

        mInitialized = true;

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

}

class CameraSettingVideoSize extends CameraSetting {

    private Size[] mValidSizes;
    private static Size DEFAULT_VIDEO_SIZE = new Size(1280, 960);



    public CameraSettingVideoSize(String prefKey, CameraCharacteristics cameraCharacteristics) {
        mPrefKey = prefKey;

        if (mRestoreDefault) {
            mSharedPreferences.edit().remove(mPrefKey).apply();
        }

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
    }

    public Size getSize() {
        return Size.parseSize(mSharedPreferences.getString(mPrefKey, DEFAULT_VIDEO_SIZE.toString()));
    }

    private void setSize(Size size) {
        mSharedPreferences.edit().putString(mPrefKey, size.toString()).apply();
    }

    protected void updatePreference(Preference preference) {
        super.updatePreference(preference);

        if (BuildConfig.DEBUG && !(preference instanceof ListPreference)) {
            throw new AssertionError("Incorrect preference type for video size");
        }

        ListPreference listPreference = (ListPreference) preference;

        String[] stringSizes = new String[mValidSizes.length];
        int defaultIndex = -1;
        Size defaultSize = getSize();
        for (int i = 0; i < mValidSizes.length; i++) {
            stringSizes[i] = mValidSizes[i].toString();
            if (defaultSize.equals(mValidSizes[i])) {
                defaultIndex = i;
            }
        }
        listPreference.setEntryValues(stringSizes);
        listPreference.setEntries(stringSizes);
        listPreference.setValueIndex(defaultIndex);


    }

}

class CameraSettingFocusMode extends CameraSetting {

    enum FocusMode {CONTINUOUS_AUTO, TOUCH_AUTO, MANUAL}
    private List<FocusMode> mValidModes = new ArrayList<>();
    private final FocusMode DEFAULT_FOCUS_MODE = FocusMode.TOUCH_AUTO;
    private final float DEFAULT_FOCUS_DISTANCE = 0;
    private final float FOCUS_RESOLUTION = 0.1f;
    private Float mMinFocusDistance = null;
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
        distPref.setMax(mMinFocusDistance);
        distPref.setMin(0);
        distPref.setResolution(FOCUS_RESOLUTION);
        distPref.setValue(DEFAULT_FOCUS_DISTANCE);
        modePref.setPersistent(true);
        distPref.setEnabled(getMode()==FocusMode.MANUAL);

        modePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                distPref.setEnabled(FocusMode.valueOf((String) newValue) == FocusMode.MANUAL);
                return true;
            }
        });

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
        //Check available options
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

        //Reduce ISO range and check default
        mISORange = cameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        mISORange = mISORange.intersect(mISORange.getLower(), MAX_ISO);
        DEFAULT_ISO = mISORange.clamp(DEFAULT_ISO);

        //Reduce Exposure range and check default
        Range <Long> exposureRangeNs = cameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        exposureRangeNs = exposureRangeNs.intersect(exposureRangeNs.getLower(),
                                                    exposureMsToNs(MAX_EXPOSURE_MS));
        mExposureTimeRange = new Range<>(
                exposureNsToMs(exposureRangeNs.getLower()),
                exposureNsToMs(exposureRangeNs.getUpper())
        );
        DEFAULT_EXPOSURE_MS = mExposureTimeRange.clamp(DEFAULT_EXPOSURE_MS);

        //Set default values
        if (mRestoreDefault || !mSharedPreferences.contains(mModePrefKey)) {
            mSharedPreferences.edit().putString(mModePrefKey, DEFAULT_MODE.toString()).apply();
        }
        if (mRestoreDefault || !mSharedPreferences.contains(mISOPrefKey)) {
            mSharedPreferences.edit().putInt(mISOPrefKey, DEFAULT_ISO).apply();
        }
        if (mRestoreDefault || !mSharedPreferences.contains(mExposurePrefKey)) {
            mSharedPreferences.edit().putFloat(mExposurePrefKey, DEFAULT_EXPOSURE_MS).apply();
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
        expPref.setMax(mExposureTimeRange.getUpper());
        expPref.setMin(mExposureTimeRange.getLower());
        expPref.setResolution(EXPOSURE_RESOLUTION);
        expPref.setValue(getExposureMs());
        expPref.setPersistent(true);
        expPref.setEnabled(getMode()==Mode.MANUAL);

        SeekBarPreference isoPref = prefScreen.findPreference(mISOPrefKey);
        isoPref.setMax(mISORange.getUpper());
        isoPref.setMin(mISORange.getLower());
        isoPref.setValue(getISO());
        isoPref.setPersistent(true);
        isoPref.setEnabled(getMode()==Mode.MANUAL);

        modePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean enable = Mode.valueOf((String) newValue) == Mode.MANUAL;
                expPref.setEnabled(enable);
                isoPref.setEnabled(enable);
                return true;
            }
        });

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
