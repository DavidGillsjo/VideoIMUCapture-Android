# VideoIMUCapture-Android
Android application for capturing video and IMU data useful for 3D reconstruction using SLAM and Structure from Motion techniques.


<img src="images/Capture.png" width="33%" border="1" ><img src="images/Settings.png" width="33%" border="1" ><img src="images/Warning_small.png" width="33%" border="1" >

# Description
This Android application is a data collection tool for researchers working with Simultaneous Localization and Mapping (SLAM) and Structure from Motion (SfM).

It records Camera Frames at ~30Hz and Inertia Measurement Unit (IMU) data at ~100Hz synchronized to the same clock, given that the [Android device supports it](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE).
The camera frames are stored to a H.264/MP4 video file and the frame meta data together with IMU data is stored in a protobuf3 file.

A major problem with modern smartphones and 3D reconstruction is that all have Optical Image Stabilization (OIS), which means different camera parameters for each frame.
Furthermore, on many Android devices it cannot be disabled and a rare few actually supply the data of the lens movement.
VideoIMUCapture shows a clear warning if you have this feature on during recording and includes settings for both Optical Image Stabilization and Digital Video Stabilization (DVS).

This code is forked from [mobile-sensor-ar-logger](https://github.com/OSUPCVLab/mobile-ar-sensor-logger) which in turn is based on the [grafika](https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/CameraCaptureActivity.java) project.
For the video capture it uses the Camera2 API.

# Features
- Captures camera frames at ~30Hz to H.264/MP4.
- Captures IMU data at ~100Hz.
- Synchronized clock, assuming [the device supports it](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE).
- Stores IMU data and all frame meta data in a protobuf file, check [recording.proto](https://github.com/DavidGillsjo/VideoIMUCapture-Android/blob/master/protobuf/recording.proto) to see what data is included.
- Display warning if OIS or DVS is enabled since this affects the camera parameters.
- Settings menu for configuring video resolution, OIS, DVS, Auto focus and Auto exposure.

# Install
To install on your Android device go to the [Release page](https://github.com/DavidGillsjo/VideoIMUCapture-Android/releases) from your Android device browser and download the latest `.apk` file. You will need to give your browser permission to install the application, but Android should guide you through the necessary steps.

# Calibration
To use the data for 3D reconstruction you will need to calibrate the IMU and Camera, see [Calibration README](calibration/README.md) for help.

# Read Protobuf File
Examples on python scripts reading the protobuf file can be found the the [calibration](calibration) folder, for example [data2statistics.py](calibration/data2statistics.py). You need `protoc` to compile a python module first, this is already done in the calibration docker image.
You may compile it yourself like this
```bash
#Go to git repo
cd <some_path>/VideoIMUCapture-Android

#Install protoc
wget -nv "https://github.com/protocolbuffers/protobuf/releases/download/v3.13.0/protoc-3.13.0-linux-x86_64.zip" -O protoc.zip &&\  
sudo unzip protoc.zip -d /usr/local &&\
rm protoc.zip

# Build module - Alternative  1
# Add to pythonpath
mkdir proto_python 
protoc --python_out=proto_python protobuf/recording.proto
export PYTHONPATH="$(pwd)/proto_python:${PYTHONPATH}"

# Build module - Alternative  2
# Just place the parser in your own project.
protoc --python_out=<your_project_dir> protobuf/recording.proto

# Other Python dependencies
pip3 install protobuf pyquaternion

#Run script
python3 calibration/data2statistics.py <datafolder>/<datetime>/video_meta.pb3
```

# Feedback
If you find any bugs or have feature requests, please create an [issue](https://github.com/DavidGillsjo/VideoIMUCapture-Android/issues) on this Github page.
