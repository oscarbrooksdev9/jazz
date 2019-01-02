#!groovy?
import groovy.transform.Field

@Field def configLoader
@Field def resourceUtil


echo "azure util loaded successfully"

def initialize(configData, resourceUtility){

    configLoader = configData
    resourceUtil = resourceUtility
}


def createQueue(name) {

    sh "az servicebus queue create --namespace-name ${configLoader.AZURE.SERVICEBUS_NAMESPACE} -n $name"

}

def getQueueName(serviceMetadata, env) {

    def queueNameInput = serviceMetadata['event_source_sqs']
    def queueNameArray = queueNameInput.split(':')
    return resourceUtil.getResourceName(queueNameArray[queueNameArray.size() - 1], env)
}

return this