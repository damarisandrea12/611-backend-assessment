param baseName string
param location string
param tags object
param actionGroupId string
param serviceBusNamespaceId string
param appInsightsId string

@description('Country codes, e.g. [ "MX", "CO", "CL" ].')
param countries array
param enabled bool

var actions = {
  actionGroups: [
    actionGroupId
  ]
}

resource deadLetters 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: 'alert-${baseName}-dlq-not-empty'
  location: 'global'
  tags: tags
  properties: {
    description: 'Messages in a dead-letter queue (intake queue or a topic subscription). Page on-call.'
    severity: 1
    enabled: enabled
    scopes: [
      serviceBusNamespaceId
    ]
    evaluationFrequency: 'PT1M'
    windowSize: 'PT5M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          name: 'dlq'
          criterionType: 'StaticThresholdCriterion'
          metricName: 'DeadletteredMessages'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          timeAggregation: 'Maximum'
          operator: 'GreaterThan'
          threshold: 0
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: [
                '*'
              ]
            }
          ]
        }
      ]
    }
    actions: [
      {
        actionGroupId: actionGroupId
      }
    ]
  }
}

resource backlog 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: 'alert-${baseName}-queue-backlog'
  location: 'global'
  tags: tags
  properties: {
    description: 'Intake queue backlog > 5,000 for 15 min: State worker not keeping up (freshness at risk).'
    severity: 2
    enabled: enabled
    scopes: [
      serviceBusNamespaceId
    ]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          name: 'active'
          criterionType: 'StaticThresholdCriterion'
          metricName: 'ActiveMessages'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          timeAggregation: 'Minimum'
          operator: 'GreaterThan'
          threshold: 5000
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: [
                'payment-notifications'
              ]
            }
          ]
        }
      ]
    }
    actions: [
      {
        actionGroupId: actionGroupId
      }
    ]
  }
}

var perCountryRules = [
  {
    key: 'freshness'
    description: 'NFR2: p95 time from provider notification to Salesforce > 5 min over 30 min.'
    severity: 2
    window: 'PT30M'
    threshold: 300
    operator: 'GreaterThan'
    query: 'customEvents | where name == "PaymentRecorded" | where tostring(customDimensions.country) == "{country}" | summarize AggregatedValue = percentile(todouble(customMeasurements.freshnessSeconds), 95)'
  }
  {
    key: 'dlq'
    description: 'Dead-lettered notifications for this country (reported by the DLQ monitor).'
    severity: 1
    window: 'PT15M'
    threshold: 0
    operator: 'GreaterThan'
    query: 'customMetrics | where name == "payments_dlq_depth" | where tostring(customDimensions.country) == "{country}" | summarize AggregatedValue = max(value)'
  }
  {
    key: 'sf-rejected'
    description: 'Payments rejected by Salesforce with a permanent error (400): data needs fixing (INC-0412 class).'
    severity: 2
    window: 'PT15M'
    threshold: 5
    operator: 'GreaterThan'
    query: 'customEvents | where name == "PaymentSyncFailed" | where tostring(customDimensions.country) == "{country}" | summarize AggregatedValue = count()'
  }
  {
    key: 'unresolved-policy'
    description: 'Authentic payments whose policy reference could not be resolved (parked for operations).'
    severity: 3
    window: 'PT1H'
    threshold: 10
    operator: 'GreaterThan'
    query: 'customMetrics | where name == "payments_unresolved_policy" | where tostring(customDimensions.country) == "{country}" | summarize AggregatedValue = sum(valueSum)'
  }
  {
    key: 'signature-failures'
    description: 'Webhooks rejected for invalid signature (secret rotation, provider change, or attack).'
    severity: 3
    window: 'PT15M'
    threshold: 20
    operator: 'GreaterThan'
    query: 'customMetrics | where name == "payments_intake_rejected" | where tostring(customDimensions.country) == "{country}" and tostring(customDimensions.reason) == "invalid_signature" | summarize AggregatedValue = sum(valueSum)'
  }
  {
    key: 'no-traffic'
    description: 'No notification accepted for this country in 60 min: provider outage, routing or signature problem. May fire on quiet nights; tune per country before prod.'
    severity: 2
    window: 'PT1H'
    threshold: 1
    operator: 'LessThan'
    query: 'customMetrics | where name == "payments_intake_accepted" | where tostring(customDimensions.country) == "{country}" | summarize AggregatedValue = sum(valueSum) | union (print AggregatedValue = 0.0) | summarize AggregatedValue = sum(AggregatedValue)'
  }
]

var countryRules = flatten(map(countries, country => map(perCountryRules, rule => union(rule, {
  country: country
}))))

resource logAlerts 'Microsoft.Insights/scheduledQueryRules@2023-03-15-preview' = [
  for rule in countryRules: {
    name: 'alert-${baseName}-${toLower(rule.country)}-${rule.key}'
    location: location
    tags: tags
    kind: 'LogAlert'
    properties: {
      displayName: '[${rule.country}] ${rule.key}'
      description: rule.description
      severity: rule.severity
      enabled: enabled
      scopes: [
        appInsightsId
      ]
      evaluationFrequency: 'PT5M'
      windowSize: rule.window
      criteria: {
        allOf: [
          {
            query: replace(rule.query, '{country}', rule.country)
            timeAggregation: 'Maximum'
            metricMeasureColumn: 'AggregatedValue'
            operator: rule.operator
            threshold: rule.threshold
            failingPeriods: {
              numberOfEvaluationPeriods: 1
              minFailingPeriodsToAlert: 1
            }
          }
        ]
      }
      autoMitigate: true
      actions: actions
    }
  }
]

resource apiBudget 'Microsoft.Insights/scheduledQueryRules@2023-03-15-preview' = {
  name: 'alert-${baseName}-salesforce-api-budget'
  location: location
  tags: tags
  kind: 'LogAlert'
  properties: {
    displayName: 'Salesforce API budget > 50% of 150,000 / 24 h'
    description: 'Rolling 24 h API calls of this integration above 50% (normal is about 4%). Investigate; the budget guard slows the sync.'
    severity: 2
    enabled: enabled
    scopes: [
      appInsightsId
    ]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "payments_sf_api_budget_used_ratio" | summarize AggregatedValue = max(value)'
          timeAggregation: 'Maximum'
          metricMeasureColumn: 'AggregatedValue'
          operator: 'GreaterThan'
          threshold: json('0.5')
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: actions
  }
}

resource apiBudgetCritical 'Microsoft.Insights/scheduledQueryRules@2023-03-15-preview' = {
  name: 'alert-${baseName}-salesforce-api-budget-critical'
  location: location
  tags: tags
  kind: 'LogAlert'
  properties: {
    displayName: 'Salesforce API budget > 80% of 150,000 / 24 h'
    description: 'Page on-call and inform the Salesforce team: the org-wide limit is shared with the contact center and partners.'
    severity: 1
    enabled: enabled
    scopes: [
      appInsightsId
    ]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      allOf: [
        {
          query: 'customMetrics | where name == "payments_sf_api_budget_used_ratio" | summarize AggregatedValue = max(value)'
          timeAggregation: 'Maximum'
          metricMeasureColumn: 'AggregatedValue'
          operator: 'GreaterThan'
          threshold: json('0.8')
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    autoMitigate: true
    actions: actions
  }
}
