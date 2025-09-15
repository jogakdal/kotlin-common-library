def BUILD_DOCKER_IMAGE = 'gradle:8.13.0-jdk21' // wrapper(8.13)와 동일 버전 이미지 (gradle wrapper 강제 사용 시 JDK만 맞춰도 무방)

def targetBranch = "${TARGET_BRANCH}" // 환경변수 기반 (필요시 parameters 로 전환 가능)

def CLEAR_CACHE = (env.CLEAR_CACHE ?: 'false').toBoolean()

def RUN_TESTS = (env.RUN_TESTS ?: 'false').toBoolean() // 필요시 true 로 설정

pipeline {
  agent any
  environment {
    GRADLE_USER_HOME = "${WORKSPACE}/.gradle-home"
  }
  stages {
    stage('validation') {
      steps { script {
        if(!targetBranch) err('TARGET_BRANCH는 필수값 입니다.')
      }}
    }

    stage('git clone') {
      steps {
        dir('kotlin-common-lib') {
          // Bitbucket 접근 (환경변수 사용)
          git branch: targetBranch, url: "https://${BITBUCKET_ID}:${BITBUCKET_ACCESS_KEY}@bitbucket.org/ihunet/kotlin-common-library.git"
        }
      }
    }

    stage('fix-perms') {
      steps {
        // 루트 컨테이너로 권한 정리 (workspace 소유자를 gradle 사용자로 변경)
        withDockerContainer(image: BUILD_DOCKER_IMAGE, args: '-u root:root') {
          dir('kotlin-common-lib') {
            sh '''#!/usr/bin/env bash
set -e
echo '[INFO] Fix ownership to gradle:gradle'
chown -R gradle:gradle . || true
# 필요 시 이전 .gradle 제거 (루트 권한일 때만)
if [ -d .gradle ]; then
  rm -rf .gradle || true
fi
'''
          }
        }
      }
    }

    stage('prepare') {
      steps {
        dir('kotlin-common-lib') { script {
          if (CLEAR_CACHE) {
            echo "[INFO] CLEAR_CACHE enabled. Removing ${env.WORKSPACE}/.gradle-home and buildSrc/build"
            sh 'rm -rf ../.gradle-home buildSrc/build || true'
          }
          // Ensure gradle home exists
          sh 'mkdir -p ../.gradle-home'
        }}
      }
    }

    stage('build & publish') {
      steps {
        withCredentials([ usernamePassword(
          credentialsId: 'NEXUS_CRED',
          usernameVariable: 'nexusId',
          passwordVariable: 'nexusPassword') ]) {
          withDockerContainer(image: BUILD_DOCKER_IMAGE) {
            dir('kotlin-common-lib') { script {
              def runTestsFlag = RUN_TESTS ? 'true' : 'false'
              def profileValue = (env.PROFILE && env.PROFILE.trim()) ? env.PROFILE.trim() : 'default'

              withEnv([
                "RUN_TESTS_FLAG=${runTestsFlag}",
                "PROFILE_VALUE=${profileValue}",
                "NEXUS_ID=${nexusId}",
                "NEXUS_PASSWORD=${nexusPassword}",
                "CLEAR_CACHE=${CLEAR_CACHE}" ]) {
                sh '''#!/usr/bin/env bash
set -euo pipefail

chmod +x gradlew

if [ "${CLEAR_CACHE}" = "true" ]; then
  echo '[INFO] CLEAR_CACHE=true: removing project .gradle and GRADLE_USER_HOME'
  rm -rf .gradle || true
  rm -rf ../.gradle-home || true
fi

if [ -d .gradle ] && [ ! -w .gradle ]; then
  echo '[WARN] .gradle not writable; attempting removal'
  rm -rf .gradle || true
fi
mkdir -p .gradle

echo '[INFO] Gradle Wrapper Version:'
./gradlew --no-daemon --version || true

echo "[INFO] Effective GRADLE_USER_HOME=$GRADLE_USER_HOME"

echo '[INFO] First build (no-parallel) to avoid buildSrc scheduling deadlock'
if [ "$RUN_TESTS_FLAG" = 'true' ]; then
  ./gradlew --no-daemon --no-parallel clean build -Pnexus.id=$NEXUS_ID -Pnexus.password=$NEXUS_PASSWORD -Pprofile=$PROFILE_VALUE
else
  ./gradlew --no-daemon --no-parallel clean build -x test -Pnexus.id=$NEXUS_ID -Pnexus.password=$NEXUS_PASSWORD -Pprofile=$PROFILE_VALUE
fi

echo '[INFO] Publish to Nexus'
./gradlew --no-daemon --no-parallel publish -Pnexus.id=$NEXUS_ID -Pnexus.password=$NEXUS_PASSWORD -Pprofile=$PROFILE_VALUE
'''
              }
            }}
          }
        }
      }
    }
  }

  post {
    success { echo 'Build Success' }
    failure {
      emailext (
        to:'sol_dev@hunet.co.kr',
        mimeType: 'text/html',
        body: """
          <p>${env.BUILD_RESULT}: job '${env.JOB_NAME}[${env.BUILD_NUMBER}]'</p>
          <p>Console logs: <a href='${env.BUILD_URL}'>${env.JOB_NAME}[${env.BUILD_NUMBER}]</a></p>
        """,
        subject: "jenkins Build Result: job name: ${env.JOB_NAME}"
      )
    }
    always { echo 'Build END' }
  }
}

private def note(String msg) {
  Date nowDate = new Date(); def date = nowDate.format('yyyy-MM-dd HH:mm:ss.SSS'); return "${date} ${msg}";
}

def err(msg) { error("${note(msg)}") }
