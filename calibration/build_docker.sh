#!/bin/bash
cp ../protobuf/recording.proto .
USE_NVIDIA=0 IMAGE=${IMAGE-video_imu_calibration} ./../libs/dockers/common/build.sh "$@"
rm recording.proto
