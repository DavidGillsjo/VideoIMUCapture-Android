#!/usr/bin/python
import argparse
from recording_pb2 import VideoCaptureData
import os.path as osp
import os
import cv2
import csv
import yaml
from pyquaternion import Quaternion
import numpy as np
import shutil
from utils import OpenCVDumper




def convert_to_lth(proto, result_path):

    video_dir = osp.join(result_dir, 'video')
    try:
        os.mkdir(video_dir)
    except OSError:
        pass

    # Extract video timestamps
    frame_list = []
    for i,frame_data in enumerate(proto.video_meta):
        frame_list.append({
            'frame': float(i),
            'time': float(frame_data.time_ns)
        })

    with open(osp.join(video_dir, 'video.data'), 'w') as f:
        yaml.dump({'video': frame_list}, f, Dumper=OpenCVDumper)

    # Extract IMU data
    imu_list = []
    for i,frame_data in enumerate(proto.imu):
        imu_list.append({
            'time': float(frame_data.time_ns),
            'accel': list(frame_data.accel),
            'gyro': list(frame_data.gyro),
            'synced': 0.0,
            'time_synced':0.0
        })

    with open(osp.join(video_dir, 'imu.data'), 'w') as f:
        yaml.dump({'imu': imu_list}, f, Dumper=OpenCVDumper)

    # Write sync
    with open(osp.join(video_dir, 'sync.data'), 'w') as f:
        yaml.dump({'sync': [{'delay': 0.0}]}, f, Dumper=OpenCVDumper)



def copy_video(data_path, result_dir):
    shutil.copyfile(osp.join(data_path, 'video_recording.mp4'),
                    osp.join(result_dir, 'video', 'video.mp4'))

def copy_calib(kalibr_path, result_dir):
    with open(kalibr_path, 'r') as f:
        calibration_dict = yaml.safe_load(f)

    calib_dir = osp.join(result_dir, 'calibration')
    try:
        os.mkdir(calib_dir)
    except OSError:
        pass

    with open(osp.join(calib_dir, 'calibration.yaml'), 'w') as f:
        yaml.dump(calibration_dict, f, Dumper=OpenCVDumper)

if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Prepare video and proto for Kalibr')
    parser.add_argument('data_dir', type=str, help='Path to folder with video_recording.mp4 and video_meta.pb3')
    parser.add_argument('--result-dir', type=str, help='Path to result folder, default same as proto', default = None)
    parser.add_argument('--kalibr', type=str, help='Path to Kalibr calibration file', default = None)


    args = parser.parse_args()
    result_dir = args.result_dir if args.result_dir else osp.join(args.data_dir, 'lth')
    try:
        os.mkdir(result_dir)
    except OSError:
        pass

    # Read proto
    proto_path = osp.join(args.data_dir, 'video_meta.pb3')
    with open(proto_path,'rb') as f:
        proto = VideoCaptureData.FromString(f.read())

    convert_to_lth(proto, result_dir)
    copy_video(args.data_dir, result_dir)

    if args.kalibr:
        copy_calib(args.kalibr, result_dir)
