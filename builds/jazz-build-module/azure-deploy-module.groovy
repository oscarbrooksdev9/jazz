#!groovy?

/*
* azure deployment module
*/


echo "azure deployment module loaded successfully"

def createFunction(stackName){

  sh "az functionapp create -s heinajazzdevsa -n $stackName --consumption-plan-location westus2 >> output.log"
  deployFunction(stackName)
}


def deployFunction(stackName){

  sh "zip -qr content.zip ."
  sh "az functionapp deployment source config-zip  -n $stackName --src content.zip >> output.log"
}


def loadAzureConfig(runtime, eventScheduleRate, scmModule, repo_credential_id) {
  checkoutConfigRepo(scmModule, repo_credential_id)
  selectConfig(runtime)
  writeConfigFile(eventScheduleRate)
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

def selectConfig(runtime) {
  echo "load azure config...."
  if (runtime.indexOf("nodejs") > -1) {
    sh "cp _azureconfig/host.json ./host.json"
    sh "cp -rf _azureconfig/Event ."
  }

}

//https://docs.microsoft.com/en-us/azure/azure-functions/functions-bindings-timer#cron-expressions
//TODO this terrible method will be removed when we fix the cron expression from UI
def writeConfigFile(eventScheduleRate) {

  def timeRate = eventScheduleRate.replace("cron(0","0 *").replace(")","").replace(" ?","")
//  timeRate = timeRate.replace(")","")
//  timeRate = timeRate.replace(" ?","")

  sh "sed -i -- 's|\${file(deployment-env.yml):eventScheduleRate}|$timeRate|g' Event/function.json"

}
return this
