param location string
param tags object
param vnetId string
param subnetId string

@description('[{ name, resourceId, groupId, dnsZone }]')
param targets array

resource dnsZones 'Microsoft.Network/privateDnsZones@2020-06-01' = [
  for target in targets: {
    name: target.dnsZone
    location: 'global'
    tags: tags
  }
]

resource dnsLinks 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = [
  for (target, i) in targets: {
    parent: dnsZones[i]
    name: 'link-${target.name}'
    location: 'global'
    tags: tags
    properties: {
      registrationEnabled: false
      virtualNetwork: {
        id: vnetId
      }
    }
  }
]

resource endpoints 'Microsoft.Network/privateEndpoints@2023-11-01' = [
  for target in targets: {
    name: 'pe-${target.name}-${uniqueString(target.resourceId)}'
    location: location
    tags: tags
    properties: {
      subnet: {
        id: subnetId
      }
      privateLinkServiceConnections: [
        {
          name: target.name
          properties: {
            privateLinkServiceId: target.resourceId
            groupIds: [
              target.groupId
            ]
          }
        }
      ]
    }
  }
]

resource zoneGroups 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2023-11-01' = [
  for (target, i) in targets: {
    parent: endpoints[i]
    name: 'default'
    properties: {
      privateDnsZoneConfigs: [
        {
          name: target.name
          properties: {
            privateDnsZoneId: dnsZones[i].id
          }
        }
      ]
    }
  }
]
