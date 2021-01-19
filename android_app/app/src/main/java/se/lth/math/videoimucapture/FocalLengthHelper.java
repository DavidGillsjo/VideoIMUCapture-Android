package se.lth.math.videoimucapture;

// estimate focal length, i.e., imaging distance in pixels, using all sorts of info
// TODO(jhuai): set default imaging distance using empirical data, see colmap

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.OisSample;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.RequiresApi;

import static java.lang.Math.abs;

public class FocalLengthHelper {

    private static final String TAG = "FocalLengthHelper";

    private float[] mIntrinsic;
    private float[] mTransformedIntrinsic;
    private float[] mDistortion;
    private Float mFocalLength;
    private Float mFocusDistance;
    private SizeF mPhysicalSize;
    private Size mPixelArraySize;
    private Rect mPreCorrectionSize; // This rectangle is defined relative to full pixel array; (0,0) is the top-left of the full pixel array
    private Rect mActiveSize; // This rectangle is defined relative to the full pixel array; (0,0) is the top-left of the full pixel array,
    private Rect mCropRegion; // Its The coordinate system is defined relative to the active array rectangle given in this field, with (0, 0) being the top-left of this rectangle.
    private Size mImageSize;
    private int mSensorOrientation;
    private Float mScale;

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

    public float getScale() { return mScale; }

    public void setImageSize(Size mImageSize) {
        this.mImageSize = mImageSize;
        if (mPreCorrectionSize != null) {
            setScale();
        }
    }

    // Assume the larger dimension will remain uncropped.
    // So even if the sensor has ratio 4:3 and the image has 16:9 we will scale the intrinsics accordingly.
    private void setScale() {
        if (mImageSize.getWidth() > mImageSize.getHeight()) {
            mScale = (float) mImageSize.getWidth() / mPreCorrectionSize.width();
        } else {
            mScale = (float) mImageSize.getHeight() / mPreCorrectionSize.height();
        }
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
        mSensorOrientation = result.get(CameraCharacteristics.SENSOR_ORIENTATION);

        if (mImageSize != null) {
            setScale();
        }
    }



    // Scale intrinsic parameters to image coordinates instead of sensor array coordinates.
    // Apply rotation in sensor coordinate system to get to device coordinate system,
    // since we store the image in device coordinate system orientation.
    public float[] getTransformedIntrinsic() {
        float scale = getScale();
        float[] transformedIntrinsic;
        float skew;
        switch (mSensorOrientation) {
            default:
            case 0:
                transformedIntrinsic =  new float[]{
                        scale*mIntrinsic[0],
                        scale*mIntrinsic[1],
                        scale*mIntrinsic[2],
                        scale*mIntrinsic[3],
                        mIntrinsic[4]};
                break;
            case 90:
                skew = abs(mIntrinsic[4]) > 1e-5 ? 1/mIntrinsic[4] : 0;
                transformedIntrinsic =  new float[]{
                        scale*mIntrinsic[1],
                        scale*mIntrinsic[0],
                        (float)mImageSize.getHeight() - scale*mIntrinsic[3] - 1,
                        scale*mIntrinsic[2],
                        skew};
                break;
            case 180:
                transformedIntrinsic =  new float[]{
                        scale*mIntrinsic[0],
                        scale*mIntrinsic[1],
                        (float)mImageSize.getWidth() - scale*mIntrinsic[2] - 1,
                        (float)mImageSize.getHeight() - scale*mIntrinsic[3] - 1,
                        mIntrinsic[4]};
                break;
            case 270:
                skew = abs(mIntrinsic[4]) > 1e-5 ? 1/mIntrinsic[4] : 0;
                transformedIntrinsic =  new float[]{
                        scale*mIntrinsic[1],
                        scale*mIntrinsic[0],
                        scale*mIntrinsic[3],
                        (float)mImageSize.getWidth() - scale*mIntrinsic[2] - 1,
                        skew};
                break;

        }
        return transformedIntrinsic;
    }



    @RequiresApi(api = Build.VERSION_CODES.P)
    public float[] transformOISSample(OisSample sample) {
        float[] xy_shift;
        float scale = getScale();
        switch (mSensorOrientation) {
            default:
            case 0:
                xy_shift =  new float[]{
                        scale*sample.getXshift(),
                        scale*sample.getYshift()};
                break;
            case 90:
                xy_shift =  new float[]{
                        -scale*sample.getYshift(),
                        scale*sample.getXshift()};
                break;
            case 180:
                xy_shift =  new float[]{
                        -scale*sample.getXshift(),
                        -scale*sample.getYshift()};
                break;
            case 270:
                xy_shift =  new float[]{
                        scale*sample.getYshift(),
                        -scale*sample.getXshift()};
                break;
        }
        return xy_shift;
    }

}
