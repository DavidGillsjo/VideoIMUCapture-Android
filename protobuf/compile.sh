JAVA_SRC_DIR=../android_app/app/src/main/java
protoc --java_out=$JAVA_SRC_DIR recording.proto

protoc --python_out=test_proto recording.proto
