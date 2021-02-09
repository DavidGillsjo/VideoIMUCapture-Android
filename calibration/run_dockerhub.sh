#!/bin/bash
#Usage: [ENV_OPTS] ./run_local [CMD] [ARGS]
USE_NVIDIA=0 IMAGE=${IMAGE-"davidgillsjo/videoimucapture-calibration"} ./../libs/dockers/common/run.sh "$@"
