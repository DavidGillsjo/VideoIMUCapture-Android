#!/bin/bash
./hooks/pre_build
USE_NVIDIA=0 IMAGE=${IMAGE-"videoimucapture-calibration"} ./../libs/dockers/common/build.sh "$@"
./hooks/post_build
