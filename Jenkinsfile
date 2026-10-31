// Thin wrapper: all logic is in ci/run.sh. MCP_MAVEN_REPOSITORY_URL, _USER and _PASSWORD come from Jenkins credentials.
// The agent needs a container runtime for Testcontainers (Docker socket mounted into the build container).
pipeline {
  agent {
    docker {
      image 'maven:3.9.12-eclipse-temurin-25'
      args '-v /var/run/docker.sock:/var/run/docker.sock'
    }
  }
  stages {
    stage('verify') {
      steps {
        sh 'ci/run.sh verify'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
  }
}
