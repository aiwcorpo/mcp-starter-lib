#!/usr/bin/env bash
# Single CI entry point (M5-5): every CI system, the container image and a developer's shell call this script and
# nothing else. .gitlab-ci.yml is a thin wrapper around it. Needs bash, git, JDK 25 and either a filled ~/.m2 with the
# mcp-contracts artifacts or MCP_MAVEN_REPOSITORY_URL (+ _USER, _PASSWORD) pointing at the registry that holds them.
#
#   ci/run.sh                 verify (default)
#   ci/run.sh verify          hygiene checks, then unit tests and the tests with the contract vectors and the
#                             test gateway (mvnw verify)
#   ci/run.sh hygiene         only the text checks of the sources (non-ASCII quotes and spaces, tool traces)
#   ci/run.sh release         verify, then install the modules to the local Maven repository; refuses a -SNAPSHOT
#                             version, a version that is already installed and a checkout that is not the tag
#                             v<version>
#
# RedisJtiReplayStoreTest starts redis:8.8.4-alpine with Testcontainers, so a container runtime must be reachable
# (a Docker socket mounted into the CI container, or DOCKER_HOST). Without it that one test class fails.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null; then
  echo "ci/run.sh: no JDK; set JAVA_HOME to a JDK 25 or use the maven:3.9.12-eclipse-temurin-25 image" >&2
  exit 1
fi

mvnw() {
  if [[ -n "${MCP_MAVEN_REPOSITORY_URL:-}" ]]; then
    ./mvnw -B -s .ci/settings.xml "$@"
  else
    ./mvnw -B "$@"
  fi
}

project_version() {
  mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1
}

tracked_sources() {
  git ls-files -z --cached --others --exclude-standard -- '*.java' '*.md' '*.xml' '*.yml' '*.yaml' '*.properties' '*.json' '*.sh' ':!:ci/run.sh'
}

# The two checks of the house style: characters that give away generated text, and phrases left by tools.
hygiene() {
  local failed=0
  if tracked_sources | xargs -0 perl -ne 'if (/[\x{00A0}\x{200B}\x{202F}\x{FEFF}\x{2018}\x{2019}\x{201C}\x{201D}\x{2026}]/) { print "$ARGV:$.: $_"; $bad = 1 } END { exit($bad ? 1 : 0) }' -CSD; then
    :
  else
    echo "ci/run.sh: non-ASCII quotes, spaces or ellipsis found (above)" >&2
    failed=1
  fi
  if tracked_sources | xargs -0 grep -niE 'as an ai|generated with|co-authored-by: claude|lorem ipsum|your-api-key|example\.com' -- ; then
    echo "ci/run.sh: tool traces or placeholders found (above)" >&2
    failed=1
  fi
  return "$failed"
}

# These classes start the server with the dev profile outside a Spring test context (or check the guard against the
# machine itself) and expect a developer's machine, while a container is a deployment signal by design
# (DevProfileEnvironment). In a container they are skipped and the script says so; run them once on a plain runner (VM or
# shell executor) as well.
container_sensitive_tests="PlatformConfigurationGuardTest,ServerCustomizerTest,StartupRefusalTest,ToolBypassTest"
in_container() {
  [[ -e /.dockerenv || -e /run/.containerenv || -n "${container:-}" ]]
}

# A build that ran no tests proves nothing (a wrong -Dtest filter, a broken surefire config).
require_tests_ran() {
  local ran
  ran="$(cat mcp-platform-*/target/surefire-reports/TEST-*.xml 2>/dev/null | grep -c '<testcase ' || true)"
  if [[ "${ran:-0}" -eq 0 ]]; then
    echo "ci/run.sh: no test was run" >&2
    exit 1
  fi
  echo "ci/run.sh: $ran test cases ran"
}

verify() {
  local args=(clean verify)
  hygiene
  if in_container; then
    echo "ci/run.sh: container detected, skipping $container_sensitive_tests (they need a developer's machine)" >&2
    args+=("-Dtest=!${container_sensitive_tests//,/,!}" -Dsurefire.failIfNoSpecifiedTests=false)
  fi
  mvnw "${args[@]}"
  require_tests_ran
}

release() {
  local version repo
  version="$(project_version)"
  if [[ "$version" == *-SNAPSHOT ]]; then
    echo "ci/run.sh release: $version is a snapshot; release a version without -SNAPSHOT" >&2
    exit 1
  fi
  if [[ "$(git describe --exact-match --tags HEAD 2>/dev/null || true)" != "v$version" ]]; then
    echo "ci/run.sh release: HEAD is not tagged v$version" >&2
    exit 1
  fi
  repo="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
  if [[ -d "$repo/pl/aiwcorpo/mcp/mcp-platform-starter/$version" ]]; then
    echo "ci/run.sh release: $version is already installed in $repo; released versions are immutable" >&2
    exit 1
  fi
  verify
  mvnw -DskipTests install
}

case "${1-verify}" in
  verify) verify ;;
  hygiene) hygiene ;;
  release) release ;;
  *) echo "usage: ci/run.sh [verify|hygiene|release]" >&2; exit 2 ;;
esac
