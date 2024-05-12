import groovy.json.JsonSlurperClassic
import notify.Gmail
import notify.MSTeam

def config
def STATUS
def STAGE_NAME
def ERROR
def HEALTH
def RESPONSE
MSTeam teams
Gmail gmail

def call() {
    config();
    def date = new Date().format('yyMMdd');
    String VERSION_BUILD = "${date}_${env.BRANCH_NAME}_${env.BUILD_NUMBER}";
    BRANCH_ENV = "";         
    if(env.BRANCH_NAME == 'main') {
        BRANCH_ENV = "prod"
    } else if(env.BRANCH_NAME == 'uat') {
        BRANCH_ENV = "uat"
    } else if(env.BRANCH_NAME == 'dev') {
        BRANCH_ENV = "dev"
    }

    podTemplate(yaml: libraryResource('pod.yaml')) {
        node(POD_LABEL) {
            try {
                stage('Clone SCM') {
                    STAGE_NAME = env.STAGE_NAME;
                    cloneSCM();
                }

                container('maven') {
                    stage('Build Code') {
                        STAGE_NAME = env.STAGE_NAME;
                        sh 'whoami'
                        buildCodeOpenShift();
                    }

                    stage('Unit Test') {
                        STAGE_NAME = env.STAGE_NAME;
                        unitTestOpenShift();
                    }

                    stage('SonarQube Analysis') {
                        STAGE_NAME = env.STAGE_NAME;
                        scanCodeOpenShift();
                    }
                }

                if(env.BRANCH_NAME == 'main' || env.BRANCH_NAME == 'uat' || env.BRANCH_NAME == 'dev') {
                    container(name: 'kaniko', shell: '/busybox/sh') {
                        withEnv(['PATH+EXTRA=/busybox:/kaniko']) {
                            stage('Build Docker') {
                                STAGE_NAME = env.STAGE_NAME;
                                sh """#!/busybox/sh
                                /kaniko/executor --insecure \
                                                 --insecure-pull \
                                                 --skip-tls-verify \
                                                 --skip-tls-verify-pull \
                                                 --dockerfile `pwd`/Dockerfile \
                                                 --context `pwd` \
                                                 --destination nexus.thanhnc85.lab:8082/springapp-${BRANCH_ENV}:$VERSION_BUILD \
                                                 --destination nexus.thanhnc85.lab:8082/springapp-${BRANCH_ENV}:latest
                                """
                            }
                        }
                    }

                    container('helm') {
                        stage('Helm deploy OpenShift') {
                            STAGE_NAME = env.STAGE_NAME;
                            sh "helm upgrade --install springapp-${BRANCH_ENV} --values=./helm/springapp/values-${BRANCH_ENV}.yaml ./helm/springapp"
                        }
                    }

                    container(name: 'kaniko', shell: '/busybox/sh') {
                        withEnv(['PATH+EXTRA=/busybox:/kaniko']) {
                            stage('Check health application') {
                                STAGE_NAME = env.STAGE_NAME;
                                retry (5) {
                                    sleep 5
                                    RESPONSE = httpRequest url:"http://springapp.thanhnc85.${BRANCH_ENV}/actuator/health", validResponseCodes: '200', validResponseContent: '"status":"UP"'
                                }
                                if(RESPONSE.status == 200) {
                                    sh """#!/busybox/sh
                                    /kaniko/executor --insecure \
                                                     --insecure-pull \
                                                     --skip-tls-verify \
                                                     --skip-tls-verify-pull \
                                                     --dockerfile `pwd`/Dockerfile \
                                                     --context `pwd` \
                                                     --destination nexus.thanhnc85.lab:8082/springapp-${BRANCH_ENV}:stable
                                    """
                                }
                            }
                        }
                    }

                    if(env.BRANCH_NAME == 'main' || env.BRANCH_NAME.startsWith('uat')) {
                        stage('Tags Deploy') {
                            STAGE_NAME = env.STAGE_NAME;
                            tags();
                        }
                    }
                }
                
                STATUS = "SUCCESS";
                HEALTH = "ACTIVE";
                ERROR = "none";
            } catch(e) {
                STATUS = "FAILED";
                ERROR = e.getMessage();
                HEALTH = "DIED";

                // Rolling back to previous stable image tag for Helm upgrade
                container('helm') {
                    sh "helm upgrade --install springapp-${BRANCH_ENV} --set image.tag=stable --values=./helm/springapp/values-${BRANCH_ENV}.yaml ./helm/springapp"
                }

                throw e
            } finally {
                notifies();
                notification(
                    urlMsTeam: teams.webhookUrl,
                    email: gmail.email,
                    status: STATUS,
                    stage: STAGE_NAME,
                    error: ERROR,
                    health: HEALTH
                )
            }
        }
    }
}

def config() {
    def json = libraryResource 'config.json';
    config = new JsonSlurperClassic().parseText(json);
}

def notifies() {
    teams = new MSTeam();
    teams.webhookUrl = config.parameters[0].notification.msteams.webhookUrl;

    gmail = new Gmail();
    gmail.email = config.parameters[0].notification.gmail.email;
}

def tags() {
    def date = new Date().format('yyMMdd');
    def commitId = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim();
    def tag = env.BRANCH_NAME == 'main' ?  "$date-release" : "$date-$env.BRANCH_NAME-$commitId";
    withCredentials([usernamePassword(credentialsId: "gitlab.s68.thanhnc85", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
        sh "git tag $tag || true";
        sh "git push http://${USERNAME}:${PASSWORD}@gitlab.trainning.s68/thanhnc85/jenkins-spring-app.git $tag || true"
    }
}
