#!/usr/bin/python
import argparse
from recording_pb2 import VideoCaptureData
import os.path as osp
import os
import cv2
import csv
import rosbag
import rospy
from sensor_msgs.msg import Image
from sensor_msgs.msg import Imu
from cv_bridge import CvBridge
from pyquaternion import Quaternion
import numpy as np
import shutil

bridge = CvBridge()
NSECS_IN_SEC=long(1e9)

def convert_to_bag(proto, video_path, result_path, subsample=1, compress=True):
    #Init rosbag
    try:
        bag = rosbag.Bag(result_path, 'w')

        # Open video stream
        try:
            cap = cv2.VideoCapture(video_path)

            # Generate images from video and frame data
            for i,frame_data in enumerate(proto.video_meta):
                ret, frame = cap.read()
                if (i % subsample) == 0:
                    rosimg, timestamp = img_to_rosimg(frame, frame_data.time_ns, compress=compress)
                    bag.write("/cam0/image_raw", rosimg, timestamp)

        finally:
            cap.release()

        # Now IMU
        for imu_frame in proto.imu:
            rosimu, timestamp = imu_to_rosimu(imu_frame.time_ns, imu_frame.gyro, imu_frame.accel)
            bag.write("/imu0", rosimu, timestamp)

    except Exception as e:
        print e

    finally:
        bag.close()



def img_to_rosimg(img, timestamp_nsecs, compress = True):
    timestamp = rospy.Time(secs=timestamp_nsecs//NSECS_IN_SEC,
                           nsecs=timestamp_nsecs%NSECS_IN_SEC)

    gray_img  = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    if compress:
        rosimage = bridge.cv2_to_compressed_imgmsg(gray_img, dst_format='png')
    else:
        rosimage = bridge.cv2_to_imgmsg(gray_img, encoding="mono8")
    rosimage.header.stamp = timestamp

    return rosimage, timestamp

def imu_to_rosimu(timestamp_nsecs, omega, alpha):
    timestamp = rospy.Time(secs=timestamp_nsecs//NSECS_IN_SEC,
                           nsecs=timestamp_nsecs%NSECS_IN_SEC)

    rosimu = Imu()
    rosimu.header.stamp = timestamp
    rosimu.angular_velocity.x = omega[0]
    rosimu.angular_velocity.y = omega[1]
    rosimu.angular_velocity.z = omega[2]
    rosimu.linear_acceleration.x = alpha[0]
    rosimu.linear_acceleration.y = alpha[1]
    rosimu.linear_acceleration.z = alpha[2]

    return rosimu, timestamp


if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Convert video and proto to rosbag')
    parser.add_argument('data_dir', type=str, help='Path to folder with video_recording.mp4 and video_meta.pb3')
    parser.add_argument('--result-dir', type=str, help='Path to result folder, default same as proto', default = None)
    parser.add_argument('--subsample', type=int, help='Take every n-th video frame', default = 1)
    parser.add_argument('--raw-image', action='store_true', help='Store raw images in rosbag')
    parser.add_argument('--calibration', type=str, help='YAML file with kalibr camera and IMU calibration to copy', default = None)

    args = parser.parse_args()
    result_dir = args.result_dir if args.result_dir else osp.join(args.data_dir, 'rosbag')
    try:
        os.mkdir(result_dir)
    except OSError:
        pass

    # Read proto
    proto_path = osp.join(args.data_dir, 'video_meta.pb3')
    with open(proto_path,'rb') as f:
        proto = VideoCaptureData.FromString(f.read())

    video_path = osp.join(args.data_dir, 'video_recording.mp4')
    bag_path = osp.join(result_dir, 'recording.bag')
    convert_to_bag(proto, video_path, bag_path, args.subsample, not args.raw_image)

    if args.calibration:
        shutil.copy(args.calibration, result_dir)
