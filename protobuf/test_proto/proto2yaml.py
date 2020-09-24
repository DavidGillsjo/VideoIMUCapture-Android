#!/usr/bin/python3
import argparse
from recording_pb2 import VideoCaptureData
import os.path as osp
import yaml
from protobuf_to_dict import protobuf_to_dict

def convert(buf_path, yaml_path):
    with open(buf_path,'rb') as f:
        data = VideoCaptureData.FromString(f.read())

    yaml_dict = protobuf_to_dict(data, use_enum_labels=True)

    with open(yaml_path, 'w') as f:
        yaml.safe_dump(yaml_dict, f, default_flow_style=False)


if __name__ == "__main__":

    parser = argparse.ArgumentParser(description='Display protobuf VideoIMUdata')
    parser.add_argument('protobuf_path', type=str, help='Path to file')
    parser.add_argument('--yaml_path', type=str, help='Path to file, default same as proto', default = None)

    args = parser.parse_args()
    ypath = args.yaml_path if args.yaml_path else (osp.splitext(args.protobuf_path)[0] + '.yaml')

    convert(args.protobuf_path, ypath)
