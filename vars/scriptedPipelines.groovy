import groovy.json.JsonSlurperClassic
import tools.Maven
import scm.Git
import scan.SonarQube
import scan.QualityGate
import deploy.Nexus
import deploy.Docker
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
Docker docker
Host nginx_host
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

    node(AGENT) {  
        try {
            stage('Clone SCM') {
                STAGE_NAME = env.STAGE_NAME;
                scm();
                cloneSCM();
            }

            stage('Tools install') {
                STAGE_NAME = env.STAGE_NAME;
                tools();
            }

            stage('Build Code') {
                STAGE_NAME = env.STAGE_NAME;
                buildCode(maven.name);
            }

            stage('Unit Test') {
                STAGE_NAME = env.STAGE_NAME;
                unitTest(maven.name);
            }

            stage('SonarQube Analysis') {
                STAGE_NAME = env.STAGE_NAME;
                scan();
                scanCode(sonarServer: sonar.environment, maven: maven.name);
            }

            stage('Quality Gate') {
                STAGE_NAME = env.STAGE_NAME;
                qualityGate(timeout: qg.timeout, unit: qg.unit);
            }

            if(env.BRANCH_NAME == 'main' || env.BRANCH_NAME.startsWith('uat') || env.BRANCH_NAME.startsWith('develop')) {
                stage('Push artifact to Nexus') {
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
                }

                stage('Build Docker') {
                    buildDockerImage(
                        privateHost: docker.privateHost,
                        image: docker.image,
                        version: docker.version
                    );
                    pushDockerImage(
                        credentialId: nexus.credentialId,
                        privateHost: docker.privateHost,
                        image: docker.image,
                        version: docker.version
                    )
                }

                stage('Pull artifact') {
                    STAGE_NAME = env.STAGE_NAME;
                    deploy();
                    // pullArtifact(
                    //     artifactUrl: nexus.artifactUrl,
                    //     credentialId: nexus.credentialId,
                    //     ansibleCredentialId: ansible.credentialId,
                    //     inventory: ansible.inventoryInline,
                    //     playbook: ansible.playbooks[0]
                    // );
                }

                stage('Deploy application') {
                    STAGE_NAME = env.STAGE_NAME;
                    // deployApp(
                    //     credentialId: nexus.credentialId,
                    //     ansibleCredentialId: ansible.credentialId,
                    //     nginx_host: ansible.host.dns,
                    //     app_host: ansible.host.dns,
                    //     protocol: ansible.host.protocol,
                    //     port: ansible.host.port,
                    //     inventory: ansible.inventoryInline,
                    //     playbook: ansible.playbooks[1]
                    // )
                    runDockerContainer(
                        credentialId: docker.credentialId,
                        nexusCredentialId: nexus.credentialId,
                        ip: docker.host.ip,
                        user: docker.host.user,
                        contextPath: docker.host.contextPath,
                        hostDir: docker.host.hostDir,
                        hostPort: docker.host.port,
                        containerDir: docker.containerDir,
                        containerPort: docker.port,
                        privateHost: docker.privateHost,
                        container: docker.container,
                        image: docker.image,
                        version: docker.version
                    )
                    runNginxReverseProxy(
                        credentialId: docker.credentialId,
                        ip: nginx_host.ip,
                        user: nginx_host.user,
                        hostDir: nginx_host.hostDir
                    )
                }

                stage('Check health application') {
                    STAGE_NAME = env.STAGE_NAME;
                    checkHealth(
                        app_host: docker.host.dns,
                        protocol: docker.host.protocol,
                        port: docker.host.port,
                        contextPath: docker.host.contextPath
                    )
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

            // Switch back to previous stable Docker image
            if(STAGE_NAME == "Check health application") {
                String STABLE_VERSION = "stable";
                runDockerContainer(
                    credentialId: docker.credentialId,
                    nexusCredentialId: nexus.credentialId,
                    ip: docker.host.ip,
                    user: docker.host.user,
                    contextPath: docker.host.contextPath,
                    hostDir: docker.host.hostDir,
                    hostPort: docker.host.port,
                    containerDir: docker.containerDir,
                    containerPort: docker.port,
                    privateHost: docker.privateHost,
                    container: docker.container,
                    image: docker.image,
                    version: STABLE_VERSION
                )
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

    // Production
    docker_prod = new Host();
    docker_prod.name = config.parameters[0].deploy.docker.hosts[0].name;
    docker_prod.ip = config.parameters[0].deploy.docker.hosts[0].ip;
    docker_prod.dns = config.parameters[0].deploy.docker.hosts[0].dns;
    docker_prod.port = config.parameters[0].deploy.docker.hosts[0].port;
    docker_prod.protocol = config.parameters[0].deploy.docker.hosts[0].protocol;
    docker_prod.user = config.parameters[0].deploy.docker.hosts[0].user;
    docker_prod.hostDir = config.parameters[0].deploy.docker.hosts[0].hostDir;
    docker_prod.contextPath = config.parameters[0].deploy.docker.hosts[0].contextPath;

    // UAT
    docker_uat = new Host();
    docker_uat.name = config.parameters[0].deploy.docker.hosts[1].name;
    docker_uat.ip = config.parameters[0].deploy.docker.hosts[1].ip;
    docker_uat.dns = config.parameters[0].deploy.docker.hosts[1].dns;
    docker_uat.port = config.parameters[0].deploy.docker.hosts[1].port;
    docker_uat.protocol = config.parameters[0].deploy.docker.hosts[1].protocol;
    docker_uat.user = config.parameters[0].deploy.docker.hosts[1].user;
    docker_uat.hostDir = config.parameters[0].deploy.docker.hosts[1].hostDir;
    docker_uat.contextPath = config.parameters[0].deploy.docker.hosts[1].contextPath;

    // DEV
    docker_dev = new Host();
    docker_dev.name = config.parameters[0].deploy.docker.hosts[2].name;
    docker_dev.ip = config.parameters[0].deploy.docker.hosts[2].ip;
    docker_dev.dns = config.parameters[0].deploy.docker.hosts[2].dns;
    docker_dev.port = config.parameters[0].deploy.docker.hosts[2].port;
    docker_dev.protocol = config.parameters[0].deploy.docker.hosts[2].protocol;
    docker_dev.user = config.parameters[0].deploy.docker.hosts[2].user;
    docker_dev.hostDir = config.parameters[0].deploy.docker.hosts[2].hostDir;
    docker_dev.contextPath = config.parameters[0].deploy.docker.hosts[2].contextPath;

    // Nginx Reverse Proxy
    nginx_host = new Host();
    nginx_host.name = config.parameters[0].deploy.docker.nginxHosts[0].name;
    nginx_host.ip = config.parameters[0].deploy.docker.nginxHosts[0].ip;
    nginx_host.dns = config.parameters[0].deploy.docker.nginxHosts[0].dns;
    nginx_host.port = config.parameters[0].deploy.docker.nginxHosts[0].port;
    nginx_host.protocol = config.parameters[0].deploy.docker.nginxHosts[0].protocol;
    nginx_host.user = config.parameters[0].deploy.docker.nginxHosts[0].user;
    nginx_host.hostDir = config.parameters[0].deploy.docker.nginxHosts[0].hostDir;

    docker = new Docker();
    String versionDocker = "${date}_${env.BRANCH_NAME}_${env.BUILD_NUMBER}";

    docker.credentialId = config.parameters[0].deploy.docker.credentialId;
    docker.privateHost = config.parameters[0].deploy.docker.privateHost;
    docker.image = config.parameters[0].deploy.docker.image;
    docker.container = "$docker.image-$env.BRANCH_NAME"
    docker.repository = config.parameters[0].deploy.docker.repository;
    docker.port = config.parameters[0].deploy.docker.port;
    docker.containerDir = config.parameters[0].deploy.docker.containerDir;
    docker.version = versionDocker;

    // Check branch to deploy on host
    if(env.BRANCH_NAME == 'main') {
        docker.host = docker_prod;
    } else if(env.BRANCH_NAME.startsWith('uat')) {
        docker.host = docker_uat;
    } else if(env.BRANCH_NAME.startsWith('develop')) {
        docker.host = docker_dev;
    } else {
        echo "Branch $env.BRANCH_NAME not found !";
    }
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
        sh "GIT_SSH_COMMAND=\"ssh -i \$KEY\" git push origin $tag || true";
    }
}