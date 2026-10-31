// Thin wrapper: all logic is in ci/run.sh. Change requests (pull request or branch builds) run in a container without the Docker
// socket and with MCP_CI_NO_DOCKER=true; only the main branch runs with the socket, on a node labelled docker-socket.
// Registry access comes from Jenkins credentials (username and password, id mcp-maven-registry; a read-only account for
// change requests, the build account scoped to main), and MCP_MAVEN_REPOSITORY_URL from the job environment.
// DOCKER_GID is the group id of the socket on the node (docker group), so the build user can use it.
pipeline {
  agent none
  stages {
    stage('verify change') {
      when { not { branch 'main' } }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25' } }
      environment { MCP_CI_NO_DOCKER = 'true' }
      steps {
        withCredentials([usernamePassword(credentialsId: 'mcp-maven-registry-readonly',
            usernameVariable: 'MCP_MAVEN_REPOSITORY_USER', passwordVariable: 'MCP_MAVEN_REPOSITORY_PASSWORD')]) {
          sh 'ci/run.sh verify'
        }
      }
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
      steps {
        withCredentials([usernamePassword(credentialsId: 'mcp-maven-registry',
            usernameVariable: 'MCP_MAVEN_REPOSITORY_USER', passwordVariable: 'MCP_MAVEN_REPOSITORY_PASSWORD')]) {
          sh 'ci/run.sh verify'
        }
      }
      post { always { junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml' } }
    }
  }
}
