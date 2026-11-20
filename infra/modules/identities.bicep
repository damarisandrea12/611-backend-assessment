param baseName string
param location string
param tags object

resource intake 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${baseName}-intake'
  location: location
  tags: tags
}

resource stateWorker 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${baseName}-state-worker'
  location: location
  tags: tags
}

resource syncWorker 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${baseName}-sync-worker'
  location: location
  tags: tags
}

resource opsApi 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${baseName}-ops-api'
  location: location
  tags: tags
}

output intake object = {
  id: intake.id
  name: intake.name
  principalId: intake.properties.principalId
  clientId: intake.properties.clientId
}
output stateWorker object = {
  id: stateWorker.id
  name: stateWorker.name
  principalId: stateWorker.properties.principalId
  clientId: stateWorker.properties.clientId
}
output syncWorker object = {
  id: syncWorker.id
  name: syncWorker.name
  principalId: syncWorker.properties.principalId
  clientId: syncWorker.properties.clientId
}
output opsApi object = {
  id: opsApi.id
  name: opsApi.name
  principalId: opsApi.properties.principalId
  clientId: opsApi.properties.clientId
}
