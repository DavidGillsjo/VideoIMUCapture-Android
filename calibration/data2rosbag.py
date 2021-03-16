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
import yaml
from utils import OpenCVDumper
import time

bridge = CvBridge()
NSECS_IN_SEC=long(1e9)

def convert_to_bag(proto, video_path, result_path, subsample=1, compress_img=False, compress_bag=False, resize = []):
    #Init rosbag
    # bz2 is better compression but lz4 is 3 times faster
    resolution = None
    try:
        bag = rosbag.Bag(result_path, 'w', compression='lz4' if compress_bag else 'none')

        # Open video stream
        try:
            cap = cv2.VideoCapture(video_path)

            # Generate images from video and frame data
            for i,frame_data in enumerate(proto.video_meta):
                ret, frame = cap.read()

                if (i % subsample) == 0:
                    rosimg, timestamp, resolution = img_to_rosimg(frame,
                                                                  frame_data.time_ns,
                                                                  compress=compress_img,
                                                                  resize = resize)
                    bag.write("/cam0/image_raw", rosimg, timestamp)

        finally:
            cap.release()

        # Now IMU
        for imu_frame in proto.imu:
            rosimu, timestamp = imu_to_rosimu(imu_frame.time_ns, imu_frame.gyro, imu_frame.accel)
            bag.write("/imu0", rosimu, timestamp)

    finally:
        bag.close()

    return resolution



def img_to_rosimg(img, timestamp_nsecs, compress = True, resize = []):
    timestamp = rospy.Time(secs=timestamp_nsecs//NSECS_IN_SEC,
                           nsecs=timestamp_nsecs%NSECS_IN_SEC)

    gray_img  = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    if resize:
        gray_img = cv2.resize(gray_img, tuple(resize), cv2.INTER_AREA)
        assert gray_img.shape[0] == resize[1]

    if compress:
        rosimage = bridge.cv2_to_compressed_imgmsg(gray_img, dst_format='png')
    else:
        rosimage = bridge.cv2_to_imgmsg(gray_img, encoding="mono8")
    rosimage.header.stamp = timestamp

    return rosimage, timestamp, (gray_img.shape[1], gray_img.shape[0])

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

def adjust_calibration(input_yaml_path, output_yaml_path, resolution):
    with open(input_yaml_path,'r') as f:
        calib = yaml.safe_load(f)

    cam0 = calib['cam0']
    if cam0['resolution'][0] != resolution[0]:
        sx = float(resolution[0])/cam0['resolution'][0]
        cam0['intrinsics'][0] *= sx
        cam0['intrinsics'][2] *= sx
        cam0['resolution'][0] = resolution[0]

    if cam0['resolution'][1] != resolution[1]:
        sy = float(resolution[1])/cam0['resolution'][1]
        cam0['intrinsics'][1] *= sy
        cam0['intrinsics'][3] *= sy
        cam0['resolution'][1] = resolution[1]

    with open(output_yaml_path,'w') as f:
        yaml.dump(calib, f, Dumper=OpenCVDumper)


def _makedir(new_dir):
    try:
        os.mkdir(result_dir)
    except OSError:
        pass

if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Convert video and proto to rosbag')
    parser.add_argument('data_dir', type=str, help='Path to folder with video_recording.mp4 and video_meta.pb3 or root-folder containing multiple datasets')
    parser.add_argument('--result-dir', type=str, help='Path to result folder, default same as proto', default = None)
    parser.add_argument('--subsample', type=int, help='Take every n-th video frame', default = 1)
    parser.add_argument('--raw-image', action='store_true', help='Store raw images in rosbag')
    parser.add_argument('--resize', type=int, nargs = 2, default = [], help='Resize image to this <width height>')
    parser.add_argument('--calibration', type=str, help='YAML file with kalibr camera and IMU calibration to copy, will also adjust for difference in resolution.', default = None)

    args = parser.parse_args()

    for root, dirnames, filenames in os.walk(args.data_dir):
        if not 'video_meta.pb3' in filenames:
            continue

        sub_path = osp.relpath(root,start=args.data_dir)
        result_dir = osp.join(args.result_dir, sub_path) if args.result_dir else osp.join(root, 'rosbag')
        _makedir(result_dir)

        # Read proto
        proto_path = osp.join(root, 'video_meta.pb3')
        with open(proto_path,'rb') as f:
            proto = VideoCaptureData.FromString(f.read())

        video_path = osp.join(root, 'video_recording.mp4')
        bag_path = osp.join(result_dir, 'data.bag')
        resolution = convert_to_bag(proto,
                                    video_path,
                                    bag_path,
                                    subsample = args.subsample,
                                    compress_img = not args.raw_image,
                                    resize = args.resize)

        if args.calibration:
            out_path = osp.join(result_dir, 'calibration.yaml')
            adjust_calibration(args.calibration, out_path, resolution)
