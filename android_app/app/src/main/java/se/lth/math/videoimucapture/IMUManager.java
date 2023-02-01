package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorEvent;
import android.hardware.SensorEventCallback;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;


public class IMUManager extends SensorEventCallback {
    private static final String TAG = "IMUManager";
    private int ACC_TYPE;
    private int GYRO_TYPE;
    private int MAG_TYPE;

    // if the accelerometer data has a timestamp within the
    // [t-x, t+x] of the gyro data at t, then the original acceleration data
    // is used instead of linear interpolation
    private final long mInterpolationTimeResolution = 500; // nanoseconds
    private final int mSensorRate = 10000; //Us, 100Hz
    private long mEstimatedSensorRate = 0; // ns
    private long mPrevTimestamp = 0; // ns
    private float[] mSensorPlacement = null;

    private static class SensorPacket {
        long timestamp;
        float[] values;

        SensorPacket(long time, float[] vals) {
            timestamp = time;
            values = vals;
        }
    }

    private static class SyncedSensorPacket {
        long timestamp;
        float[] acc_values;
        float[] gyro_values;
        float[] mag_values;

        SyncedSensorPacket(long time, float[] acc, float[] gyro, float[] mag) {
            timestamp = time;
            acc_values = acc;
            gyro_values = gyro;
            mag_values = mag;
        }
    }

    // Sensor listeners
    private SensorManager mSensorManager;
    private Sensor mAccel;
    private Sensor mGyro;
    private Sensor mMag;

    private int linear_acc; // accuracy
    private int angular_acc;
    private int mag_acc;

    private volatile boolean mRecordingInertialData = false;
    private RecordingWriter mRecordingWriter = null;
    private HandlerThread mSensorThread;

    private Deque<SensorPacket> mGyroData = new ArrayDeque<>();
    private Deque<SensorPacket> mAccelData = new ArrayDeque<>();
    private Deque<SensorPacket> mMagData = new ArrayDeque<>();

    public IMUManager(Activity activity) {
        super();
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        setSensorType();
        mAccel = mSensorManager.getDefaultSensor(ACC_TYPE);
        mGyro = mSensorManager.getDefaultSensor(GYRO_TYPE);
        mMag = mSensorManager.getDefaultSensor(MAG_TYPE);
    }

    private void setSensorType() {
        if (Build.VERSION.SDK_INT >= 26)
            ACC_TYPE = Sensor.TYPE_ACCELEROMETER_UNCALIBRATED;
        else
            ACC_TYPE = Sensor.TYPE_ACCELEROMETER;
        GYRO_TYPE = Sensor.TYPE_GYROSCOPE_UNCALIBRATED;
        MAG_TYPE = Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED;
    }

    private float[] linearInterpolate(Deque<SensorPacket> queue, SensorPacket reference) {
        // target's timestamp is assumed to be within the range of queue's timestamp

        SensorPacket left = null;
        SensorPacket right = null;
        Iterator<SensorPacket> itr = queue.iterator();

        // find the closest data right next to gyro's timestamp
        while (itr.hasNext()) {
            SensorPacket packet = itr.next();

            // using <= and >= as sometimes there is not enough data & left/right is null
            if (packet.timestamp <= reference.timestamp) {
                left = packet;
            } else if (packet.timestamp >= reference.timestamp) {
                right = packet;
                break;
            }
        }

        float[] data;
        if (reference.timestamp - left.timestamp <= mInterpolationTimeResolution) {
            data = left.values;
        } else if (right.timestamp - reference.timestamp <= mInterpolationTimeResolution) {
            data = right.values;
        } else {
            float ratio = (float)(reference.timestamp - left.timestamp) /
                    (right.timestamp - left.timestamp);
            data = new float[left.values.length]; // could vary depending on sensor type
            for (int i = 0 ; i < left.values.length ; i++) {
                data[i] = left.values[i] +
                        (right.values[i] - left.values[i]) * ratio;
            }
        }

        // Remove the current element from the iterator and the list.
        for (Iterator<SensorPacket> iterator = queue.iterator(); iterator.hasNext(); ) {
            SensorPacket packet = iterator.next();
            if (packet.timestamp < left.timestamp) {
                iterator.remove();
            } else {
                break;
            }
        }

        return data;
    }

    public Boolean sensorsExist() {
        return (mAccel != null) && (mGyro != null) && (mMag != null);
    }

    public void startRecording(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
        writeMetaData();
        mRecordingInertialData = true;
    }

    public void stopRecording() {
        mRecordingInertialData = false;
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == ACC_TYPE) {
            linear_acc = accuracy;
        } else if (sensor.getType() == GYRO_TYPE) {
            angular_acc = accuracy;
        } else if (sensor.getType() == MAG_TYPE) {
            mag_acc = accuracy;
        }
    }

    // sync inertial data by interpolating linear acceleration for each gyro data
    // Because the sensor events are delivered to the handler thread in order,
    // no need for synchronization here
    private SyncedSensorPacket syncInertialData() {
        if (mGyroData.size() >= 1 && mAccelData.size() >= 2 && mMagData.size() >= 2) {
            // take gyro as reference
            SensorPacket oldestGyro = mGyroData.peekFirst();

            // interpolate accel and mag
            SensorPacket oldestAccel = mAccelData.peekFirst();
            SensorPacket latestAccel = mAccelData.peekLast();
            SensorPacket oldestMag = mMagData.peekFirst();
            SensorPacket latestMag = mMagData.peekLast();

            if (oldestGyro.timestamp < oldestAccel.timestamp || oldestGyro.timestamp < oldestMag.timestamp) {
                // check if gyro data is within range of mag & accel data
                Log.w(TAG, "throwing one gyro data");
                mGyroData.removeFirst();
            } else if (oldestGyro.timestamp > latestAccel.timestamp) {
                Log.w(TAG, "throwing #accel data " + (mAccelData.size() - 1));
                mAccelData.clear();
                mAccelData.add(latestAccel);
            } else if (oldestGyro.timestamp > latestMag.timestamp) {
                Log.d(TAG, "throwing #mag data " + (mMagData.size() - 1));
                mMagData.clear();
                mMagData.add(latestMag);
            } else { // linearly interpolate the accel & mag data at the gyro timestamp
                float[] acc_data = linearInterpolate(mAccelData, oldestGyro);
                float[] mag_data = linearInterpolate(mMagData, oldestGyro);

                mGyroData.removeFirst(); // remove the processed data

                return new SyncedSensorPacket(oldestGyro.timestamp,
                        acc_data, oldestGyro.values, mag_data);
            }
        }
        return null;
    }

    private void writeData(SyncedSensorPacket packet) {
        RecordingProtos.IMUData.Builder imuBuilder =
                RecordingProtos.IMUData.newBuilder()
                        .setTimeNs(packet.timestamp)
                        .setAccelAccuracyValue(linear_acc)
                        .setGyroAccuracyValue(angular_acc)
                        .setMagAccuracyValue(mag_acc);

        for (int i = 0 ; i < 3 ; i++) {
            imuBuilder.addGyro(packet.gyro_values[i]);
            imuBuilder.addAccel(packet.acc_values[i]);
            imuBuilder.addMag(packet.mag_values[i]);
        }
        if (ACC_TYPE == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
            for (int i = 3 ; i < 6 ; i++) {
                imuBuilder.addAccelBias(packet.acc_values[i]);
            }
        }
        if (GYRO_TYPE == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            for (int i = 3 ; i < 6 ; i++) {
                imuBuilder.addGyroDrift(packet.gyro_values[i]);
            }
        }
        if (MAG_TYPE == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            for (int i = 3 ; i < 6 ; i++) {
                imuBuilder.addMagBias(packet.mag_values[i]);
            }
        }

        mRecordingWriter.queueData(imuBuilder.build());
    }

    private void writeMetaData() {
        RecordingProtos.IMUInfo.Builder builder = RecordingProtos.IMUInfo.newBuilder();
        if (mGyro != null) {
            builder.setGyroInfo(mGyro.toString()).setGyroResolution(mGyro.getResolution());
        }
        if (mAccel != null) {
            builder.setAccelInfo(mAccel.toString()).setAccelResolution(mAccel.getResolution());
        }
        if (mMag != null) {
            builder.setMagInfo(mMag.toString()).setMagResolution(mMag.getResolution());
        }
        builder.setSampleFrequency(getSensorFrequency());

        //Store translation for sensor placement in device coordinate system.
        if (mSensorPlacement != null) {
            builder.addPlacement(mSensorPlacement[3])
                    .addPlacement(mSensorPlacement[7])
                    .addPlacement(mSensorPlacement[11]);
        }
        mRecordingWriter.queueData(builder.build());
    }

    private void updateSensorRate(SensorEvent event) {
        long diff = event.timestamp - mPrevTimestamp;
        mEstimatedSensorRate += (diff - mEstimatedSensorRate) >> 3;
        mPrevTimestamp = event.timestamp;
    }

    public float getSensorFrequency() {
        return 1e9f/((float) mEstimatedSensorRate);
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == ACC_TYPE) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mAccelData.add(sp);

            updateSensorRate(event);
        } else if (event.sensor.getType() == GYRO_TYPE) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mGyroData.add(sp);

            // sync data
            if (mRecordingInertialData) {
                SyncedSensorPacket syncedData = syncInertialData();
                if (syncedData != null)
                    writeData(syncedData);
            }
        } else if (event.sensor.getType() == MAG_TYPE) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mMagData.add(sp);
        }
    }

    @Override
    public final void onSensorAdditionalInfo(SensorAdditionalInfo info) {
        if (mSensorPlacement != null) {
            return;
        }
        if ((info.sensor == mAccel) && (info.type == SensorAdditionalInfo.TYPE_SENSOR_PLACEMENT)) {
            mSensorPlacement = info.floatValues;
        }
    }

    /**
     * This will register all IMU listeners
     * https://stackoverflow.com/questions/3286815/sensoreventlistener-in-separate-thread
     */
    public void register() {
        if (!sensorsExist()) {
            return;
        }
        mSensorThread = new HandlerThread("Sensor thread",
                Process.THREAD_PRIORITY_MORE_FAVORABLE);
        mSensorThread.start();
        // Blocks until looper is prepared, which is fairly quick
        Handler sensorHandler = new Handler(mSensorThread.getLooper());
        mSensorManager.registerListener(this, mAccel, mSensorRate, sensorHandler);
        mSensorManager.registerListener(this, mGyro, mSensorRate, sensorHandler);
        mSensorManager.registerListener(this, mMag, mSensorRate, sensorHandler);
    }

    /**
     * This will unregister all IMU listeners
     */
    public void unregister() {
        if (!sensorsExist()) {
            return;
        }
        mSensorManager.unregisterListener(this, mAccel);
        mSensorManager.unregisterListener(this, mGyro);
        mSensorManager.unregisterListener(this, mMag);
        mSensorManager.unregisterListener(this);
        mSensorThread.quitSafely();
        stopRecording();
    }
}
