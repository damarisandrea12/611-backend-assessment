targetScope = 'resourceGroup'

@description('Environment name.')
@allowed([
  'dev'
  'test'
  'prod'
])
param environmentName string

@description('Azure region. One region serves the three countries (assumption A-02).')
param location string = resourceGroup().location

@description('Short workload name used in resource names.')
@maxLength(10)
param workload string = 'paynotif'

@description('Payment providers enabled in this environment. Adding a country = adding an entry (NFR9).')
param providers array = [
  {
    name: 'quetzal'
    country: 'MX'
  }
  {
    name: 'andina'
    country: 'CO'
  }
  {
    name: 'austral'
    country: 'CL'
  }
]

@description('Service Bus tier: Standard for dev/test, Premium for prod (ADR-0003).')
@allowed([
  'Standard'
  'Premium'
])
param serviceBusSku string

@description('Premium messaging units (ignored for Standard).')
@allowed([
  1
  2
  4
])
param serviceBusCapacity int = 1

@description('Private endpoints + VNet-integrated, internal-only Container Apps environment.')
param enablePrivateNetworking bool

@description('Zone redundancy for Service Bus, SQL and Container Apps (prod).')
param zoneRedundant bool

@description('Resource id of the EXISTING platform Log Analytics workspace.')
param logAnalyticsWorkspaceId string

@description('Resource id of the EXISTING platform action group that pages the Payments Integration on-call.')
param oncallActionGroupId string

@description('Object id of the Entra ID group that administers the SQL server (no SQL logins/passwords).')
param sqlAdminGroupObjectId string

@description('Display name of that Entra ID group.')
param sqlAdminGroupName string

@description('Serverless SQL max vCores.')
param sqlMaxVCores int = 2

@description('Serverless SQL min vCores (string because Bicep has no decimal literal).')
param sqlMinVCores string = '0.5'

@description('Auto-pause delay in minutes; -1 disables auto-pause (prod).')
param sqlAutoPauseDelayMinutes int = -1

@description('Minimum Intake replicas. Prod keeps 2 warm replicas for provider timeouts (NFR4).')
param intakeMinReplicas int = 1

@description('Maximum Intake replicas.')
param intakeMaxReplicas int = 10

@description('Container image tag (git SHA) deployed by the pipeline.')
param imageTag string

@description('Topic subscription consumers owned by other squads: [{ subscription: "finance", principalId: "<object id>" }].')
param downstreamConsumers array = []

@description('false = deploy/update infrastructure only and leave the apps untouched (first CD pass, before the image is pushed).')
param deployApps bool = true

param tags object = {}

var suffix = take(uniqueString(resourceGroup().id), 6)
var baseName = '${workload}-${environmentName}'
var allTags = union(tags, {
  workload: workload
  environment: environmentName
  dataClassification: 'confidential-no-pii'
})
var publicNetworkAccess = enablePrivateNetworking ? 'Disabled' : 'Enabled'

module network 'modules/network.bicep' = if (enablePrivateNetworking) {
  name: 'network'
  params: {
    name: 'vnet-${baseName}'
    location: location
    tags: allTags
  }
}

module identities 'modules/identities.bicep' = {
  name: 'identities'
  params: {
    baseName: baseName
    location: location
    tags: allTags
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    name: 'appi-${baseName}'
    location: location
    tags: allTags
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
  }
}

module serviceBus 'modules/servicebus.bicep' = {
  name: 'servicebus'
  params: {
    name: 'sb-${baseName}-${suffix}'
    location: location
    tags: allTags
    sku: serviceBusSku
    capacity: serviceBusCapacity
    zoneRedundant: zoneRedundant && serviceBusSku == 'Premium'
    // Standard does not support private endpoints; public access is then protected by Entra RBAC only.
    publicNetworkAccess: serviceBusSku == 'Premium' ? publicNetworkAccess : 'Enabled'
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault'
  params: {
    name: 'kv-${workload}-${environmentName}-${suffix}'
    location: location
    tags: allTags
    publicNetworkAccess: publicNetworkAccess
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
  }
}

module sql 'modules/sql.bicep' = {
  name: 'sql'
  params: {
    serverName: 'sql-${baseName}-${suffix}'
    location: location
    tags: allTags
    adminGroupObjectId: sqlAdminGroupObjectId
    adminGroupName: sqlAdminGroupName
    maxVCores: sqlMaxVCores
    minVCores: sqlMinVCores
    autoPauseDelayMinutes: sqlAutoPauseDelayMinutes
    zoneRedundant: zoneRedundant
    publicNetworkAccess: publicNetworkAccess
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
  }
}

module registry 'modules/registry.bicep' = {
  name: 'registry'
  params: {
    name: 'cr${workload}${environmentName}${suffix}'
    location: location
    tags: allTags
    sku: environmentName == 'prod' ? 'Standard' : 'Basic'
  }
}

module privateEndpoints 'modules/private-endpoints.bicep' = if (enablePrivateNetworking) {
  name: 'private-endpoints'
  params: {
    location: location
    tags: allTags
    vnetId: enablePrivateNetworking ? network!.outputs.vnetId : ''
    subnetId: enablePrivateNetworking ? network!.outputs.privateEndpointSubnetId : ''
    targets: [
      {
        name: 'servicebus'
        resourceId: serviceBus.outputs.namespaceId
        groupId: 'namespace'
        dnsZone: 'privatelink.servicebus.windows.net'
      }
      {
        name: 'keyvault'
        resourceId: keyVault.outputs.id
        groupId: 'vault'
        dnsZone: 'privatelink.vaultcore.azure.net'
      }
      {
        name: 'sql'
        resourceId: sql.outputs.serverId
        groupId: 'sqlServer'
        dnsZone: 'privatelink${environment().suffixes.sqlServerHostname}'
      }
    ]
  }
}

module rbac 'modules/rbac.bicep' = {
  name: 'rbac'
  params: {
    serviceBusName: serviceBus.outputs.name
    keyVaultName: keyVault.outputs.name
    registryName: registry.outputs.name
    appInsightsName: monitoring.outputs.name
    intakePrincipalId: identities.outputs.intake.principalId
    stateWorkerPrincipalId: identities.outputs.stateWorker.principalId
    syncWorkerPrincipalId: identities.outputs.syncWorker.principalId
    opsApiPrincipalId: identities.outputs.opsApi.principalId
    downstreamConsumers: downstreamConsumers
  }
}

module containerApps 'modules/containerapps.bicep' = if (deployApps) {
  name: 'containerapps'
  dependsOn: [
    rbac // identities must be able to pull images and read secrets before the first revision starts
  ]
  params: {
    baseName: baseName
    location: location
    tags: allTags
    infrastructureSubnetId: enablePrivateNetworking ? network!.outputs.containerAppsSubnetId : ''
    zoneRedundant: zoneRedundant && enablePrivateNetworking
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    registryLoginServer: registry.outputs.loginServer
    imageTag: imageTag
    keyVaultUri: keyVault.outputs.uri
    serviceBusFqdn: serviceBus.outputs.fqdn
    queueName: serviceBus.outputs.queueName
    topicName: serviceBus.outputs.topicName
    serviceBusName: serviceBus.outputs.name
    sqlServerFqdn: sql.outputs.serverFqdn
    sqlDatabaseName: sql.outputs.databaseName
    appInsightsConnectionString: monitoring.outputs.connectionString
    providers: providers
    identities: identities.outputs
    intakeMinReplicas: intakeMinReplicas
    intakeMaxReplicas: intakeMaxReplicas
    workerMinReplicas: environmentName == 'prod' ? 1 : 0
  }
}

module alerts 'modules/alerts.bicep' = {
  name: 'alerts'
  params: {
    baseName: baseName
    location: location
    tags: allTags
    actionGroupId: oncallActionGroupId
    serviceBusNamespaceId: serviceBus.outputs.namespaceId
    appInsightsId: monitoring.outputs.id
    countries: [for p in providers: p.country]
    enabled: environmentName != 'dev'
  }
}

output serviceBusNamespace string = serviceBus.outputs.fqdn
output keyVaultUri string = keyVault.outputs.uri
output sqlServerFqdn string = sql.outputs.serverFqdn
output registryLoginServer string = registry.outputs.loginServer
output intakeFqdn string = deployApps ? containerApps!.outputs.intakeFqdn : ''
output opsApiFqdn string = deployApps ? containerApps!.outputs.opsApiFqdn : ''
