#!groovy?
import groovy.transform.Field

/*
* azure deployment module
*/

@Field def configLoader
@Field def azureUtil

echo "azure deployment module loaded successfully"

def initialize(configData, azureUtility){
  configLoader = configData
  azureUtil = azureUtility

  configLoader.AZURE.APP_INSIGHTS_KEY = sh (
      script: "az resource show  -n ${configLoader.AZURE.APP_INSIGHTS} --resource-type 'Microsoft.Insights/components' --query properties.InstrumentationKey --output tsv",
      returnStdout: true
  ).trim()


  configLoader.AZURE.SERVICEBUS_CONNECTION_STRING = sh (
      script: "az servicebus namespace authorization-rule keys list --namespace-name ${configLoader.AZURE.SERVICEBUS_NAMESPACE} --name RootManageSharedAccessKey --query primaryConnectionString --output tsv",
      returnStdout: true
  ).trim()

}

def createFunction(stackName){

  sh "az functionapp create -s ${configLoader.AZURE.STORAGE_ACCOUNT} -g ${configLoader.AZURE.RESOURCE_GROUP}  -n $stackName --consumption-plan-location ${configLoader.AZURE.LOCATION} >> output.log"
  sh "az functionapp config appsettings set -n $stackName " +
          "--settings APPINSIGHTS_INSTRUMENTATIONKEY=${configLoader.AZURE.APP_INSIGHTS_KEY} " +
          "SAS_SERVICEBUS='${configLoader.AZURE.SERVICEBUS_CONNECTION_STRING}' >> output.log"
  deployFunction(stackName)
}


def deployFunction(stackName){
  try {
    sh "zip -qr content.zip ."
    sh "az functionapp deployment source config-zip  -n $stackName --src content.zip -g ${configLoader.AZURE.RESOURCE_GROUP} >> output.log"
  } catch (ex) {
      echo "deploy function got exception continue..."
  }

}


def loadAzureConfig(environment_logical_id, runtime, config, scmModule, repo_credential_id, isEventSchdld, isScheduleEnabled, isEc2EventEnabled, isS3EventEnabled, isSQSEventEnabled, isStreamEnabled, isDynamoDbEnabled) {
  checkoutConfigRepo(scmModule, repo_credential_id)
  selectConfig(runtime, isEventSchdld, isScheduleEnabled, isEc2EventEnabled, isS3EventEnabled, isSQSEventEnabled, isStreamEnabled, isDynamoDbEnabled)
  writeConfigFile(config, environment_logical_id)
}

def checkoutConfigRepo(scmModule, repo_credential_id) {

  def configPackURL = scmModule.getCoreRepoCloneUrl("azure-config-pack")

  dir('_azureconfig') {
    checkout([$class: 'GitSCM', branches: [
            [name: '*/master']
    ], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [
            [credentialsId: repo_credential_id, url: configPackURL]
    ]])
  }

}

def selectConfig(runtime, isEventSchdld, isScheduleEnabled, isEc2EventEnabled, isS3EventEnabled, isSQSEventEnabled, isStreamEnabled, isDynamoDbEnabled) {
  echo "load azure config...."
  if (runtime.indexOf("nodejs") > -1) {
    sh "cp _azureconfig/host.json ."
    sh "cp -rf _azureconfig/nodejs ./Event"  //TODO we should not need to use this index.js but currently we are using node6 for aws and it does not work with node 8+ and azure needs node8+
  } else {
    sh "mkdir Event"   //TODO we will handle other runtime condition later
  }

  if (isScheduleEnabled) {
    sh "cp -rf _azureconfig/cron/function.json ./Event/function.json"
  } else if (isSQSEventEnabled) {
    sh "cp -rf _azureconfig/queue/function.json ./Event/function.json"
    sh "cp _azureconfig/extensions.csproj ."
    sh "cp -rf _azureconfig/bin ."
  }


}

//https://docs.microsoft.com/en-us/azure/azure-functions/functions-bindings-timer#cron-expressions
//TODO this terrible method will be removed when we fix the cron expression from UI
def writeConfigFile(config, environment_logical_id) {
  if (config['eventScheduleRate']) {
    def eventScheduleRate = config['eventScheduleRate']
    def timeRate = eventScheduleRate.replace("cron(0", "0 *").replace(")", "").replace(" ?", "")

    sh "sed -i -- 's|\${file(deployment-env.yml):eventScheduleRate}|$timeRate|g' Event/function.json"
  }

  if (config['event_source_sqs']) {
    def queueName = azureUtil.getQueueName(config, environment_logical_id)
    sh "sed -i -- 's|{queue_name}|$queueName|g' Event/function.json"
    sh "sed -i -- 's|{extension_name}|ServiceBus|g' extensions.csproj"

    azureUtil.createQueue(queueName)
  }
}

return this
