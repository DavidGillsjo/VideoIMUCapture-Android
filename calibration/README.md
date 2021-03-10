# Calibration and Data Conversion Tools
To use the data for 3D reconstruction we need to calibrate the camera and also calculate the transform between IMU and camera.
For this there is a docker Image running Kalibr included which understands the proto format.

## Move the data
To move the data from your device, mount the phone to your filesystem and
you will find all data at `/Android/data/se.lth.math.videoimucapture/files/YYYY_MM_DD_hh_mm_ss`.
We now refer to `<path-to-recording>` as the path to one measurement `<my-data-path>/YYYY_MM_DD_hh_mm_ss`.

## Clone repository
To use the scripts you first need to clone this repository.
```
git clone https://github.com/DavidGillsjo/VideoIMUCapture-Android.git
cd VideoIMUCapture-Android/calibration
```

## Run Docker container
The easy way to run the docker image is to pull it from Dockerhub.
The only prerequisite is that you have [docker](https://docs.docker.com/engine/install/ubuntu/) installed.
Start a container by running
```
DATA=<my-data-path> ./run_dockerhub.sh
```
If you require `sudo` to run you may use
`SUDO=1 DATA=<my-data-path> ./run_dockerhub.sh`

You are now in the container and may execute the scripts in this folder or any Kalibr command.
Your data will now be mounted under `/data`, so `<path-to-recording>=/data/YYYY_MM_DD_hh_mm_ss`.

## Calibrate Camera
Calibration of the camera will yield intrinsic camera parameters and distortion parameters.
Both might be supplied by the Smartphone depending on brand and model.
To see if this is the case, record a short video run
```
python data2statistics.py /host_home/<path-to-recording>/video_meta.pb3
```
and look for `intrinsic_params` and `distortion_params`.

It is most likely so that they are not available or not good enough.
There are many toolboxes for calibrating the camera, we used Kalibr.
See below for instructions on how to use Kalibr or MATLAB.

### Kalibr
See [their instruction](https://github.com/ethz-asl/kalibr/wiki/multiple-camera-calibration) on how to perform the calibration.

To convert the video to images you may use
```
python data2kalibr.py <path-to-recording>
  --tag-size <measured-april-tag-size-in-meters>
  --subsample 30
```
to convert every 30th frame to an image. (1 image per second)

Assuming we use the Equidistance distortion model the calibration command might look like this
```
cd <path-to-recording>/kalibr
kalibr_calibrate_cameras --bag kalibr.bag --target target.yaml --models pinhole-equi --topics /cam0/image_raw
```
Your calibration will end up in `<path-to-recording>/kalibr/camchain-kalibr.yaml`.

### MATLAB
It is possible to use MATLAB for camera calibration.
See [their instruction](https://se.mathworks.com/help/vision/ug/single-camera-calibrator-app.html) for usage.
Compute **tangential distortion** and **2 coefficient radial distortion**.
When done with the calibration you press *Export Parameters* and save to your workspace.
Then you may use the script we included to store them as a txt file:
```
cd <path-to-VideoIMUCapture-Android>/calibration
intrinsic2txt(cameraParams, <calibration-result-dir>)
```
Now you will have the calibration in `<calibration-result-dir>/camera.txt`.

## Calibrate IMU and camera
Now we need the transform between IMU and camera. For this we use Kalibr.
See [their instruction](https://github.com/ethz-asl/kalibr/wiki/camera-imu-calibration) on how to perform the calibration.
Note that this should be a *different dataset* than for calibration of the camera, since they have different requirements.

To convert the recording to their ROS-format and prepare necessary files you may use the supplied script
```
python data2kalibr.py <path-to-recording>
  --tag-size <measured-april-tag-size-in-meters>
  --subsample 3
  # If you have MATLAB camera calibration
  --matlab-calibration <calibration-result-dir>/camera.txt
  # If you have Kalibr camera calibration
  --kalibr-calibration <calibration-result-dir>/camchain-kalibr.yaml
```
which will put the required files in `<path-to-recording>/kalibr`

To start the calibration run
```
cd <path-to-recording>/kalibr
kalibr_calibrate_imu_camera
  --target target.yaml
  --imu imu.yaml
  --cams camchain.yaml
  --bag kalibr.bag
```
and you will find the calibration result at `camchain-imucam-kalibr.yaml` together with report `report-imucam-kalibr.pdf`.

## Convert data to rosbag
To convert a recording to rosbag you may use
```
python data2rosbag.py <path-to-recording>
  --calibration camchain-imucam-kalibr.yaml # Will copy calibration file and re-scale it to current video resolution.
  --raw-image                               # Store raw images instead of compressed
```
The script will crawl through `<path-to-recording>`, so it may also be a directory with sub-directories containing recordings.
See help for more details
```
python data2rosbag.py --help
```

## Build and run local Docker Image (Development)
In case you want to build the image yourself to customize it.
First build the image by running
```
./build_docker.sh
```
If you require `sudo` to run you may use
`SUDO=1 ./build_docker.sh`

Then start a container by running
```
./run_docker.sh
```

If you require `sudo` to run or want to mount a different directory than `$HOME` you may use
`SUDO=1 DHOME=<custom_home> ./run_docker.sh`

For a full list of options see [dockers README](https://github.com/DavidGillsjo/dockers).

For development purposes, you most likely want to use the code on host instead of container copy.
From within the docker you may now navigate to this folder, if you cloned it to
`$HOME/VideoIMUCapture-Android` then you run
```
cd /host_home/VideoIMUCapture-Android/calibration
```
to go to this folder from within the docker container.
