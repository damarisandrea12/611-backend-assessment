using '../main.bicep'

param environmentName = 'dev'
param serviceBusSku = 'Standard'
param enablePrivateNetworking = false
param zoneRedundant = false
param sqlMaxVCores = 1
param sqlMinVCores = '0.5'
param sqlAutoPauseDelayMinutes = 60
param intakeMinReplicas = 0
param intakeMaxReplicas = 2
param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local')
param logAnalyticsWorkspaceId = readEnvironmentVariable('LOG_ANALYTICS_WORKSPACE_ID', '')
param oncallActionGroupId = readEnvironmentVariable('ONCALL_ACTION_GROUP_ID', '')
param sqlAdminGroupObjectId = readEnvironmentVariable('SQL_ADMIN_GROUP_OBJECT_ID', '')
param sqlAdminGroupName = 'grp-payments-integration-sql-admins-dev'
