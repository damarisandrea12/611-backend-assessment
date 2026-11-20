param name string
param location string
param tags object
param logAnalyticsWorkspaceId string

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: name
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspaceId
    IngestionMode: 'LogAnalytics'
    // Telemetry is sent with Entra ID (managed identity + Monitoring Metrics Publisher), not the ikey alone.
    DisableLocalAuth: true
    RetentionInDays: 90
  }
}

output id string = appInsights.id
output name string = appInsights.name
// Not a secret when local auth is disabled: ingestion still requires an Entra token.
output connectionString string = appInsights.properties.ConnectionString
