package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class IMUManager implements SensorEventListener {
    private static final String TAG = "IMUManager";
    private int ACC_TYPE;
    private int GYRO_TYPE;


    // if the accelerometer data has a timestamp within the
    // [t-x, t+x] of the gyro data at t, then the original acceleration data
    // is used instead of linear interpolation
    private final long mInterpolationTimeResolution = 500; // nanoseconds
    private final int mSensorRate = 10000; //Us, 100Hz
    private long mEstimatedSensorRate = 0; // ns
    private long mPrevTimestamp = 0; // ns

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

        SyncedSensorPacket(long time, float[] acc, float[] gyro) {
            timestamp = time;
            acc_values = acc;
            gyro_values = gyro;
        }
    }

    // Sensor listeners
    private SensorManager mSensorManager;
    private Sensor mAccel;
    private Sensor mGyro;

    private int linear_acc; // accuracy
    private int angular_acc;

    private volatile boolean mRecordingInertialData = false;
    private RecordingWriter mRecordingWriter = null;
    private HandlerThread mSensorThread;

    private Deque<SensorPacket> mGyroData = new ArrayDeque<>();
    private Deque<SensorPacket> mAccelData = new ArrayDeque<>();

    public IMUManager(Activity activity) {
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        setSensorType();
        mAccel = mSensorManager.getDefaultSensor(ACC_TYPE);
        mGyro = mSensorManager.getDefaultSensor(GYRO_TYPE);
    }

    private void setSensorType() {
        if (Build.VERSION.SDK_INT >= 26)
            ACC_TYPE = Sensor.TYPE_ACCELEROMETER_UNCALIBRATED;
        else
            ACC_TYPE = Sensor.TYPE_ACCELEROMETER;
        GYRO_TYPE = Sensor.TYPE_GYROSCOPE_UNCALIBRATED;
    }

    public Boolean sensorsExist() {
        return (mAccel != null) && (mGyro != null);
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
        }
    }

    // sync inertial data by interpolating linear acceleration for each gyro data
    // Because the sensor events are delivered to the handler thread in order,
    // no need for synchronization here
    private SyncedSensorPacket syncInertialData() {
        if (mGyroData.size() >= 1 && mAccelData.size() >= 2) {
            SensorPacket oldestGyro = mGyroData.peekFirst();
            SensorPacket oldestAccel = mAccelData.peekFirst();
            SensorPacket latestAccel = mAccelData.peekLast();
            if (oldestGyro.timestamp < oldestAccel.timestamp) {
                Log.w(TAG, "throwing one gyro data");
                mGyroData.removeFirst();
            } else if (oldestGyro.timestamp > latestAccel.timestamp) {
                Log.w(TAG, "throwing #accel data " + (mAccelData.size() - 1));
                mAccelData.clear();
                mAccelData.add(latestAccel);
            } else { // linearly interpolate the accel data at the gyro timestamp
                SensorPacket leftAccel = null;
                SensorPacket rightAccel = null;
                Iterator<SensorPacket> itr = mAccelData.iterator();
                while (itr.hasNext()) {
                    SensorPacket packet = itr.next();
                    if (packet.timestamp <= oldestGyro.timestamp) {
                        leftAccel = packet;
                    } else if (packet.timestamp >= oldestGyro.timestamp) {
                        rightAccel = packet;
                        break;
                    }
                }

                float[] acc_data;
                if (oldestGyro.timestamp - leftAccel.timestamp <=
                        mInterpolationTimeResolution) {
                    acc_data = leftAccel.values;
                } else if (rightAccel.timestamp - oldestGyro.timestamp <=
                        mInterpolationTimeResolution) {
                    acc_data = rightAccel.values;
                } else {
                    float ratio = (float)(oldestGyro.timestamp - leftAccel.timestamp) /
                            (rightAccel.timestamp - leftAccel.timestamp);
                    acc_data = new float[leftAccel.values.length];
                    for (int i = 0 ; i<leftAccel.values.length ; i++) {
                        acc_data[i] = leftAccel.values[i] +
                                (rightAccel.values[i] - leftAccel.values[i]) * ratio;
                    }
                }

                mGyroData.removeFirst();
                for (Iterator<SensorPacket> iterator = mAccelData.iterator();
                     iterator.hasNext(); ) {
                    SensorPacket packet = iterator.next();
                    if (packet.timestamp < leftAccel.timestamp) {
                        // Remove the current element from the iterator and the list.
                        iterator.remove();
                    } else {
                        break;
                    }
                }
                return new SyncedSensorPacket(oldestGyro.timestamp,
                        acc_data, oldestGyro.values);
            }
        }
        return null;
    }

    private void writeData(SyncedSensorPacket packet) {
        RecordingProtos.IMUData.Builder imuBuilder =
                RecordingProtos.IMUData.newBuilder()
                .setTimeNs(packet.timestamp)
                .setAccelAccuracyValue(linear_acc)
                .setGyroAccuracyValue(angular_acc);

        for (int i = 0 ; i < 3 ; i++) {
            imuBuilder.addGyro(packet.gyro_values[i]);
            imuBuilder.addAccel(packet.acc_values[i]);
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
        mRecordingWriter.queueData(imuBuilder.build());
    }

    private void writeMetaData() {
        RecordingProtos.IMUInfo.Builder builder = RecordingProtos.IMUInfo.newBuilder();
        if (mGyro != null) {
            builder.setGyroInfo(mGyro.toString())
                    .setGyroResolution(mGyro.getResolution());
        }
        if (mAccel != null) {
            builder.setAccelInfo(mAccel.toString())
                    .setAccelResolution(mAccel.getResolution());
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
            SyncedSensorPacket syncedData = syncInertialData();
            if (syncedData != null && mRecordingInertialData) {
                writeData(syncedData);
            }
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
        mSensorManager.registerListener(
                this, mAccel, mSensorRate, sensorHandler);
        mSensorManager.registerListener(
                this, mGyro, mSensorRate, sensorHandler);
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
        mSensorManager.unregisterListener(this);
        mSensorThread.quitSafely();
        stopRecording();
    }
}
