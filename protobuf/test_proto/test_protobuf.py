#!/usr/bin/python3
import argparse
from recording_pb2 import VideoCaptureData


def print_buf(buf_path):
    with open(buf_path,'rb') as f:
        data = VideoCaptureData.FromString(f.read())

    print(data)


if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Display protobuf VideoIMUdata')
    parser.add_argument('protobuf_path', type=str, help='Path to file')

    args = parser.parse_args()

    print_buf(args.protobuf_path)
