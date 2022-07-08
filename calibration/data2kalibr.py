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
from data2rosbag import convert_to_bag



def create_camera_yaml(proto, camera_yaml_path, matlab_calibration=None):
    c = proto.camera_meta
    est_focal_length = proto.video_meta[0].est_focal_length_pix
    q = Quaternion(c.lens_pose_rotation[3], *c.lens_pose_rotation[:3])
    print('intrinsics: ', c.intrinsic_params)
    P = q.transformation_matrix
    print("Translation")
    print(c.lens_pose_translation)
    P[:3,3] = -np.matmul(q.rotation_matrix,c.lens_pose_translation)
    print("P")
    print(P)
    if c.intrinsic_params:
        assert proto.camera_meta.intrinsic_params[4] == 0
        intrinsics = c.intrinsic_params[:4]
        print('Estimated Focal Length: {}, Supplied: {}'.format(est_focal_length, c.intrinsic_params[:2]))
    else:
        intrinsics = []



    if matlab_calibration:
        with open(matlab_calibration, 'r') as f:
            csvreader = csv.reader(f, quoting=csv.QUOTE_NONNUMERIC)
            intrinsics = next(csvreader)
            radial_dist = next(csvreader)
            tangential_dist = next(csvreader)
    else:
        if c.distortion_params:
            radial_dist = list(c.distortion_params[:2])
            tangential_dist = list(c.distortion_params[3:])
        else:
            radial_dist = [0.0]*2
            tangential_dist = [0.0]*2
    camera_dict = {
        'camera_model': 'pinhole',
        'intrinsics': intrinsics,
        'distortion_model': 'radtan',
        'distortion_coeffs': radial_dist + tangential_dist,
        'T_cam_imu': P.tolist(),
        'timeshift_cam_imu': 0,
        'rostopic': '/cam0/image_raw',
        'resolution': [c.resolution.width, c.resolution.height],
        'cam_overlaps': []
    }

    with open(camera_yaml_path, 'w') as f:
        yaml.safe_dump({'cam0':camera_dict}, f, default_flow_style=False)

def create_imu_yaml(proto, imu_yaml_path):
    #Noise density values from pixel 3 IMU datasheet
    imu_dict = {
        'accelerometer_noise_density': 9.8*180e-6,   #Noise density (continuous-time)
        'accelerometer_random_walk':   9.8*180e-6,   #Bias random walk
        'gyroscope_noise_density':     0.007*np.pi/180.0,   #Noise density (continuous-time)
        'gyroscope_random_walk':       0.007*np.pi/180.0,   #Bias random walk
        'rostopic':                    '/imu0',      #the IMU ROS topic
        'update_rate':                 proto.imu_meta.sample_frequency   #Hz (for discretization of the values above)
    }

    with open(imu_yaml_path, 'w') as f:
        yaml.safe_dump(imu_dict, f, default_flow_style=False)

def create_target_yaml(tag_size, target_path):
    target = {
        'target_type': 'aprilgrid', #gridtype
        'tagCols': 6,               #number of apriltags
        'tagRows': 6,               #number of apriltags
        'tagSize': tag_size,           #size of apriltag, edge to edge [m]
        'tagSpacing': 0.3          #ratio of space between tags to tagSize
                             #example: tagSize=2m, spacing=0.5m --> tagSpacing=0.25[-]
    }
    with open(target_path, 'w') as f:
        yaml.safe_dump(target, f, default_flow_style=False)

if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Prepare video and proto for Kalibr')
    parser.add_argument('data_dir', type=str, help='Path to folder with video_recording.mp4 and video_meta.pb3')
    parser.add_argument('--result-dir', type=str, help='Path to result folder, default same as proto', default = None)
    parser.add_argument('--tag-size', type=float, help='Tag size for april grid', default = 24e-3)
    parser.add_argument('--subsample', type=int, help='Take every n-th video frame', default = 1)
    parser.add_argument('--matlab-calibration', type=str, help='Txt file with matlab camera calibration', default = None)
    parser.add_argument('--kalibr-calibration', type=str, help='YAML file with kalibr camera calibration', default = None)

    args = parser.parse_args()
    result_dir = args.result_dir if args.result_dir else osp.join(args.data_dir, 'kalibr')
    try:
        os.mkdir(result_dir)
    except OSError:
        pass

    # Read proto
    proto_path = osp.join(args.data_dir, 'video_meta.pb3')
    with open(proto_path,'rb') as f:
        proto = VideoCaptureData.FromString(f.read())

    video_path = osp.join(args.data_dir, 'video_recording.mp4')
    bag_path = osp.join(result_dir, 'kalibr.bag')
    convert_to_bag(proto, video_path, bag_path, args.subsample)

    camera_yaml_path = osp.join(result_dir, 'camchain.yaml')
    if args.kalibr_calibration:
        shutil.copy(args.kalibr_calibration, camera_yaml_path)
    else:
        create_camera_yaml(proto, camera_yaml_path, args.matlab_calibration)
    imu_yaml_path = osp.join(result_dir, 'imu.yaml')
    create_imu_yaml(proto, imu_yaml_path)
    target_yaml_path = osp.join(result_dir, 'target.yaml')
    create_target_yaml(args.tag_size, target_yaml_path)
