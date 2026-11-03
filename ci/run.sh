#!/usr/bin/env bash
# Single CI entry point (M5-5): every CI system, the container image and a developer's shell call this script and
# nothing else. .gitlab-ci.yml, Jenkinsfile and .github/workflows/ci.yml are thin wrappers around it. Needs bash 3.2 or newer
# (Linux, macOS; Windows through WSL2), git, perl, JDK 25 and either a filled ~/.m2 with the mcp-contracts artifacts or MCP_MAVEN_REPOSITORY_URL (+ _USER, _PASSWORD)
# pointing at the registry that holds them.
#
#   ci/run.sh                 verify (default)
#   ci/run.sh verify          hygiene checks, then clean verify (unit tests, tests with the contract vectors and the
#                             test gateway); fails when the build fails or when no test ran
#   ci/run.sh hygiene         only the text checks of the sources (non-ASCII quotes and spaces, tool traces)
#   ci/run.sh release         verify, then install the modules to the local Maven repository; refuses a -SNAPSHOT
#                             version, a dirty tree, a version that is already installed, a checkout that is not the tag
#                             v<version> and a run in which any test was skipped
#
# MAVEN_REPO_LOCAL (default ~/.m2/repository) is the one repository Maven resolves from and installs to, and the one release
# checks for an existing version. RedisJtiReplayStoreTest starts redis:8.8.4-alpine with Testcontainers, so a container
# runtime must be reachable (Docker socket, or DOCKER_HOST; TESTCONTAINERS_HOST_OVERRIDE when the host is not routable).
# MCP_CI_NO_DOCKER=true is for change requests, whose code must not get the Docker socket: RedisJtiReplayStoreTest is left out (and
# counted as skipped, so release refuses); the push to the default branch runs it on a runner that has the socket.
# Tests that expect a developer's machine skip themselves on a host with a deployment signal (container, pod, systemd);
# the skips are in the surefire reports and make release refuse.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

java_major() {
  "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | head -n 1
}

jdk_is_25() {
  local major
  [[ -x "$1/bin/java" ]] || return 1
  major="$(java_major "$1/bin/java" 2>/dev/null || true)"
  [[ -n "$major" ]] && [[ "${major%%.*}" -ge 25 ]] 2>/dev/null
}

# The build needs Java 25. Keeps the current JDK when it is new enough, otherwise looks in the usual places (macOS
# java_home, Homebrew, /usr/lib/jvm, SDKMAN, IntelliJ); JAVA_HOME set to a JDK 25 skips the search.
select_jdk() {
  local candidate
  if [[ -n "${JAVA_HOME:-}" ]] && jdk_is_25 "$JAVA_HOME"; then
    return 0
  fi
  if command -v java >/dev/null 2>&1; then
    candidate="$(dirname "$(dirname "$(command -v java)")")"
    if jdk_is_25 "$candidate"; then
      export JAVA_HOME="$candidate"
      return 0
    fi
  fi
  candidate=""
  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
  fi
  if [[ -n "$candidate" ]] && jdk_is_25 "$candidate"; then
    export JAVA_HOME="$candidate"
    return 0
  fi
  if command -v brew >/dev/null 2>&1; then
    candidate="$(brew --prefix openjdk@25 2>/dev/null || true)"
    for candidate in "$candidate/libexec/openjdk.jdk/Contents/Home" "$candidate/libexec"; do
      if jdk_is_25 "$candidate"; then
        export JAVA_HOME="$candidate"
        return 0
      fi
    done
  fi
  for candidate in /usr/lib/jvm/*25* "$HOME"/.sdkman/candidates/java/25* "$HOME"/.jdks/*25*; do
    if jdk_is_25 "$candidate"; then
      export JAVA_HOME="$candidate"
      return 0
    fi
  done
  echo "ci/run.sh: JDK 25 not found; set JAVA_HOME to a JDK 25 or use the maven:3.9.12-eclipse-temurin-25 image" >&2
  return 1
}

select_jdk

repo="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"

mvnw() {
  local args=(-B -Dmaven.repo.local="$repo")
  if [[ -n "${MCP_MAVEN_REPOSITORY_URL:-}" ]]; then
    args+=(-s .ci/settings.xml)
  fi
  ./mvnw "${args[@]}" "$@"
}

project_version() {
  mvnw -q -N org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1
}

# Tracked and untracked-but-not-ignored sources; .m2/ (a cache inside the workspace) is ignored by git and left out by name too.
source_files() {
  git ls-files -z --cached --others --exclude-standard -- '*.java' '*.md' '*.xml' '*.yml' '*.yaml' '*.properties' '*.json' '*.sh' \
    ':!:ci/run.sh' ':!:.m2/**'
}

# The two checks of the house style: characters that give away generated text, and phrases left by tools. The file list is
# read once into an array, so no check runs on a chunk of it and a failing tool is told apart from "found" (grep: 0 found, 1 none, 2 error).
hygiene() {
  local failed=0 status files=() file
  while IFS= read -r -d '' file; do
    files+=("$file")
  done < <(source_files)
  if [[ "${#files[@]}" -eq 0 ]]; then
    echo "ci/run.sh: no source files to check" >&2
    return 1
  fi
  status=0
  perl -CSD -e 'my $bad = 0; while (<<>>) { if (/[\x{00A0}\x{200B}\x{202F}\x{FEFF}\x{2018}\x{2019}\x{201C}\x{201D}\x{2026}]/) { print "$ARGV:$.: $_"; $bad = 1 } } continue { close ARGV if eof } exit($bad);' -- "${files[@]}" || status=$?
  if [[ "$status" -eq 1 ]]; then
    echo "ci/run.sh: non-ASCII quotes, spaces or ellipsis found (above)" >&2
    failed=1
  elif [[ "$status" -ne 0 ]]; then
    echo "ci/run.sh: the text check itself failed ($status)" >&2
    return 1
  fi
  status=0
  grep -niE 'as an ai|generated with|co-authored-by: claude|lorem ipsum|your-api-key|example\.com' -- "${files[@]}" || status=$?
  if [[ "$status" -eq 0 ]]; then
    echo "ci/run.sh: tool traces or placeholders found (above)" >&2
    failed=1
  elif [[ "$status" -ne 1 ]]; then
    echo "ci/run.sh: the phrase check itself failed ($status)" >&2
    return 1
  fi
  return "$failed"
}

# A build that ran no tests proves nothing (a wrong -Dtest filter, a broken surefire config).
require_tests_ran() {
  local ran
  ran="$(cat mcp-platform-*/target/surefire-reports/TEST-*.xml 2>/dev/null | grep -c '<testcase ' || true)"
  if [[ "${ran:-0}" -eq 0 ]]; then
    echo "ci/run.sh: no test was run" >&2
    return 1
  fi
  echo "ci/run.sh: $ran test cases ran"
}

skipped_tests() {
  cat mcp-platform-*/target/surefire-reports/TEST-*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | grep -o ' skipped="[0-9]*"' \
    | tr -dc '0-9\n' | awk '{ n += $1 } END { print n + 0 }'
}

verify() {
  local args=(clean verify)
  hygiene
  if [[ "${MCP_CI_NO_DOCKER:-}" == true ]]; then
    echo "ci/run.sh: MCP_CI_NO_DOCKER=true, leaving out RedisJtiReplayStoreTest" >&2
    args+=("-Dtest=!RedisJtiReplayStoreTest" -Dsurefire.failIfNoSpecifiedTests=false)
  fi
  mvnw "${args[@]}"
  require_tests_ran
  echo "ci/run.sh: $(skipped_tests) tests skipped"
}

release() {
  local version
  version="$(project_version)"
  if [[ "$version" == *-SNAPSHOT ]]; then
    echo "ci/run.sh release: $version is a snapshot; release a version without -SNAPSHOT" >&2
    return 1
  fi
  if ! git diff --quiet HEAD || [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
    echo "ci/run.sh release: the working tree is not clean" >&2
    return 1
  fi
  if [[ "$(git describe --exact-match --tags HEAD 2>/dev/null || true)" != "v$version" ]]; then
    echo "ci/run.sh release: HEAD is not tagged v$version" >&2
    return 1
  fi
  if [[ -d "$repo/pl/aiwcorpo/mcp/mcp-platform-starter/$version" ]]; then
    echo "ci/run.sh release: $version is already installed in $repo; released versions are immutable" >&2
    return 1
  fi
  if [[ "${MCP_CI_NO_DOCKER:-}" == true ]]; then
    echo "ci/run.sh release: MCP_CI_NO_DOCKER=true leaves tests out; release on a runner with a container runtime" >&2
    return 1
  fi
  verify
  if [[ "$(skipped_tests)" -ne 0 ]]; then
    echo "ci/run.sh release: $(skipped_tests) tests were skipped (a host with a deployment signal?); release on a plain runner" >&2
    return 1
  fi
  mvnw -DskipTests install
}

case "${1-verify}" in
  verify) verify ;;
  hygiene) hygiene ;;
  release) release ;;
  *) echo "usage: ci/run.sh [verify|hygiene|release]" >&2; exit 2 ;;
esac
