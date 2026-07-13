FROM gradle:8.9.0-jdk17-jammy@sha256:682c0245826af1e6f5f7b95306cc7039d3d8d8e8de06b1a330e6f9015ee757a0

USER root

ARG ANDROID_COMMAND_LINE_TOOLS=11076708
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG ANDROID_PLATFORM=35
ARG ANDROID_PLATFORM_REVISION=2
ARG ANDROID_BUILD_TOOLS_PRIMARY=35.0.0
ARG ANDROID_BUILD_TOOLS_COMPAT=34.0.0
ARG ANDROID_PLATFORM_TOOLS_REVISION=37.0.0
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/gradle/.gradle \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl --fail --location --silent --show-error \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS}_latest.zip" \
      --output /tmp/android-command-line-tools.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/android-command-line-tools.zip" | sha256sum --check - \
    && unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-command-line-tools \
    && mv /tmp/android-command-line-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-command-line-tools /tmp/android-command-line-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
      "build-tools;${ANDROID_BUILD_TOOLS_COMPAT}" \
      "build-tools;${ANDROID_BUILD_TOOLS_PRIMARY}" \
      "platforms;android-${ANDROID_PLATFORM}" \
      "platform-tools" \
    && grep -qx "Pkg.Revision=${ANDROID_BUILD_TOOLS_COMPAT}" \
      "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_COMPAT}/source.properties" \
    && grep -qx "Pkg.Revision=${ANDROID_BUILD_TOOLS_PRIMARY}" \
      "${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS_PRIMARY}/source.properties" \
    && grep -qx "Pkg.Revision=${ANDROID_PLATFORM_REVISION}" \
      "${ANDROID_HOME}/platforms/android-${ANDROID_PLATFORM}/source.properties" \
    && grep -qx "Pkg.Revision=${ANDROID_PLATFORM_TOOLS_REVISION}" \
      "${ANDROID_HOME}/platform-tools/source.properties" \
    && mkdir -p \
      /home/gradle/.gradle \
      /home/gradle/work/build \
      /home/gradle/work/project-cache \
      /home/gradle/work/kotlin \
      /workspace/android/agent \
      /workspace/android/device-controller \
      /workspace/android/fixture-app \
    && chown -R gradle:gradle /home/gradle/.gradle /home/gradle/work /workspace/android

WORKDIR /workspace/android
USER gradle:gradle
