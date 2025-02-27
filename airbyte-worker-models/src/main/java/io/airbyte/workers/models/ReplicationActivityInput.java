/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.models;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.config.ConnectionContext;
import io.airbyte.config.JobSyncConfig;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.SyncResourceRequirements;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.JobRunConfig;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A class holding the input to the Temporal replication activity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationActivityInput {

  // We include this so that we can use a feature flag to roll out the switch from sync input to
  // replication input.
  // The sync workflow has to be deterministic, so we don't want to switch based on the feature flags
  // there. Instead,
  // we pass the sync input so that replicateV2 can switch to replicate if indicated by a feature
  // flag. This reduces
  // the risk from this change. Once we're fully on replicateV2 we can remove this from the input.
  private StandardSyncInput syncInput;

  // Actor ID for the source used in the sync - this is used to update the actor configuration when
  // requested.
  private UUID sourceId;
  // Actor ID for the destination used in the sync - this is used to update the actor configuration
  // when requested.
  private UUID destinationId;
  // Source-connector specific blob. Must be a valid JSON string.
  private JsonNode sourceConfiguration;
  // Destination-connector specific blob. Must be a valid JSON string.
  private JsonNode destinationConfiguration;
  // The job info -- job id, attempt id -- for this sync.
  private JobRunConfig jobRunConfig;
  // Config related to the source launcher (rather than the source itself) e.g., whether it's a custom
  // connector.
  private IntegrationLauncherConfig sourceLauncherConfig;
  // Config related to the destination launcher (rather than the destination itself) e.g., whether it
  // supports DBT
  // transformations.
  private IntegrationLauncherConfig destinationLauncherConfig;
  // Resource requirements to use for the sync.
  private SyncResourceRequirements syncResourceRequirements;
  // The id of the workspace associated with this sync.
  private UUID workspaceId;
  // The id of the connection associated with this sync.
  private UUID connectionId;
  // Whether normalization should be run in the destination container.
  private Boolean normalizeInDestinationContainer;
  // The task queue that replication will use.
  private String taskQueue;
  // Whether this 'sync' is performing a logical reset.
  private Boolean isReset;
  // The type of namespace definition - e.g. source, destination, or custom.
  private JobSyncConfig.NamespaceDefinitionType namespaceDefinition;
  private String namespaceFormat;
  // Prefix that will be prepended to the name of each stream when it is written to the destination.
  private String prefix;
  // The results of schema refresh, including the applied diff which is used to determine which
  // streams to backfill.
  private RefreshSchemaActivityOutput schemaRefreshOutput;
  // Replication context object containing relevant IDs
  private ConnectionContext connectionContext;

}
