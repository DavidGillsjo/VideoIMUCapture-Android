package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Size;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public class CameraSettingsManager {
    private enum Setting {OIS, OIS_DATA, DVS, VIDEO_SIZE};
    private Map<Setting, CameraSetting> mCameraSettings;
    private SharedPreferences mPreferences;

    public CameraSettingsManager(Activity activity) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
        CameraSetting.setSharedPreferences(mPreferences);
        mCameraSettings = new HashMap<>();

        //Initialize settings
        PreferenceManager.setDefaultValues(activity, R.xml.settings, false);
    }

    public void updateRequestBuilder(CaptureRequest.Builder builder) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            entry.getValue().updateCaptureRequest(builder);
        }
    }

    public void updatePreferences(PreferenceScreen preferenceScreen) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            CameraSetting cameraSetting = entry.getValue();
            Preference preference = cameraSetting.findPreference(preferenceScreen);
            if (preference != null) {
                cameraSetting.updatePreference(preference);
            }
        }
    }

    public void updateSettings(CameraCharacteristics cameraCharacteristics) {
        mCameraSettings.put(Setting.OIS,
                new CameraSettingBoolean(
                        "ois",
                        cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE
                )
        );

        if (Build.VERSION.SDK_INT > 28) {
            mCameraSettings.put(Setting.OIS_DATA,
                    new CameraSettingBoolean(
                            "ois_data",
                            cameraCharacteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES),
                            CameraMetadata.STATISTICS_OIS_DATA_MODE_ON,
                            CaptureRequest.STATISTICS_OIS_DATA_MODE
                )
            );
        } else {
            mCameraSettings.put(Setting.OIS_DATA,
                    new CameraSettingBoolean("ois_data", null, 1, null)
            );
        }

        mCameraSettings.put(Setting.DVS,
                new CameraSettingBoolean(
                        "dvs",
                        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE
                )
        );

        mCameraSettings.put(Setting.VIDEO_SIZE,
                new CameraSettingVideoSize(
                        "video_size",
                        cameraCharacteristics
                )
        );

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
    
}

abstract class CameraSetting {
    protected Boolean mConfigurable;
    protected CaptureRequest.Key mRequestKey;
    protected static SharedPreferences mSharedPreferences;
    protected String mPrefKey;

    public static void setSharedPreferences(SharedPreferences preferences) {mSharedPreferences=preferences;};

    public Preference findPreference(PreferenceScreen screen) {
        return screen.findPreference(mPrefKey);
    }

    public void updatePreference(Preference preference) {

        preference.setEnabled(mConfigurable);
    }
    public abstract void updateCaptureRequest(CaptureRequest.Builder builder);
}

class CameraSettingBoolean extends CameraSetting {
    private int mOnValue;

    public CameraSettingBoolean(String prefKey,
                                int[] modes,
                                int onValue,
                                CaptureRequest.Key requestKey) {
        mPrefKey = prefKey;
        mRequestKey = requestKey;
        mOnValue = onValue;
        mConfigurable = (modes != null) && (modes.length > 1);

        if ((modes != null) && (modes.length == 1)) {
            mSharedPreferences.edit().putBoolean(prefKey, modes[0] == mOnValue).apply();
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

    public void updatePreference(Preference preference) {
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

    public void updateCaptureRequest(CaptureRequest.Builder builder) {
    }

}
