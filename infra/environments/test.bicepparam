using '../main.bicep'

param environmentName = 'test'
param serviceBusSku = 'Standard'
param enablePrivateNetworking = true
param zoneRedundant = false
param sqlMaxVCores = 2
param sqlMinVCores = '0.5'
param sqlAutoPauseDelayMinutes = 60
param intakeMinReplicas = 1
param intakeMaxReplicas = 4
param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local')
param logAnalyticsWorkspaceId = readEnvironmentVariable('LOG_ANALYTICS_WORKSPACE_ID', '')
param oncallActionGroupId = readEnvironmentVariable('ONCALL_ACTION_GROUP_ID', '')
param sqlAdminGroupObjectId = readEnvironmentVariable('SQL_ADMIN_GROUP_OBJECT_ID', '')
param sqlAdminGroupName = 'grp-payments-integration-sql-admins-test'
