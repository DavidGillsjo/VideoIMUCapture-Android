package se.lth.math.videoimucapture;

// estimate focal length, i.e., imaging distance in pixels, using all sorts of info
// TODO(jhuai): set default imaging distance using empirical data, see colmap

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;

public class FocalLengthHelper {

    private static final String TAG = "FocalLengthHelper";

    private float[] mIntrinsic;
    private float[] mDistortion;
    private Float mFocalLength;
    private Float mFocusDistance;
    private SizeF mPhysicalSize;
    private Size mPixelArraySize;
    private Rect mPreCorrectionSize; // This rectangle is defined relative to full pixel array; (0,0) is the top-left of the full pixel array
    private Rect mActiveSize; // This rectangle is defined relative to the full pixel array; (0,0) is the top-left of the full pixel array,
    private Rect mCropRegion; // Its The coordinate system is defined relative to the active array rectangle given in this field, with (0, 0) being the top-left of this rectangle.
    private Size mImageSize;

    public FocalLengthHelper() {

    }

    public void setmCropRegion(Rect mCropRegion) {
        this.mCropRegion = mCropRegion;
    }

    public void setmFocalLength(Float mFocalLength) {
        this.mFocalLength = mFocalLength;
    }

    public void setmFocusDistance(Float mFocusDistance) {
        this.mFocusDistance = mFocusDistance;
    }

    public void setmImageSize(Size mImageSize) {
        this.mImageSize = mImageSize;
    }

    // compute the distance between the lens and the imaging sensor, i
    // in pixels. Recall 1/focal_length = focus_distance + 1/i
    // because focal_length is very small,
    // focus_distance is often comparatively small,
    // i is often very close to the physical focal length
    // ref: https://source.android.com/devices/camera/camera3_crop_reprocess.html
    // https://stackoverflow.com/questions/39965408/what-is-the-android-camera2-api-equivalent-of-camera-parameters-gethorizontalvie
    public Float getFocalLengthPixel() {
        if (mFocalLength != null) {
            float imageDistance; // mm
            if (mFocusDistance == null || mFocusDistance == 0.f) {
                imageDistance = mFocalLength;
            } else {
                imageDistance = 1000.f / (1000.f / mFocalLength - mFocusDistance);
            }
            // ignore the effect of distortion on the active array coordinates
            float crop_aspect = (float) mCropRegion.width() /
                    ((float) mCropRegion.height());
            float image_aspect = (float) mImageSize.getWidth() /
                    ((float) mImageSize.getHeight());
            float f_image_pixel;
            if (image_aspect >= crop_aspect) {
                float scale = (float) mImageSize.getWidth() / ((float) mCropRegion.width());
                f_image_pixel = scale * imageDistance * mPixelArraySize.getWidth() /
                        mPhysicalSize.getWidth();
            } else {
                float scale = (float) mImageSize.getHeight() / ((float) mCropRegion.height());
                f_image_pixel = scale * imageDistance * mPixelArraySize.getHeight() /
                        mPhysicalSize.getHeight();
            }
            return f_image_pixel;
        }
        return null;
    }

    public void setLensParams(CameraCharacteristics result) {
        mPhysicalSize = result.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (mPhysicalSize != null)
            Log.d(TAG, "Physical size " + mPhysicalSize.toString());
        mPixelArraySize = result.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (mPixelArraySize != null)
            Log.d(TAG, "Pixel array size " + mPixelArraySize.toString());
        mActiveSize = result.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (mActiveSize != null)
            Log.d(TAG, "Active rect " + mActiveSize.toString());

        mIntrinsic = result.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (mIntrinsic != null)
            Log.d(TAG, "char lens intrinsics fx " + mIntrinsic[0] +
                    " fy " + mIntrinsic[1] +
                    " cx " + mIntrinsic[2] +
                    " cy " + mIntrinsic[3] +
                    " s " + mIntrinsic[4]);
        float[] mDistortion = result.get(CameraCharacteristics.LENS_RADIAL_DISTORTION);
        if (mDistortion != null)
            Log.d(TAG, "char lens distortion k1 " + mDistortion[0] +
                    " k2 " + mDistortion[1] +
                    " k3 " + mDistortion[2] +
                    " k4 " + mDistortion[3] +
                    " \nk5 " + mDistortion[4] +
                    " k6 " + mDistortion[5]);
        mPreCorrectionSize =
                result.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (mPreCorrectionSize != null)
            Log.d(TAG, "Precorrection rect " + mPreCorrectionSize.toString());
    }

}
