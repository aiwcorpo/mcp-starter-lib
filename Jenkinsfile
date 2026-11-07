// Change requests (pull request or branch builds) run in a container without the Docker
// socket and without the Redis test; only the main branch runs with the socket, on a node labelled docker-socket.
// No credential is needed: the mcp-contracts artifacts come from Maven Central.
// DOCKER_GID is the group id of the socket on the node (docker group), so the build user can use it.
pipeline {
  agent none
  stages {
    stage('verify change') {
      when { not { branch 'main' } }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25' } }
      steps { sh "./mvnw -B verify '-Dtest=!RedisJtiReplayStoreTest'" }
      post { always { junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml' } }
    }
    stage('verify main') {
      when { branch 'main' }
      agent {
        docker {
          image 'maven:3.9.12-eclipse-temurin-25'
          label 'docker-socket'
          args "-v /var/run/docker.sock:/var/run/docker.sock --group-add ${env.DOCKER_GID ?: '999'}"
        }
      }
      steps { sh './mvnw -B verify' }
      post { always { junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml' } }
    }
  }
}
