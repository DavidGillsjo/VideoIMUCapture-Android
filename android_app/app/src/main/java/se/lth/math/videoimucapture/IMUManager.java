package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class IMUManager implements SensorEventListener {
    private static final String TAG = "IMUManager";
    // if the accelerometer data has a timestamp within the
    // [t-x, t+x] of the gyro data at t, then the original acceleration data
    // is used instead of linear interpolation
    private final long mInterpolationTimeResolution = 500; // nanoseconds
    private final int mSensorRate = SensorManager.SENSOR_DELAY_GAME;

    private class SensorPacket {
        long timestamp;
        float[] values;

        SensorPacket(long time, float[] vals) {
            timestamp = time;
            values = vals;
        }
    }

    // Sensor listeners
    private SensorManager mSensorManager;
    private Sensor mAccel;
    private Sensor mGyro;

    private int linear_acc; // accuracy
    private int angular_acc;

    private volatile boolean mRecordingInertialData = false;
    private BufferedWriter mDataWriter = null;
    private HandlerThread mSensorThread;

    private Deque<SensorPacket> mGyroData = new ArrayDeque<>();
    private Deque<SensorPacket> mAccelData = new ArrayDeque<>();

    public IMUManager(Activity activity) {
        mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    public void startRecording(String captureResultFile) {
        try {
            mDataWriter = new BufferedWriter(
                    new FileWriter(captureResultFile, false));
            String header = "Timestamp[nanosec], gx[rad/s], gy[rad/s], gz[rad/s]," +
                    " ax[m/s^2], ay[m/s^2], az[m/s^2]\n";

            mDataWriter.write(header);
            mRecordingInertialData = true;
        } catch (IOException err) {
            System.err.println("IOException in opening inertial data writer at "
                    + captureResultFile + ": " + err.getMessage());
        }
    }

    public void stopRecording() {
        if (mRecordingInertialData) {
            mRecordingInertialData = false;
            try {
                mDataWriter.flush();
                mDataWriter.close();
            } catch (IOException err) {
                System.err.println(
                        "IOException in closing inertial data writer: " + err.getMessage());
            }
            mDataWriter = null;
        }
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            linear_acc = accuracy;
        } else if (sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            angular_acc = accuracy;
        }
    }

    // sync inertial data by interpolating linear acceleration for each gyro data
    // Because the sensor events are delivered to the handler thread in order,
    // no need for synchronization here
    private SensorPacket syncInertialData() {
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
                float[] gyro_accel = new float[6];
                SensorPacket sp = new SensorPacket(oldestGyro.timestamp, gyro_accel);
                gyro_accel[0] = oldestGyro.values[0];
                gyro_accel[1] = oldestGyro.values[1];
                gyro_accel[2] = oldestGyro.values[2];

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

                if (oldestGyro.timestamp - leftAccel.timestamp <=
                        mInterpolationTimeResolution) {
                    gyro_accel[3] = leftAccel.values[0];
                    gyro_accel[4] = leftAccel.values[1];
                    gyro_accel[5] = leftAccel.values[2];
                } else if (rightAccel.timestamp - oldestGyro.timestamp <=
                        mInterpolationTimeResolution) {
                    gyro_accel[3] = rightAccel.values[0];
                    gyro_accel[4] = rightAccel.values[1];
                    gyro_accel[5] = rightAccel.values[2];
                } else {
                    float ratio = (oldestGyro.timestamp - leftAccel.timestamp) /
                            (rightAccel.timestamp - leftAccel.timestamp);
                    gyro_accel[3] = leftAccel.values[0] +
                            (rightAccel.values[0] - leftAccel.values[0]) * ratio;
                    gyro_accel[4] = leftAccel.values[1] +
                            (rightAccel.values[1] - leftAccel.values[1]) * ratio;
                    gyro_accel[5] = leftAccel.values[2] +
                            (rightAccel.values[2] - leftAccel.values[2]) * ratio;
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
                return sp;
            }
        }
        return null;
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mAccelData.add(sp);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            SensorPacket sp = new SensorPacket(event.timestamp, event.values);
            mGyroData.add(sp);
            SensorPacket syncedData = syncInertialData();
            if (syncedData != null && mRecordingInertialData) {
                String delimiter = ",";
                StringBuilder sb = new StringBuilder();
                sb.append(syncedData.timestamp);
                for (int index = 0; index < 6; ++index) {
                    sb.append(delimiter + syncedData.values[index]);
                }
                try {
                    mDataWriter.write(sb.toString() + "\n");
                } catch (IOException ioe) {
                    System.err.println("IOException: " + ioe.getMessage());
                }
            }
        }
    }

    /**
     * This will register all IMU listeners
     * https://stackoverflow.com/questions/3286815/sensoreventlistener-in-separate-thread
     */
    public void register() {
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
        mSensorManager.unregisterListener(this, mAccel);
        mSensorManager.unregisterListener(this, mGyro);
        mSensorManager.unregisterListener(this);
        mSensorThread.quitSafely();
        stopRecording();
    }
}
