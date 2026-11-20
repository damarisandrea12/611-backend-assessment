// Least-privilege role assignments (NFR6). Each identity gets the narrowest scope that works:
//
// | Identity       | Role                                  | Scope                         |
// |----------------|---------------------------------------|-------------------------------|
// | intake         | Azure Service Bus Data Sender          | queue payment-notifications   |
// | intake         | Key Vault Secrets User                 | vault (webhook secrets)       |
// | state-worker   | Azure Service Bus Data Receiver        | queue payment-notifications   |
// | state-worker   | Azure Service Bus Data Receiver        | queue DLQ (via queue scope)   |
// | state-worker   | Azure Service Bus Data Sender          | topic premium-payment-events  |
// | sync-worker    | Key Vault Secrets User                 | vault (Salesforce credentials)|
// | ops-api        | Azure Service Bus Data Receiver/Sender | queue (DLQ replay only)       |
// | all            | AcrPull                                | registry                      |
// | all            | Monitoring Metrics Publisher           | Application Insights          |
// | consumers      | Azure Service Bus Data Receiver        | their own subscription only   |
//
// SQL permissions are database roles granted with T-SQL (infra/sql/grant-database-roles.sql),
// because Azure RBAC does not reach inside the database.
//
// Note: Key Vault RBAC is assigned at vault scope because secret values (and therefore the secret
// resources) are created out of band. With separate secrets per consumer the next step is to scope
// each assignment to the individual secret (Microsoft.KeyVault/vaults/secrets) once they exist.
param serviceBusName string
param keyVaultName string
param registryName string
param appInsightsName string
param intakePrincipalId string
param stateWorkerPrincipalId string
param syncWorkerPrincipalId string
param opsApiPrincipalId string
param downstreamConsumers array

var roles = {
  serviceBusDataSender: '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39'
  serviceBusDataReceiver: '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0'
  keyVaultSecretsUser: '4633458b-17de-408a-b874-0445c86b69e6'
  acrPull: '7f951dda-4ed3-4680-a7ca-43fe172d538d'
  monitoringMetricsPublisher: '3913510d-42f4-4e42-8a64-420c390055eb'
}

resource namespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' existing = {
  name: serviceBusName
}

resource queue 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' existing = {
  parent: namespace
  name: 'payment-notifications'
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' existing = {
  parent: namespace
  name: 'premium-payment-events'
}

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' existing = {
  name: keyVaultName
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: registryName
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: appInsightsName
}

var allPrincipals = [
  intakePrincipalId
  stateWorkerPrincipalId
  syncWorkerPrincipalId
  opsApiPrincipalId
]

resource intakeSendsToQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(queue.id, intakePrincipalId, roles.serviceBusDataSender)
  scope: queue
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.serviceBusDataSender)
    principalId: intakePrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource stateWorkerReceivesFromQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(queue.id, stateWorkerPrincipalId, roles.serviceBusDataReceiver)
  scope: queue
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.serviceBusDataReceiver)
    principalId: stateWorkerPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource stateWorkerSendsToTopic 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(topic.id, stateWorkerPrincipalId, roles.serviceBusDataSender)
  scope: topic
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.serviceBusDataSender)
    principalId: stateWorkerPrincipalId
    principalType: 'ServicePrincipal'
  }
}

// Ops API replays DLQ messages to the main queue: receive (from DLQ) + send (to queue), queue scope only.
resource opsApiReceivesFromQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(queue.id, opsApiPrincipalId, roles.serviceBusDataReceiver)
  scope: queue
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.serviceBusDataReceiver)
    principalId: opsApiPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource opsApiSendsToQueue 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(queue.id, opsApiPrincipalId, roles.serviceBusDataSender)
  scope: queue
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.serviceBusDataSender)
    principalId: opsApiPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource intakeReadsSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vault.id, intakePrincipalId, roles.keyVaultSecretsUser)
  scope: vault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.keyVaultSecretsUser)
    principalId: intakePrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource syncWorkerReadsSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(vault.id, syncWorkerPrincipalId, roles.keyVaultSecretsUser)
  scope: vault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.keyVaultSecretsUser)
    principalId: syncWorkerPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource acrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for principalId in allPrincipals: {
    name: guid(registry.id, principalId, roles.acrPull)
    scope: registry
    properties: {
      roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.acrPull)
      principalId: principalId
      principalType: 'ServicePrincipal'
    }
  }
]

resource telemetryPublisher 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for principalId in allPrincipals: {
    name: guid(appInsights.id, principalId, roles.monitoringMetricsPublisher)
    scope: appInsights
    properties: {
      roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.monitoringMetricsPublisher)
      principalId: principalId
      principalType: 'ServicePrincipal'
    }
  }
]

resource consumerSubscriptions 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' existing = [
  for consumer in downstreamConsumers: {
    parent: topic
    name: consumer.subscription
  }
]

resource consumersReceive 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for (consumer, i) in downstreamConsumers: {
    name: guid(topic.id, consumer.subscription, consumer.principalId, roles.serviceBusDataReceiver)
    scope: consumerSubscriptions[i]
    properties: {
      roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roles.serviceBusDataReceiver)
      principalId: consumer.principalId
      principalType: 'ServicePrincipal'
    }
  }
]
