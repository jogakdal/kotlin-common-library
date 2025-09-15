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
          // 기본 gradle 이미지 사용자(gradle) 유지 -> /home/gradle 권한 이슈 회피
          withDockerContainer(image: BUILD_DOCKER_IMAGE) {
            dir('kotlin-common-lib') { script {
              def runTestsFlag = RUN_TESTS ? 'true' : 'false'
              def profileValue = (env.PROFILE && env.PROFILE.trim()) ? env.PROFILE.trim() : 'default'
              sh """#!/usr/bin/env bash
set -euo pipefail

RUN_TESTS_FLAG='${runTestsFlag}'
PROFILE_VALUE='${profileValue}'

chmod +x gradlew

echo '[INFO] Gradle Wrapper Version:'
./gradlew --no-daemon --version

ls -al .. | sed 's/^/[WS]/'

echo '[INFO] Effective GRADLE_USER_HOME:'
python3 - <<'PY'
import os
print('[INFO] GRADLE_USER_HOME=', os.environ.get('GRADLE_USER_HOME'))
PY

echo '[INFO] Build buildSrc first (clean)'
./gradlew --no-daemon --no-configuration-cache --refresh-dependencies :buildSrc:clean :buildSrc:build

BUILD_SRC_JAR=$(ls buildSrc/build/libs/buildSrc-*.jar | tail -n1)
echo "[INFO] buildSrc jar: $BUILD_SRC_JAR"

if ! jar tf "$BUILD_SRC_JAR" | grep -q 'com/hunet/commonlibrary/build/ConventionPublishingPlugin.class'; then
  echo '[ERROR] Plugin class not found inside buildSrc jar (expected com/hunet/commonlibrary/build/ConventionPublishingPlugin.class)'
  jar tf "$BUILD_SRC_JAR" | sed 's/^/[JAR]/'
  exit 2
fi

echo '[INFO] Descriptor content:'
unzip -p "$BUILD_SRC_JAR" META-INF/gradle-plugins/com.hunet.common-library.convention.properties || true

echo "[INFO] Start project build (tests=\${RUN_TESTS_FLAG})"
if [[ "\${RUN_TESTS_FLAG}" == 'true' ]]; then
  ./gradlew --no-daemon clean build -Pnexus.id=${nexusId} -Pnexus.password=${nexusPassword} -Pprofile='${profileValue}'
else
  ./gradlew --no-daemon clean build -x test -Pnexus.id=${nexusId} -Pnexus.password=${nexusPassword} -Pprofile='${profileValue}'
fi

echo '[INFO] Publish to Nexus'
./gradlew --no-daemon publish -Pnexus.id=${nexusId} -Pnexus.password=${nexusPassword} -Pprofile='${profileValue}'
"""
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
