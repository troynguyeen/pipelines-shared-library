import groovy.json.JsonSlurperClassic
import tools.Maven
import scm.Git
import scan.SonarQube
import scan.QualityGate
import deploy.Nexus
import deploy.Pom
import deploy.Asset
import deploy.Ansible
import deploy.Host
import notify.Gmail
import notify.MSTeam

def config
def AGENT
def STATUS
def STAGE_NAME
def ERROR
def HEALTH
Git git
Maven maven
SonarQube sonar
QualityGate qg
Nexus nexus
Pom pom
Asset asset
Ansible ansible
Host host_prod
Host host_uat
Host host_dev
MSTeam teams
Gmail gmail

def call() {
    config();
    agent();
    notifies();

    pipeline {
        agent { label AGENT }
        environment {
            HEALTH = "ACTIVE"
            STATUS = "SUCCESS";
            ERROR = "none";
        }
        stages {
            stage('Tools install') {
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            tools();
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Build Code') {
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            buildCode(maven.name);
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Unit Test') {
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            unitTest(maven.name);
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('SonarQube Analysis') {
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            scan();
                            scanCode(sonarServer: sonar.environment, maven: maven.name);
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Quality Gate') {
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            qualityGate(timeout: qg.timeout, unit: qg.unit);
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Push artifact to Nexus') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'uat*'
                        branch 'develop*'
                    }
                    not { changeRequest() }
                }
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            artifact();
                            pushArtifact(
                                credentialId: nexus.credentialId,
                                url: nexus.url,
                                repository: nexus.repository,
                                groupId: pom.groupId,
                                artifactId: pom.artifactId,
                                version: pom.version,
                                file: asset.file,
                                extension: asset.extension
                            );
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Pull artifact to config application') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'uat*'
                        branch 'develop*'
                    }
                    not { changeRequest() }
                }
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            deploy();
                            pullArtifact(
                                artifactUrl: nexus.artifactUrl,
                                credentialId: nexus.credentialId,
                                ansibleCredentialId: ansible.credentialId,
                                inventory: ansible.inventoryInline,
                                playbook: ansible.playbooks[0]
                            );
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Deploy application on Nginx Reverse Proxy') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'uat*'
                        branch 'develop*'
                    }
                    not { changeRequest() }
                }
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            deployApp(
                                credentialId: nexus.credentialId,
                                ansibleCredentialId: ansible.credentialId,
                                nginx_host: ansible.host.dns,
                                app_host: ansible.host.dns,
                                protocol: ansible.host.protocol,
                                port: ansible.host.port,
                                inventory: ansible.inventoryInline,
                                playbook: ansible.playbooks[1]
                            )
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Check health application') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'uat*'
                        branch 'develop*'
                    }
                    not { changeRequest() }
                }
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            checkHealth(
                                app_host: ansible.host.dns,
                                protocol: ansible.host.protocol,
                                port: ansible.host.port
                            )
                        } catch(e) {
                            STATUS = "FAILED";
                            HEALTH="DIED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
            stage('Tags Deploy') {
                when {
                    anyOf {
                        branch 'main'
                        branch 'uat*'
                    }
                    not { changeRequest() }
                }
                steps {
                    script {
                        try {
                            STAGE_NAME = env.STAGE_NAME;
                            scm();
                            tags();
                        } catch(e) {
                            STATUS = "FAILED";
                            ERROR = e.getMessage();
                            error "[${env.STAGE_NAME}]: ${e.getMessage()}";
                        }
                    }
                }
            }
        }
        post {
            always {
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

def agent() {
    AGENT = config.parameters[0].agent.labels[1];
}

def tools() {
    maven = new Maven();
    maven.name = config.parameters[0].tools.maven;
}

def scm() {
    git = new Git();
    git.credentialId = config.parameters[0].scm.git.credentialId;
}

def scan() {
    sonar = new SonarQube();
    sonar.environment = config.parameters[0].scan.env;
    sonar.version = config.parameters[0].scan.version;

    qg = new QualityGate();
    qg.timeout = config.parameters[0].scan.qualityGate.timeout;
    qg.unit = config.parameters[0].scan.qualityGate.unit;
}

def artifact() {
    def date = new Date().format('yyMMdd');
    def pomFile = readMavenPom file: "pom.xml";
    def filesByGlob = findFiles(glob: "target/*.${pomFile.packaging}");
    def file = filesByGlob[0].path;
    def groupId = "${pomFile.groupId}.${env.BRANCH_NAME}";
    def version = "${pomFile.version}_${date}_${env.BUILD_NUMBER}";

    pom = new Pom();
    pom.file = config.parameters[0].deploy.pom.file;
    pom.groupId = groupId;
    pom.artifactId = pomFile.artifactId;
    pom.version = version;

    asset = new Asset();
    asset.file = file;
    asset.extension = pomFile.packaging;

    nexus = new Nexus();
    nexus.url = config.parameters[0].deploy.nexus.url;
    nexus.protocol = config.parameters[0].deploy.nexus.protocol;
    nexus.version = config.parameters[0].deploy.nexus.version;
    nexus.credentialId = config.parameters[0].deploy.nexus.credentialId;
    nexus.repository = config.parameters[0].deploy.nexus.repository;
    nexus.artifactVersion = config.parameters[0].deploy.nexus.artifactVersion;
    def artifactUrl = "$nexus.url/service/rest/v1/search/assets/download?sort=$nexus.artifactVersion&repository=$nexus.repository&maven.groupId=$groupId&maven.artifactId=$pomFile.artifactId&maven.baseVersion=$version&maven.extension=$pomFile.packaging";
    nexus.artifactUrl = artifactUrl;
}

def deploy() {
    host_prod = new Host();
    host_prod.name = config.parameters[0].deploy.ansible.hosts[0].name;
    host_prod.ip = config.parameters[0].deploy.ansible.hosts[0].ip;
    host_prod.dns = config.parameters[0].deploy.ansible.hosts[0].dns;
    host_prod.port = config.parameters[0].deploy.ansible.hosts[0].port;
    host_prod.protocol = config.parameters[0].deploy.ansible.hosts[0].protocol;
    host_prod.user = config.parameters[0].deploy.ansible.hosts[0].user;

    host_uat = new Host();
    host_uat.name = config.parameters[0].deploy.ansible.hosts[1].name;
    host_uat.ip = config.parameters[0].deploy.ansible.hosts[1].ip;
    host_uat.dns = config.parameters[0].deploy.ansible.hosts[1].dns;
    host_uat.port = config.parameters[0].deploy.ansible.hosts[1].port;
    host_uat.protocol = config.parameters[0].deploy.ansible.hosts[1].protocol;
    host_uat.user = config.parameters[0].deploy.ansible.hosts[1].user;

    host_dev = new Host();
    host_dev.name = config.parameters[0].deploy.ansible.hosts[2].name;
    host_dev.ip = config.parameters[0].deploy.ansible.hosts[2].ip;
    host_dev.dns = config.parameters[0].deploy.ansible.hosts[2].dns;
    host_dev.port = config.parameters[0].deploy.ansible.hosts[2].port;
    host_dev.protocol = config.parameters[0].deploy.ansible.hosts[2].protocol;
    host_dev.user = config.parameters[0].deploy.ansible.hosts[2].user;

    ansible = new Ansible();
    ansible.credentialId = config.parameters[0].deploy.ansible.credentialId;
    ansible.inventory = config.parameters[0].deploy.ansible.inventory;
    ansible.playbooks = new ArrayList();
    ansible.playbooks.add(config.parameters[0].deploy.ansible.playbooks[0].playbook);
    ansible.playbooks.add(config.parameters[0].deploy.ansible.playbooks[1].playbook);
    ansible.hosts = new ArrayList<Host>();
    ansible.hosts.add(host_prod);
    ansible.hosts.add(host_uat);
    ansible.hosts.add(host_dev);
    if(env.BRANCH_NAME == 'main') {
        ansible.host = host_prod;
    } else if(env.BRANCH_NAME.startsWith('uat')) {
        ansible.host = host_uat;
    } else if(env.BRANCH_NAME.startsWith('develop')) {
        ansible.host = host_dev;
    } else {
        echo "Branch $env.BRANCH_NAME not found !";
    }
    //Inventory host_list plugin
    ansible.inventoryInline = "$ansible.host.ip,";
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
    withCredentials([sshUserPrivateKey(credentialsId: git.credentialId, keyFileVariable: 'KEY')]) {
        sh "git tag $tag || true";
        sh "GIT_SSH_COMMAND=\"ssh -i \$KEY\" git push origin $tag";
    }
}