FROM stereolabs/kalibr
# Arguments
ARG user=docker
ARG uid=1000
ARG gid=1000

# Install some dependencies
RUN apt-get update && apt-get install -y \
		sudo \
		wget \
		zip \
		&& \
	apt-get clean && \
	apt-get autoremove && \
	rm -rf /var/lib/apt/lists/*

#Get protoc
RUN wget -nv "https://github.com/protocolbuffers/protobuf/releases/download/v3.13.0/protoc-3.13.0-linux-x86_64.zip" -O protoc.zip &&\
	  unzip protoc.zip -d /usr/local &&\
		rm protoc.zip

# Compile proto
COPY recording.proto .
RUN mkdir proto && \
		protoc --python_out=proto recording.proto
ENV PYTHONPATH="/proto:${PYTHONPATH}"

# Python packages
RUN pip install --upgrade pip && \
		pip install --no-cache-dir\
		protobuf \
		pyquaternion

VOLUME data
VOLUME host_home


# Setup user
RUN export uid="${uid}" gid="${gid}" && \
    groupadd -g "${gid}" "${user}" && \
    useradd -m -u "${uid}" -g "${user}" -s /bin/bash "${user}" && \
    passwd -d "${user}" && \
    usermod -aG sudo "${user}"

USER "${uid}"

CMD bash
