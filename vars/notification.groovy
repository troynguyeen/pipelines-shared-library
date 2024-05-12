def call(Map config = [:]) {
    def status = config.status ?: 'SUCCESS';
    def color = '00ff00';
    def colorSlack = 'good';

    switch(status) {
        case 'SUCCESS':
            color = '00ff00';
            colorSlack = 'good';
        break
        case 'FAILED':
            color = 'ff0000';
            colorSlack = 'danger';
        break
        default:
            color = 'ff0000';
            colorSlack = 'warning';
        break
    }

    //MS TEAMS
    office365ConnectorSend (
        webhookUrl: config.urlMsTeam,
        color: color,
        status: "BUILD $status",
        message: "Latest status of build #${env.BUILD_NUMBER}"
    )

    //SLACK
    def slackMessage
    if(status == 'SUCCESS') {
        slackMessage = "SUCCESSFUL: ${env.JOB_NAME} \nBuild #${env.BUILD_NUMBER} - ${env.BUILD_URL}";
    } else {
        slackMessage = "FAILED: ${env.JOB_NAME} at \"${config.stage}\" stage \nBuild #${env.BUILD_NUMBER} - ${env.BUILD_URL} \nERROR: ${config.error}";
    }
    slackSend (
        color: colorSlack, 
        message: slackMessage
    )

    //GMAIL
    def subject
    def body
    if(status == 'SUCCESS') {
        subject = "SUCCESSFUL - ${env.JOB_NAME} - Build #${env.BUILD_NUMBER}";
        body = """
            <h2>SUCCESSFUL - ${env.JOB_NAME} Build #${env.BUILD_NUMBER}:</h2>
            <p>HEALTH: <span style="color: green; font-weight: bold;">${config.health}</span></p>
            <p>Check console output at <a href='${env.BUILD_URL}'>${env.JOB_NAME} #${env.BUILD_NUMBER}</a></p>
        """;
    } else {
        subject = "FAILED - ${env.JOB_NAME} at \"${config.stage}\" stage - Build #${env.BUILD_NUMBER}";
        body = """
            <h2>FAILED - ${env.JOB_NAME} at \"${config.stage}\" stage - Build #${env.BUILD_NUMBER}:</h2>
            <p>ERROR: ${config.error}</p>
            <p>HEALTH: <span style="color: red; font-weight: bold;">${config.health}</span></p>
            <p>Check console output at <a href='${env.BUILD_URL}'>${env.JOB_NAME} #${env.BUILD_NUMBER}</a></p>
        """;
    }
    emailext (
        to: "$config.email",
        subject: subject,
        body: body,
        recipientProviders: [[$class: 'DevelopersRecipientProvider']],
    )
}