#!/bin/sh
# Resolves the published Grounds Scene Editor API from GitHub Packages without a local repository.
set -eu

: "${GITHUB_ACTOR:?Set GITHUB_ACTOR to a GitHub Packages user.}"
: "${GITHUB_TOKEN:?Set GITHUB_TOKEN to a GitHub Packages token.}"

verify_gradle_home="$(mktemp -d)"
trap 'rm -rf "$verify_gradle_home"' EXIT HUP INT TERM

GRADLE_USER_HOME="$verify_gradle_home" \
    ./gradlew --no-build-cache --refresh-dependencies clean check :buildsystem-grounds:shadowJar
