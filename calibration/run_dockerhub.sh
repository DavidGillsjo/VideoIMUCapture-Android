#!/bin/bash
#Usage: [ENV_OPTS] ./run_local [CMD] [ARGS]
./hooks/pre_run
PULL=1 USE_NVIDIA=0 IMAGE=${IMAGE-"davidgillsjo/videoimucapture-calibration"} ./../libs/dockers/common/run.sh "$@"
