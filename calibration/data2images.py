#!/usr/bin/python
import argparse
import os.path as osp
import os
import cv2
import sys
from data2rosbag import _makedir, adjust_calibration


def convert_to_images(video_path, result_path, subsample=1, resize=[]):

    if resize:
        resize_f = lambda frame: cv2.resize(frame, tuple(resize), cv2.INTER_AREA)
    else:
        resize_f = lambda frame: frame

    # Open video stream
    try:
        cap = cv2.VideoCapture(video_path)

        # Generate images from video and frame data
        got_frame, frame = cap.read()
        resolution = (frame.shape[1], frame.shape[0])

        i = 0
        while got_frame:
            if (i % subsample) == 0:
                frame = resize_f(frame)
                cv2.imwrite(osp.join(result_path,'{:06d}.png'.format(i)), frame)
            got_frame, frame = cap.read()
            i += 1

    finally:
        cap.release()

    return resolution

if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Create images from video file')
    parser.add_argument('video_path', type=str, help='Path to video')
    parser.add_argument('--result-dir', type=str, help='Path to result folder, default same as video file', default = None)
    parser.add_argument('--subsample', type=int, help='Take every n-th video frame', default = 1)
    parser.add_argument('--resize', type=int, nargs = 2, default = [], help='Resize image to this <width height>')
    parser.add_argument('--calibration', type=str, help='YAML file with kalibr camera and IMU calibration to copy, will also adjust for difference in resolution.', default = None)

    args = parser.parse_args()

    if not osp.isdir(args.video_path):
        result_dir = args.result_dir if args.result_dir else osp.join(osp.dirname(args.video_path), 'images')
        _makedir(result_dir)
        resolution = convert_to_images(args.video_path, result_dir,
                                       subsample = args.subsample,
                                       resize = args.resize)
        if args.calibration:
            out_path = osp.join(result_dir, 'calibration.yaml')
            adjust_calibration(args.calibration, out_path, resolution)
        sys.exit()


    for root, dirnames, filenames in os.walk(args.video_path):
        if not 'video_meta.pb3' in filenames:
            continue

        sub_path = osp.relpath(root,start=args.video_path)
        result_dir = osp.join(args.result_dir, sub_path) if args.result_dir else osp.join(root, 'images')
        _makedir(result_dir)

        video_path = osp.join(root, 'video_recording.mp4')
        resolution = convert_to_images(video_path, result_dir,
                                       subsample = args.subsample,
                                       resize = args.resize)

        if args.calibration:
            out_path = osp.join(result_dir, 'calibration.yaml')
            adjust_calibration(args.calibration, out_path, resolution)
