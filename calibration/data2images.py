#!/usr/bin/python
import argparse
import os.path as osp
import os
import cv2


def convert_to_images(video_path, result_path, subsample=1):
    # Open video stream
    try:
        cap = cv2.VideoCapture(video_path)

        # Generate images from video and frame data
        i = 0
        while True:
            ret, frame = cap.read()
            if frame is None:
                break
            if (i % subsample) == 0:
                cv2.imwrite(osp.join(result_path,'{}.png'.format(i)), frame)
            i += 1

    finally:
        cap.release()

if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Create images from video file')
    parser.add_argument('video_path', type=str, help='Path to video')
    parser.add_argument('--result-dir', type=str, help='Path to result folder, default same as video file', default = None)
    parser.add_argument('--subsample', type=int, help='Take every n-th video frame', default = 1)

    args = parser.parse_args()
    result_dir = args.result_dir if args.result_dir else osp.join(osp.dirname(args.video_path), 'images')
    try:
        os.mkdir(result_dir)
    except OSError:
        pass

    convert_to_images(args.video_path, result_dir, args.subsample)
