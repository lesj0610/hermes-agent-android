#!/usr/bin/env bash
# Hermes Android — Gradle 빌드용 샌드박스 환경.
#
# source 해서 쓴다. 전역 셸 설정(~/.bashrc, /etc/profile)은 건드리지 않고,
# 시스템 패키지도 설치하지 않는다. 이 셸 세션 안에서만 유효하다.
#
#   source ./env.sh
#   ./gradlew assembleDebug
#
# SDK 패키지 관리(android CLI)는 이 파일이 아니라 ./sdk.sh 를 쓴다.
# 두 도구가 서로 다른 환경변수를 보고, 동시에 설정하면 AGP가 죽는다.
# 자세한 근거는 ENVIRONMENT.md 참고.

# Android SDK — 시스템 패키지가 아니라 /ssd128g 아래 사용자 소유 디렉터리.
export ANDROID_HOME=/ssd128g/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Gradle 캐시/배포판/데몬 로그를 홈 디렉터리 밖으로 격리.
# 기본값 ~/.gradle 를 쓰면 루트 파티션(여유 93G)을 먹고 다른 프로젝트와 섞인다.
export GRADLE_USER_HOME=/ssd128g/gradle-home

# AGP가 debug.keystore 와 analytics.settings 를 쓰는 위치의 부모 디렉터리.
# 실제 파일은 $ANDROID_PREFS_ROOT/.android/ 아래에 생긴다.
# 미설정 시 ~/.android 로 간다.
#
# 주의: ANDROID_USER_HOME 을 함께 export 하면 AGP가
# 'Could not create provider for value source AndroidLocationsBuildService'
# 로 실패한다. Gradle 쪽에서는 ANDROID_PREFS_ROOT 만 설정한다.
export ANDROID_PREFS_ROOT=/ssd128g/android-prefs
unset ANDROID_USER_HOME

# 이 셸에서만 SDK 툴을 PATH에 얹는다.
export PATH="$ANDROID_HOME/platform-tools:$PATH"

# JDK 21 시스템 설치본을 그대로 쓴다. Gradle toolchain 설정이 컴파일에
# 필요한 JDK 17 타깃을 처리한다.
: "${JAVA_HOME:=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export JAVA_HOME

echo "Hermes Android build env:"
echo "  ANDROID_HOME       = $ANDROID_HOME"
echo "  ANDROID_PREFS_ROOT = $ANDROID_PREFS_ROOT"
echo "  GRADLE_USER_HOME   = $GRADLE_USER_HOME"
echo "  JAVA_HOME          = $JAVA_HOME"
