param name string
param location string
param tags object

@allowed([
  'Standard'
  'Premium'
])
param sku string
param capacity int
param zoneRedundant bool

@allowed([
  'Enabled'
  'Disabled'
])
param publicNetworkAccess string
param logAnalyticsWorkspaceId string

@description('Topic subscriptions for downstream consumers (FR6).')
param subscriptionNames array = [
  'finance'
  'notifications'
]

var queueName = 'payment-notifications'
var topicName = 'premium-payment-events'

resource namespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: name
  location: location
  tags: tags
  sku: sku == 'Premium'
    ? {
        name: sku
        tier: sku
        capacity: capacity
      }
    : {
        name: sku
        tier: sku
      }
  properties: {
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
    publicNetworkAccess: publicNetworkAccess
    zoneRedundant: zoneRedundant
  }
}

resource queue 'Microsoft.ServiceBus/namespaces/queues@2022-10-01-preview' = {
  parent: namespace
  name: queueName
  properties: {
    lockDuration: 'PT1M'
    maxDeliveryCount: 10
    deadLetteringOnMessageExpiration: true
    defaultMessageTimeToLive: 'P14D'
    requiresDuplicateDetection: false // dedup is authoritative in the State worker, which must see every attempt id (FR7)
    requiresSession: false
    enableBatchedOperations: true
    maxSizeInMegabytes: 5120
  }
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: namespace
  name: topicName
  properties: {
    defaultMessageTimeToLive: 'P14D'
    requiresDuplicateDetection: true // second guard for consumers; messageId = idempotency key
    duplicateDetectionHistoryTimeWindow: 'P1D'
    enableBatchedOperations: true
    maxSizeInMegabytes: 5120
  }
}

resource subscriptions 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = [
  for subscriptionName in subscriptionNames: {
    parent: topic
    name: subscriptionName
    properties: {
      lockDuration: 'PT1M'
      maxDeliveryCount: 10
      deadLetteringOnMessageExpiration: true
      deadLetteringOnFilterEvaluationExceptions: true
      defaultMessageTimeToLive: 'P14D'
    }
  }
]

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: namespace
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

output namespaceId string = namespace.id
output name string = namespace.name
output fqdn string = '${namespace.name}.servicebus.windows.net'
output queueName string = queue.name
output topicName string = topic.name
