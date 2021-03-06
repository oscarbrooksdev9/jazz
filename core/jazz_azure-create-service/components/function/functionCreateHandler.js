const sbHandler = require('./servicebusHandler');
const dbHandler = require('./cosmosDBHandler');
const ehHandler = require('./eventhubHandler');
const storageHandler = require('./storageblobHandler');

async function createDependency(data, factory) {

  let type = data.eventSourceType;

  switch (type) {
    case 'Storage':
      await storageHandler.create(data, await factory.getResource('StorageManagementClient'));
      break;
    case 'CosmosDB':
      await dbHandler.create(data, await factory.getResource('CosmosDBManagementClient'));
      break;
    case 'EventHubs':
      await ehHandler.create(data, await factory.getResource('EventHubManagementClient'));
      break;
    case 'ServiceBus':
      await sbHandler.create(data, await factory.getResource('ServiceBusManagementClient'));
      break;
    default:
  }

}


async function getConnectionString(data, factory) {

  let type = data.eventSourceType;
  let resource = '';
  switch (type) {
    case 'CosmosDB':
      resource = await dbHandler.getConnectionString(data, await factory.getResource('CosmosDBManagementClient'));
      break;
    case 'EventHubs':
      resource = await ehHandler.getConnectionString(data, await factory.getResource('EventHubManagementClient'));
      break;
    case 'ServiceBus':
      resource = await sbHandler.getConnectionString(data, await factory.getResource('ServiceBusManagementClient'));
      break;
    default:
  }

  return resource;
}

module.exports = {
  createDependency,
  getConnectionString
};
