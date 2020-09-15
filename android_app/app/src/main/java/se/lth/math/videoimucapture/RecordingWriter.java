package se.lth.math.videoimucapture;

import android.util.Log;

import com.google.protobuf.Timestamp;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import se.lth.math.videoimucapture.RecordingProtos.VideoCaptureData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameMetaData;
import se.lth.math.videoimucapture.RecordingProtos.VideoFrameToTimestamp;
import se.lth.math.videoimucapture.RecordingProtos.IMUData;
import se.lth.math.videoimucapture.RecordingProtos.IMUInfo;
import se.lth.math.videoimucapture.RecordingProtos.CameraInfo;
import se.lth.math.videoimucapture.RecordingProtos.MessageWrapper;

import static java.lang.Math.abs;

public class RecordingWriter implements Runnable{
    final private static String TAG = "RecordingWriter";
    final private Boolean VERBOSE = false;

    private FileOutputStream mFileStream;
    private BlockingQueue<MessageWrapper> mQueue = new ArrayBlockingQueue<>(1000);
    //Empty message as poison pill
    private final MessageWrapper mPoisonPill = MessageWrapper.newBuilder().build();

    //Queues to handle merging of video frames
    private Queue<VideoFrameMetaData> mFrameDataQueue = new ArrayBlockingQueue<>(100);
    private Queue<VideoFrameToTimestamp> mFrameTimeQueue = new ArrayBlockingQueue<>(100);

    //Other state variables
    private Boolean mIsRecording = false;

    public Boolean isRecording() {return mIsRecording;}

    public void startRecording(String resultFile) throws IOException {

        Log.d(TAG, String.format("Starting on %s thread", Thread.currentThread()));
        mFileStream = new FileOutputStream(resultFile);

        //Reset state
        mIsRecording = true;
        mFrameDataQueue.clear();
        mFrameTimeQueue.clear();
        mQueue.clear();

        //Start background thread
        Thread myThread = new Thread(this, "RecordingWriter");
        myThread.start();

    }

    public void stopRecording(){
        try {
            mQueue.put(mPoisonPill);
        } catch (InterruptedException e) {
            Log.d(TAG, "Interrupted in close.");
        }

    }


    public void run() {
        Log.d(TAG, String.format("Looping on %s thread", Thread.currentThread()));
        try {
            initializeFile();

            while (true) {
                MessageWrapper msg = mQueue.take();
                if (msg.equals(mPoisonPill)) {
                    mFileStream.flush();
                    mFileStream.close();
                    mIsRecording = false;
                    return;
                }
                writeMessage(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            //TODO:SOMETHING USEFUL
            Log.e(TAG,"Write error, SHOULD stop recording!!!!!" + e);
        }
    }

    private void initializeFile() throws IOException {
        if (VERBOSE) Log.d(TAG, String.format("Initialize on %s thread", Thread.currentThread()));
        // Set timestamp
        VideoCaptureData.Builder dataBuilder = VideoCaptureData.newBuilder();
        long millis = System.currentTimeMillis();
        Timestamp timestamp = Timestamp.newBuilder().setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1000000)).build();
        dataBuilder.setTime(timestamp);

        //Write to file
        dataBuilder.build().writeTo(mFileStream);
    }

    private void writeMessage(MessageWrapper msg) throws IOException {
        if (VERBOSE) Log.d(TAG, String.format("Queuing message on %s thread", Thread.currentThread()));

        MessageWrapper.MsgCase msgCase = msg.getMsgCase();
        switch (msgCase) {
            case FRAME_META:
                if (VERBOSE) Log.d(TAG,"Got Frame Meta");
                mFrameDataQueue.add(msg.getFrameMeta());
                tryVideoDataMerge();
                break;
            case FRAME_TIME:
                if (VERBOSE) Log.d(TAG,"Got Frame Time");
                mFrameTimeQueue.add(msg.getFrameTime());
                tryVideoDataMerge();
                break;
            case IMU_DATA:
                if (VERBOSE) Log.d(TAG,"Got IMU data");
                VideoCaptureData.newBuilder().addImu(msg.getImuData())
                        .build().writeTo(mFileStream);
                break;
            case IMU_META:
                if (VERBOSE) Log.d(TAG,"Got IMU Info");
                VideoCaptureData.newBuilder().mergeImuMeta(msg.getImuMeta())
                        .build().writeTo(mFileStream);
                break;
            case CAMERA_META:
                if (VERBOSE) Log.d(TAG,"Got Camera Meta");
                VideoCaptureData.newBuilder().mergeCameraMeta(msg.getCameraMeta())
                        .build().writeTo(mFileStream);
                break;
        }
    }


    private void tryVideoDataMerge() throws IOException {
        if (VERBOSE)  Log.d(TAG, String.format("Trying to merge, Queue lengths: %d,%d", mFrameDataQueue.size(), mFrameTimeQueue.size()));

        // Peek at oldest frame time message
        VideoFrameToTimestamp frameTimeMsg = mFrameTimeQueue.peek();
        VideoFrameMetaData frameMetaMsg = mFrameDataQueue.peek();

        //Try to find frames to match
        while ((frameTimeMsg != null) && (frameMetaMsg != null)) {
            long timeDiffNs = (1000*frameTimeMsg.getTimeUs() - frameMetaMsg.getTimeNs());
            if (VERBOSE) Log.d(TAG, String.format("Time diff: %d ns", timeDiffNs));

            if (abs(timeDiffNs) <= 10000) {
                // They are from the same capture frame
                VideoFrameMetaData.Builder frameBuilder = VideoFrameMetaData.newBuilder().mergeFrom(frameMetaMsg)
                        .setFrameNumber(frameTimeMsg.getFrameNbr());
                VideoCaptureData.newBuilder().addVideoMeta(frameBuilder).build().writeTo(mFileStream);
                // Remove frames from queue
                mFrameTimeQueue.poll();
                mFrameDataQueue.poll();
                //We are done
                break;
            } else if (timeDiffNs > 0) {
                //Meta message is too old, try another one
                mFrameDataQueue.poll(); // throw old
                frameMetaMsg = mFrameDataQueue.peek();
                Log.d(TAG, "Diff too large, skipping frame meta data");
            } else {
                // Frame Time message too old, try another one
                mFrameTimeQueue.poll(); // throw old
                frameTimeMsg = mFrameTimeQueue.peek();
                Log.d(TAG, "Diff too large, skipping frame time data");
            }
        }

    }

    private void queueData(MessageWrapper msg) {
        if (!isRecording()) {
            return;
        }
        try {
            mQueue.put(msg);
        } catch (InterruptedException e) {
            throw new RuntimeException("Could not queue data: " + msg + "due to" + e);
        }
    }
    public void queueData(VideoFrameMetaData msg) {
        queueData(MessageWrapper.newBuilder().setFrameMeta(msg).build());
    }
    public void queueData(VideoFrameToTimestamp msg) {
        queueData(MessageWrapper.newBuilder().setFrameTime(msg).build());
    }
    public void queueData(IMUData msg) {
        queueData(MessageWrapper.newBuilder().setImuData(msg).build());
    }
    public void queueData(IMUInfo msg) {
        queueData(MessageWrapper.newBuilder().setImuMeta(msg).build());
    }
    public void queueData(CameraInfo msg) {
        queueData(MessageWrapper.newBuilder().setCameraMeta(msg).build());
    }

}
