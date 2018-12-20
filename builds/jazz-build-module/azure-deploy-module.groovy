#!groovy?
import groovy.transform.Field

/*
* azure deployment module
*/

@Field def config_loader

echo "azure deployment module loaded successfully"



def initialize(configData){
  config_loader = configData

}

def createFunction(stackName){

  sh "az functionapp create -s ${config_loader.AZURE.STORAGE_ACCOUNT} -g ${config_loader.AZURE.RESOURCE_GROUP}  -n $stackName --consumption-plan-location ${config_loader.AZURE.LOCATION} >> output.log"
  sh "az functionapp config appsettings set -n $stackName " +
    "--settings APPINSIGHTS_INSTRUMENTATIONKEY=${config_loader.AZURE.APP_INSIGHTS} >> output.log"
  deployFunction(stackName)
}


def deployFunction(stackName){
  try {
    sh "zip -qr content.zip ."
    sh "az functionapp deployment source config-zip  -n $stackName --src content.zip -g ${config_loader.AZURE.RESOURCE_GROUP} >> output.log"
  } catch (ex) {
    echo "deploy function got exception  continue..."
  }

}


def loadAzureConfig(runtime, config, scmModule, repo_credential_id, isEventSchdld, isScheduleEnabled, isEc2EventEnabled, isS3EventEnabled, isSQSEventEnabled, isStreamEnabled, isDynamoDbEnabled) {
  checkoutConfigRepo(scmModule, repo_credential_id)
  selectConfig(runtime, isEventSchdld, isScheduleEnabled, isEc2EventEnabled, isS3EventEnabled, isSQSEventEnabled, isStreamEnabled, isDynamoDbEnabled)
  writeConfigFile(config)
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
    sh "cp _azureconfig/extensions.csproj ."
    sh "cp -rf _azureconfig/bin ."
    sh "cp -rf _azureconfig/nodejs ./Event"  //TODO we should not need to use this index.js but currently we are using node6 for aws and it does not work with node 8+ and azure needs node8+
  } else {
    sh "mkdir Event"   //TODO we will handle other runtime condition later
  }

  if (isScheduleEnabled) {
    sh "cp -rf _azureconfig/cron/function.json ./Event/function.json"
  } else if (isSQSEventEnabled) {
    sh "cp -rf _azureconfig/queue/function.json ./Event/function.json"
  }


}

//https://docs.microsoft.com/en-us/azure/azure-functions/functions-bindings-timer#cron-expressions
//TODO this terrible method will be removed when we fix the cron expression from UI
def writeConfigFile(config) {
  if (config['eventScheduleRate']) {
    def eventScheduleRate = config['eventScheduleRate']
    def timeRate = eventScheduleRate.replace("cron(0", "0 *").replace(")", "").replace(" ?", "")

    sh "sed -i -- 's|\${file(deployment-env.yml):eventScheduleRate}|$timeRate|g' Event/function.json"
  }

  if (config['event_source_sqs']) {
    def queueNameInput = config['event_source_sqs']
    def queueNameArray = queueNameInput.split(':')
    def queueName = queueNameArray[queueNameArray.size() - 1]
    sh "sed -i -- 's|{queue_name}|$queueName|g' Event/function.json"
    sh "sed -i -- 's|{extension_name}|Storage|g' extensions.csproj"
  }
}
return this
