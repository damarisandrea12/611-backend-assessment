using '../main.bicep'

param environmentName = 'prod'
param serviceBusSku = 'Premium'
param serviceBusCapacity = 1
param enablePrivateNetworking = true
param zoneRedundant = true
param sqlMaxVCores = 4
param sqlMinVCores = '1'
param sqlAutoPauseDelayMinutes = -1
param intakeMinReplicas = 2
param intakeMaxReplicas = 10
param imageTag = readEnvironmentVariable('IMAGE_TAG')
param logAnalyticsWorkspaceId = readEnvironmentVariable('LOG_ANALYTICS_WORKSPACE_ID')
param oncallActionGroupId = readEnvironmentVariable('ONCALL_ACTION_GROUP_ID')
param sqlAdminGroupObjectId = readEnvironmentVariable('SQL_ADMIN_GROUP_OBJECT_ID')
param sqlAdminGroupName = 'grp-payments-integration-sql-admins-prod'
param downstreamConsumers = [
  // Filled in when Finance and Notifications share their workload identities (object ids are not secrets).
]
