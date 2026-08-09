#!/usr/bin/env bash
# Hermes Android — SDK 패키지 관리 래퍼.
#
#   ./sdk.sh sdk list
#   ./sdk.sh sdk install platforms/android-37.1
#   ./sdk.sh info
#
# env.sh 와 분리한 이유: android CLI 는 ANDROID_USER_HOME 을 보고,
# AGP 는 ANDROID_PREFS_ROOT 를 본다. 둘을 한 프로세스에 동시에 export 하면
# AGP 빌드가 AndroidLocationsBuildService 생성 실패로 죽는다.
# 그래서 CLI 쪽만 ANDROID_USER_HOME 을 쓰되, AGP 가 쓰는 것과 같은
# 디렉터리($ANDROID_PREFS_ROOT/.android)를 가리키게 해서 상태를 한 곳에 모은다.

set -euo pipefail

export ANDROID_HOME=/ssd128g/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME=/ssd128g/android-prefs/.android
unset ANDROID_PREFS_ROOT

# java.util.prefs 가 기본값 ~/.java 에 사용자 preference 트리를 만든다.
# 그것까지 샌드박스 안으로 넣는다.
export JDK_JAVA_OPTIONS="-Djava.util.prefs.userRoot=/ssd128g/android-prefs/javaprefs"

# 텔레메트리는 매 호출 --no-metrics 로 끈다.
mkdir -p "$ANDROID_USER_HOME" /ssd128g/android-prefs/javaprefs
exec "$ANDROID_HOME/cmdline-tools/latest/bin/android" --no-metrics --sdk="$ANDROID_HOME" "$@"
