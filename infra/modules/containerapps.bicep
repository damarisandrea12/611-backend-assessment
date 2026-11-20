param baseName string
param location string
param tags object

@description('Delegated subnet; empty = no VNet integration (dev).')
param infrastructureSubnetId string
param zoneRedundant bool
param logAnalyticsWorkspaceId string
param registryLoginServer string
param imageTag string
param keyVaultUri string
param serviceBusFqdn string
param serviceBusName string
param queueName string
param topicName string
param sqlServerFqdn string
param sqlDatabaseName string
param appInsightsConnectionString string

@description('[{ name, country }]: one webhook secret reference per provider.')
param providers array

@description('Outputs of identities.bicep.')
param identities object
param intakeMinReplicas int
param intakeMaxReplicas int
param workerMinReplicas int

var image = '${registryLoginServer}/payment-notifications:${imageTag}'
var internalOnly = !empty(infrastructureSubnetId)

resource managedEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'cae-${baseName}'
  location: location
  tags: tags
  properties: {
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    vnetConfiguration: internalOnly
      ? {
          infrastructureSubnetId: infrastructureSubnetId
          internal: true
        }
      : null
    zoneRedundant: zoneRedundant
    appLogsConfiguration: {
      destination: 'azure-monitor'
    }
  }
}

resource environmentDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: managedEnvironment
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
  }
}

func commonEnv(clientId string, profile string, aiConnectionString string, sbFqdn string) array => [
  {
    name: 'SPRING_PROFILES_ACTIVE'
    value: profile
  }
  {
    name: 'AZURE_CLIENT_ID'
    value: clientId
  }
  {
    name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
    value: aiConnectionString
  }
  {
    name: 'APPLICATIONINSIGHTS_AUTHENTICATION_STRING'
    value: 'Authorization=AAD;ClientId=${clientId}'
  }
  {
    name: 'SERVICEBUS_NAMESPACE'
    value: sbFqdn
  }
]

var probes = [
  {
    type: 'Liveness'
    httpGet: {
      path: '/actuator/health/liveness'
      port: 8080
    }
    periodSeconds: 10
  }
  {
    type: 'Readiness'
    httpGet: {
      path: '/actuator/health/readiness'
      port: 8080
    }
    periodSeconds: 5
  }
]

var webhookSecrets = [
  for p in providers: {
    name: '${p.name}-webhook-secrets'
    keyVaultUrl: '${keyVaultUri}secrets/${p.name}-webhook-secrets'
    identity: identities.intake.id
  }
]

var webhookSecretEnv = [
  for p in providers: {
    name: '${toUpper(p.name)}_WEBHOOK_SECRETS'
    secretRef: '${p.name}-webhook-secrets'
  }
]

resource intake 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'ca-${baseName}-intake'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identities.intake.id}': {}
    }
  }
  properties: {
    environmentId: managedEnvironment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
      }
      registries: [
        {
          server: registryLoginServer
          identity: identities.intake.id
        }
      ]
      secrets: webhookSecrets
    }
    template: {
      containers: [
        {
          name: 'intake'
          image: image
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv(identities.intake.clientId, 'intake', appInsightsConnectionString, serviceBusFqdn), webhookSecretEnv, [
            {
              name: 'SERVICEBUS_QUEUE'
              value: queueName
            }
            {
              name: 'INTAKE_PUBLISH_TIMEOUT'
              value: '3s'
            }
            {
              name: 'SQL_URL' // first-line dedup (fail-open), read/insert only
              value: 'jdbc:sqlserver://${sqlServerFqdn}:1433;database=${sqlDatabaseName};encrypt=true;authentication=ActiveDirectoryManagedIdentity;msiClientId=${identities.intake.clientId}'
            }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: intakeMinReplicas
        maxReplicas: intakeMaxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
}

resource stateWorker 'Microsoft.App/containerApps@2024-10-02-preview' = {
  name: 'ca-${baseName}-state-worker'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identities.stateWorker.id}': {}
    }
  }
  properties: {
    environmentId: managedEnvironment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      registries: [
        {
          server: registryLoginServer
          identity: identities.stateWorker.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'state-worker'
          image: image
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv(identities.stateWorker.clientId, 'state-worker', appInsightsConnectionString, serviceBusFqdn), [
            {
              name: 'SERVICEBUS_QUEUE'
              value: queueName
            }
            {
              name: 'SERVICEBUS_TOPIC'
              value: topicName
            }
            {
              name: 'SQL_URL'
              value: 'jdbc:sqlserver://${sqlServerFqdn}:1433;database=${sqlDatabaseName};encrypt=true;authentication=ActiveDirectoryManagedIdentity;msiClientId=${identities.stateWorker.clientId}'
            }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: workerMinReplicas
        maxReplicas: 5
        rules: [
          {
            name: 'queue-length'
            custom: {
              type: 'azure-servicebus'
              metadata: {
                namespace: serviceBusName
                queueName: queueName
                messageCount: '100'
              }
              identity: identities.stateWorker.id
            }
          }
        ]
      }
    }
  }
}

resource syncWorker 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'ca-${baseName}-sync-worker'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identities.syncWorker.id}': {}
    }
  }
  properties: {
    environmentId: managedEnvironment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      registries: [
        {
          server: registryLoginServer
          identity: identities.syncWorker.id
        }
      ]
      secrets: [
        {
          name: 'salesforce-client-id'
          keyVaultUrl: '${keyVaultUri}secrets/salesforce-client-id'
          identity: identities.syncWorker.id
        }
        {
          name: 'salesforce-client-secret'
          keyVaultUrl: '${keyVaultUri}secrets/salesforce-client-secret'
          identity: identities.syncWorker.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'sync-worker'
          image: image
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv(identities.syncWorker.clientId, 'sync-worker', appInsightsConnectionString, serviceBusFqdn), [
            {
              name: 'SALESFORCE_CLIENT_ID'
              secretRef: 'salesforce-client-id'
            }
            {
              name: 'SALESFORCE_CLIENT_SECRET'
              secretRef: 'salesforce-client-secret'
            }
            {
              name: 'SYNC_BATCH_SIZE'
              value: '200'
            }
            {
              name: 'SYNC_FLUSH_INTERVAL'
              value: '15s'
            }
            {
              name: 'SYNC_MAX_PARALLEL_BATCHES'
              value: '4'
            }
            {
              name: 'SQL_URL'
              value: 'jdbc:sqlserver://${sqlServerFqdn}:1433;database=${sqlDatabaseName};encrypt=true;authentication=ActiveDirectoryManagedIdentity;msiClientId=${identities.syncWorker.clientId}'
            }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: max(workerMinReplicas, 1)
        maxReplicas: 2
      }
    }
  }
}

resource opsApi 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'ca-${baseName}-ops-api'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identities.opsApi.id}': {}
    }
  }
  properties: {
    environmentId: managedEnvironment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: internalOnly
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
      }
      registries: [
        {
          server: registryLoginServer
          identity: identities.opsApi.id
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'ops-api'
          image: image
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
          env: concat(commonEnv(identities.opsApi.clientId, 'ops-api', appInsightsConnectionString, serviceBusFqdn), [
            {
              name: 'SQL_URL'
              value: 'jdbc:sqlserver://${sqlServerFqdn}:1433;database=${sqlDatabaseName};encrypt=true;authentication=ActiveDirectoryManagedIdentity;msiClientId=${identities.opsApi.clientId}'
            }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 2
      }
    }
  }
}

output intakeFqdn string = intake.properties.configuration.ingress.fqdn
output opsApiFqdn string = opsApi.properties.configuration.ingress.fqdn
