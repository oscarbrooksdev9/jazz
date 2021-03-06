#!groovy?
import groovy.transform.Field
/*
* azure deployment module
*/

@Field def configLoader
@Field def azureUtil
@Field def utilModule
@Field def scmModule
@Field def events

echo "azure deployment module loaded successfully"

def initialize(configLoader, utilModule, scmModule, events, azureUtil){
  this.configLoader = configLoader
  this.utilModule = utilModule
  this.scmModule = scmModule
  this.events = events
  this.azureUtil = azureUtil

}


def createFunction(serviceInfo){
  azureUtil.setAzureVar(serviceInfo)
  loadAzureConfig(serviceInfo)

  def masterKey = invokeAzureCreation(serviceInfo)

  def endpoint = "https://${serviceInfo.stackName}.azurewebsites.net/admin/functions/${serviceInfo.stackName}?code=$masterKey"
  return endpoint

}


def sendAssetCompletedEvent(serviceInfo, assetList) {

  def data = azureUtil.getAzureRequestPayload(serviceInfo)
  data.tagName = 'service'
  data.tagValue = serviceInfo.stackName
  def output = azureUtil.invokeAzureService(data, "getResourcesByServiceName")

  for (item in output.data.result) {
    def assetType = item.kind
    def id = item.id
    switch (item.type) {
      case "Microsoft.Storage/storageAccounts":
        assetType = "storage_account"
        break
      case "Microsoft.ServiceBus/namespaces":
        assetType = "servicebus_namespace"
        break
      case "Microsoft.EventHub/namespaces":
        assetType = "eventhubs_namespace"
        break
      case "Microsoft.DocumentDB/databaseAccounts":
        assetType = "cosmosdb_account"
        break
    }

    events.sendCompletedEvent('CREATE_ASSET', null, utilModule.generateAssetMap(serviceInfo.serviceCatalog['platform'], id, assetType, serviceInfo.serviceCatalog), serviceInfo.envId)

  }

  if (assetList) {
    for (item in assetList) {
      def id = item.azureResourceId
      def assetType = item.type
      events.sendCompletedEvent('CREATE_ASSET', null, utilModule.generateAssetMap(serviceInfo.serviceCatalog['platform'], id, assetType, serviceInfo.serviceCatalog), serviceInfo.envId)

    }
  }
}

def invokeAzureCreation(serviceInfo){

  sh "rm -rf _azureconfig"

  sh "zip -qr content.zip ."
  def zip = sh(script: 'readlink -f ./content.zip', returnStdout: true).trim()

  withCredentials([
    [$class: 'UsernamePasswordMultiBinding', credentialsId: 'AZ_PASSWORD', passwordVariable: 'AZURE_CLIENT_SECRET', usernameVariable: 'UNAME'],
    [$class: 'UsernamePasswordMultiBinding', credentialsId: 'AZ_CLIENTID', passwordVariable: 'AZURE_CLIENT_ID', usernameVariable: 'UNAME'],
    [$class: 'UsernamePasswordMultiBinding', credentialsId: 'AZ_TENANTID', passwordVariable: 'AZURE_TENANT_ID', usernameVariable: 'UNAME'],
    [$class: 'UsernamePasswordMultiBinding', credentialsId: 'AZ_SUBSCRIPTIONID', passwordVariable: 'AZURE_SUBSCRIPTION_ID', usernameVariable: 'UNAME']
  ]) {

    def type = azureUtil.getExtensionName(serviceInfo)
    def runtimeType = azureUtil.getRuntimeType(serviceInfo)
    def tags = azureUtil.getTags(serviceInfo)
    def data = azureUtil.getAzureRequestPayload(serviceInfo)
    data.tags = tags
    data.runtime = runtimeType
    data.resourceName = serviceInfo.resourceName


    def repo_name = "jazz_azure-create-service"
    sh "rm -rf $repo_name"
    sh "mkdir $repo_name"

    def repocloneUrl = scmModule.getCoreRepoCloneUrl(repo_name)
    def masterKey

    dir(repo_name)
      {
        def assetList =[]
        checkout([$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[credentialsId: configLoader.REPOSITORY.CREDENTIAL_ID, url: repocloneUrl]]])
        sh "npm install -s"
        try {

          createStorageAccount(data, serviceInfo)

          if (type) {
            assetList = createEventResource(data, type, serviceInfo)
          }

          createFunctionApp(data)

          def output = azureUtil.invokeAzureService(data, "getMasterKey")
          masterKey = output.data.result.key
          deployFunction(data, zip, type)
          sendAssetCompletedEvent(serviceInfo, assetList)
          return masterKey
        } catch (ex) {
          echo "error occur $ex, rollback starting..."
          deleteResourceByTag(serviceInfo)
          error "Failed creating azure function $ex"
        } finally {
          echo "cleanup ...."
          sh "rm -rf ../content.zip"
          sh "rm -rf ../$repo_name"

        }
      }
  }
}

def deleteResourceByTag(serviceInfo) {
  def data = azureUtil.getAzureRequestPayload(serviceInfo)
  data.tagName = 'service'
  data.tagValue = serviceInfo.stackName
  azureUtil.invokeAzureService(data, "deleteByTag")

}

def createFunctionApp(data) {
  azureUtil.invokeAzureService(data, "createfunction")

}
def deployFunction(data, zip, type) {

  data.zip = zip
  azureUtil.invokeAzureService(data, "deployFunction")
  data.zip = ""
  if (type) {
    azureUtil.invokeAzureService(data, "installFunctionExtensions")

  }

}

def createStorageAccount(data, serviceInfo) {
  data.appName = azureUtil.getStorageAccount(serviceInfo.serviceCatalog, serviceInfo.envId, serviceInfo.storageAccountName)
  azureUtil.invokeAzureService(data, "createStorage")

}

def createEventResource(data, type, serviceInfo) {

  data.eventSourceType = type

  def items =[]
  if (type == 'CosmosDB') {
    data.database_account = azureUtil.getCosmosAccount(serviceInfo.serviceCatalog, serviceInfo.envId, serviceInfo.storageAccountName)
    data.database = azureUtil.getCosmosDatabase(serviceInfo.serviceCatalog, serviceInfo.envId, data.resourceName)
    data.table = azureUtil.getCosmosTable(serviceInfo.serviceCatalog, serviceInfo.envId, data.resourceName)
    azureUtil.invokeAzureService(data, "createEventResource")
    output = azureUtil.invokeAzureService(data, "createDatabase")
    items.add(getAssetDetails(data.resourceName, "cosmosdb_database"))
    items.add(getAssetDetails(data.resourceName, "cosmosdb_collection"))
  } else if (type == 'ServiceBus') {
    data.namespace = azureUtil.getServicebusNamespace(serviceInfo.serviceCatalog, serviceInfo.envId, serviceInfo.storageAccountName)
    azureUtil.invokeAzureService(data, "createEventResource")
    items.add(getAssetDetails(data.resourceName, "servicebus_queue"))
  } else if (type == 'EventHubs') {
    data.namespace = azureUtil.getEventhubsNamespace(serviceInfo.serviceCatalog, serviceInfo.envId, serviceInfo.storageAccountName)
    azureUtil.invokeAzureService(data, "createEventResource")
    items.add(getAssetDetails(data.resourceName, "eventhubs_eventhub"))
  } else if (type == 'Storage') {

    azureUtil.invokeAzureService(data, "createEventResource")
    items.add(getAssetDetails(data.resourceName, "storage_blob_container"))
  }

  return items

}

def loadAzureConfig(serviceInfo) {
  checkoutConfigRepo(serviceInfo.repoCredentialId)
  selectConfig(serviceInfo)
}

def checkoutConfigRepo(repoCredentialId) {

  def configPackURL = scmModule.getCoreRepoCloneUrl("azure-config-pack")

  dir('_azureconfig') {
    checkout([$class: 'GitSCM', branches: [
      [name: '*/master']
    ], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [
      [credentialsId: repoCredentialId, url: configPackURL]
    ]])
  }

}

def selectConfig(serviceInfo) {
  echo "load azure config...."
  def functionName = serviceInfo.stackName
  def serviceCatalog = serviceInfo.serviceCatalog

  if (serviceCatalog['runtime'].indexOf("nodejs") > -1) {
    sh "cp _azureconfig/host.json ."
    sh "cp -rf _azureconfig/nodejs ./$functionName"

  } else {
    sh "mkdir $functionName"   //TODO we will handle other runtime condition later
  }

  def config = serviceInfo.serviceCatalog
  def envId = serviceInfo.envId


  if (serviceInfo.isScheduleEnabled) {
    serviceInfo.resourceType = "cron"
    sh "cp -rf _azureconfig/cron/function.json ./$functionName/function.json"
    def eventScheduleRate = config['eventScheduleRate']
    def timeRate = eventScheduleRate.replace("cron(0", "0 *").replace(")", "").replace(" ?", "")

    sh "sed -i -- 's|\${file(deployment-env.yml):eventScheduleRate}|$timeRate|g' $functionName/function.json"
    if (serviceInfo.serviceCatalog['runtime'].indexOf("c#") > -1) {
      sh "cp _azureconfig/host.json ."
      sh "cp -rf _azureconfig/cron/run.csx ./$functionName"
    }
  } else if (serviceInfo.isQueueEnabled) {

    def resourceName = azureUtil.getQueueName(config, envId)
    writeConfig(functionName, "servicebus", resourceName, serviceInfo, "3.0.0")

  } else if (serviceInfo.isStreamEnabled) {

    def resourceName = azureUtil.getStreamName(config, envId)
    writeConfig(functionName, "eventhubs", resourceName, serviceInfo, "3.0.0")

  } else if (serviceInfo.isStorageEnabled) {

    def resourceName = azureUtil.getStorageName(config, envId)
    writeConfig(functionName, "storage", resourceName, serviceInfo, "3.0.0")
  } else if (serviceInfo.isDbEnabled) {
    def resourceName = azureUtil.getDbName(config, envId)
    writeConfig(functionName, "cosmosdb", resourceName, serviceInfo, "3.0.1")
  }

}

private void writeConfig(functionName, type, resourceName, serviceInfo, version) {
  def extName = azureUtil.getExtensionName(serviceInfo)


  if (serviceInfo.serviceCatalog['runtime'].indexOf("c#") > -1) {
    sh "cp _azureconfig/host.json ."
    sh "cp -rf _azureconfig/$type/run.csx ./$functionName"
  }

  sh "cp -rf _azureconfig/$type/function.json ./$functionName/function.json"
  registerBindingExtension(type)
  sh "sed -i -- 's|{resource_name}|$resourceName|g' $functionName/function.json"
  sh "sed -i -- 's|{extension_name}|$extName|g' extensions.csproj"
  sh "sed -i -- 's|{extension_version}|$version|g' extensions.csproj"
  serviceInfo.resourceType = type
  serviceInfo.resourceName = resourceName
}

def registerBindingExtension(type) {

  sh "cp _azureconfig/extensions.csproj ."

}

def getAssetDetails(id, assetType) {


  def assetItem = [
    type: assetType,
    azureResourceId: id
  ]

  return assetItem


}



return this
