def BUILD_DOCKER_IMAGE = 'gradle:8.13.0-jdk21' // wrapper(8.13)와 동일 버전 이미지 (gradle wrapper 강제 사용 시 JDK만 맞춰도 무방)

def targetBranch = "${TARGET_BRANCH}" // 환경변수 기반 (필요시 parameters 로 전환 가능)

def CLEAR_CACHE = (env.CLEAR_CACHE ?: 'false').toBoolean()

def RUN_TESTS = (env.RUN_TESTS ?: 'false').toBoolean() // 필요시 true 로 설정

pipeline {
  agent any
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
          // 선택적 캐시 삭제 (Gradle 변형 캐시 문제 대응)
          if (CLEAR_CACHE) {
            sh 'rm -rf ~/.gradle/caches/*/transforms ~/.gradle/caches/modules-2 ~/.gradle/caches/jars-* || true'
          }
        }}
      }
    }

    stage('build & publish') {
      steps {
        withCredentials([ usernamePassword(
          credentialsId: 'NEXUS_CRED',
          usernameVariable: 'nexusId',
          passwordVariable: 'nexusPassword') ]) {
          withDockerContainer(args: '-u root:root', image: BUILD_DOCKER_IMAGE) {
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

echo '[INFO] Build buildSrc first (clean)'
./gradlew --no-daemon --no-configuration-cache --no-build-cache --refresh-dependencies :buildSrc:clean :buildSrc:build

echo '[INFO] Verify plugin class presence'
if ! jar tf buildSrc/build/libs/buildSrc-*.jar | grep -q 'ConventionPublishingPlugin.class'; then
  echo '[ERROR] Plugin class missing in buildSrc JAR'
  exit 1
fi

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
