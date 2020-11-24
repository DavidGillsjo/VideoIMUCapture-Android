function [] = intrinsic2txt(cameraParams ,result_dir)
% Assumes you just ran the MATLAB calibration tool and have loaded 
% cameraParams in the workspace

intrinsics = [cameraParams.FocalLength cameraParams.PrincipalPoint];

% Write in new format
fileID = fopen(fullfile(result_dir, 'camera.txt'),'w');
fprintf(fileID,'%f, %f, %f, %f\n',intrinsics);
fprintf(fileID,'%f, %f\n', cameraParams.RadialDistortion);
fprintf(fileID,'%f, %f\n', cameraParams.TangentialDistortion);
fclose(fileID);

