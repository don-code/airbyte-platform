/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.data.services.impls.jooq;

import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTIVE_DECLARATIVE_MANIFEST;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_CATALOG;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_CATALOG_FETCH_EVENT;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_DEFINITION;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_DEFINITION_BREAKING_CHANGE;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_DEFINITION_CONFIG_INJECTION;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_DEFINITION_VERSION;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ACTOR_OAUTH_PARAMETER;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.CONNECTION;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.CONNECTOR_BUILDER_PROJECT;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.DECLARATIVE_MANIFEST;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.ORGANIZATION;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.SCHEMA_MANAGEMENT;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.WORKSPACE;
import static io.airbyte.db.instance.configs.jooq.generated.Tables.WORKSPACE_SERVICE_ACCOUNT;

import io.airbyte.commons.enums.Enums;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.version.AirbyteProtocolVersion;
import io.airbyte.commons.version.Version;
import io.airbyte.config.ActorCatalog;
import io.airbyte.config.ActorCatalogFetchEvent;
import io.airbyte.config.ActorCatalogWithUpdatedAt;
import io.airbyte.config.ActorDefinitionBreakingChange;
import io.airbyte.config.ActorDefinitionConfigInjection;
import io.airbyte.config.ActorDefinitionResourceRequirements;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.ActorDefinitionVersion.SupportState;
import io.airbyte.config.AllowedHosts;
import io.airbyte.config.ConnectorBuilderProject;
import io.airbyte.config.DeclarativeManifest;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.DestinationOAuthParameter;
import io.airbyte.config.FieldSelectionData;
import io.airbyte.config.Geography;
import io.airbyte.config.JobSyncConfig.NamespaceDefinitionType;
import io.airbyte.config.NormalizationDestinationDefinitionConfig;
import io.airbyte.config.Notification;
import io.airbyte.config.NotificationSettings;
import io.airbyte.config.Organization;
import io.airbyte.config.ReleaseStage;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.Schedule;
import io.airbyte.config.ScheduleData;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.SourceOAuthParameter;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSourceDefinition.SourceType;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSync.NonBreakingChangesPreference;
import io.airbyte.config.StandardSync.ScheduleType;
import io.airbyte.config.StandardSync.Status;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.SuggestedStreams;
import io.airbyte.config.SupportLevel;
import io.airbyte.config.WorkspaceServiceAccount;
import io.airbyte.db.instance.configs.jooq.generated.enums.AutoPropagationStatus;
import io.airbyte.db.instance.configs.jooq.generated.enums.NotificationType;
import io.airbyte.db.instance.configs.jooq.generated.tables.records.NotificationConfigurationRecord;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConnectorSpecification;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Record;

/**
 * Provides static methods for converting from repository layer results (often in the form of a jooq
 * {@link Record}) to config models.
 */
public class DbConverter {

  /**
   * Build connection (a.k.a. StandardSync) from db record.
   *
   * @param record db record.
   * @param connectionOperationId connection operation id.
   * @return connection (a.k.a. StandardSync)
   */
  public static StandardSync buildStandardSync(final Record record,
                                               final List<UUID> connectionOperationId,
                                               final List<NotificationConfigurationRecord> notificationConfigurations) {
    final boolean isWebhookNotificationEnabled = notificationConfigurations.stream()
        .filter(notificationConfiguration -> notificationConfiguration
            .getNotificationType() == NotificationType.webhook && notificationConfiguration.getEnabled())
        .findAny().isPresent();

    final boolean isEmailNotificationEnabled = notificationConfigurations.stream()
        .filter(notificationConfiguration -> notificationConfiguration
            .getNotificationType() == NotificationType.email && notificationConfiguration.getEnabled())
        .findAny().isPresent();

    return new StandardSync()
        .withConnectionId(record.get(CONNECTION.ID))
        .withNamespaceDefinition(
            Enums.toEnum(record.get(CONNECTION.NAMESPACE_DEFINITION, String.class), NamespaceDefinitionType.class)
                .orElseThrow())
        .withNamespaceFormat(record.get(CONNECTION.NAMESPACE_FORMAT))
        .withPrefix(record.get(CONNECTION.PREFIX))
        .withSourceId(record.get(CONNECTION.SOURCE_ID))
        .withDestinationId(record.get(CONNECTION.DESTINATION_ID))
        .withName(record.get(CONNECTION.NAME))
        .withCatalog(parseConfiguredAirbyteCatalog(record.get(CONNECTION.CATALOG).data()))
        .withFieldSelectionData(record.get(CONNECTION.FIELD_SELECTION_DATA) == null ? null
            : Jsons.deserialize(record.get(CONNECTION.FIELD_SELECTION_DATA).data(), FieldSelectionData.class))
        .withStatus(
            record.get(CONNECTION.STATUS) == null ? null
                : Enums.toEnum(record.get(CONNECTION.STATUS, String.class), Status.class).orElseThrow())
        .withSchedule(Jsons.deserialize(record.get(CONNECTION.SCHEDULE).data(), Schedule.class))
        .withManual(record.get(CONNECTION.MANUAL))
        .withScheduleType(record.get(CONNECTION.SCHEDULE_TYPE) == null ? null
            : Enums.toEnum(record.get(CONNECTION.SCHEDULE_TYPE, String.class), ScheduleType.class).orElseThrow())
        .withScheduleData(
            record.get(CONNECTION.SCHEDULE_DATA) == null ? null
                : Jsons.deserialize(record.get(CONNECTION.SCHEDULE_DATA).data(), ScheduleData.class))
        .withOperationIds(connectionOperationId)
        .withResourceRequirements(
            Jsons.deserialize(record.get(CONNECTION.RESOURCE_REQUIREMENTS).data(), ResourceRequirements.class))
        .withSourceCatalogId(record.get(CONNECTION.SOURCE_CATALOG_ID))
        .withBreakingChange(record.get(CONNECTION.BREAKING_CHANGE))
        .withGeography(Enums.toEnum(record.get(CONNECTION.GEOGRAPHY, String.class), Geography.class).orElseThrow())
        .withNonBreakingChangesPreference(
            Enums.toEnum(Optional.ofNullable(record.get(SCHEMA_MANAGEMENT.AUTO_PROPAGATION_STATUS)).orElse(AutoPropagationStatus.ignore)
                .getLiteral(), NonBreakingChangesPreference.class).orElseThrow())
        .withNotifySchemaChanges(isWebhookNotificationEnabled)
        .withNotifySchemaChangesByEmail(isEmailNotificationEnabled);
  }

  private static ConfiguredAirbyteCatalog parseConfiguredAirbyteCatalog(final String configuredAirbyteCatalogString) {
    final ConfiguredAirbyteCatalog configuredAirbyteCatalog = Jsons.deserialize(configuredAirbyteCatalogString, ConfiguredAirbyteCatalog.class);
    // On-the-fly migration of persisted data types related objects (protocol v0->v1)
    // TODO feature flag this for data types rollout
    // CatalogMigrationV1Helper.upgradeSchemaIfNeeded(configuredAirbyteCatalog);
    CatalogMigrationV1Helper.downgradeSchemaIfNeeded(configuredAirbyteCatalog);
    return configuredAirbyteCatalog;
  }

  /**
   * Build workspace from db record.
   *
   * @param record db record
   * @return workspace
   */
  public static StandardWorkspace buildStandardWorkspace(final Record record) {
    final List<Notification> notificationList = new ArrayList<>();
    final List fetchedNotifications = Jsons.deserialize(record.get(WORKSPACE.NOTIFICATIONS).data(), List.class);
    for (final Object notification : fetchedNotifications) {
      notificationList.add(Jsons.convertValue(notification, Notification.class));
    }
    return new StandardWorkspace()
        .withWorkspaceId(record.get(WORKSPACE.ID))
        .withName(record.get(WORKSPACE.NAME))
        .withSlug(record.get(WORKSPACE.SLUG))
        .withInitialSetupComplete(record.get(WORKSPACE.INITIAL_SETUP_COMPLETE))
        .withCustomerId(record.get(WORKSPACE.CUSTOMER_ID))
        .withEmail(record.get(WORKSPACE.EMAIL))
        .withAnonymousDataCollection(record.get(WORKSPACE.ANONYMOUS_DATA_COLLECTION))
        .withNews(record.get(WORKSPACE.SEND_NEWSLETTER))
        .withSecurityUpdates(record.get(WORKSPACE.SEND_SECURITY_UPDATES))
        .withDisplaySetupWizard(record.get(WORKSPACE.DISPLAY_SETUP_WIZARD))
        .withTombstone(record.get(WORKSPACE.TOMBSTONE))
        .withNotifications(notificationList)
        .withNotificationSettings(record.get(WORKSPACE.NOTIFICATION_SETTINGS) == null ? null
            : Jsons.deserialize(record.get(WORKSPACE.NOTIFICATION_SETTINGS).data(), NotificationSettings.class))
        .withFirstCompletedSync(record.get(WORKSPACE.FIRST_SYNC_COMPLETE))
        .withFeedbackDone(record.get(WORKSPACE.FEEDBACK_COMPLETE))
        .withDefaultGeography(
            Enums.toEnum(record.get(WORKSPACE.GEOGRAPHY, String.class), Geography.class).orElseThrow())
        .withWebhookOperationConfigs(record.get(WORKSPACE.WEBHOOK_OPERATION_CONFIGS) == null ? null
            : Jsons.deserialize(record.get(WORKSPACE.WEBHOOK_OPERATION_CONFIGS).data()))
        .withOrganizationId(record.get(WORKSPACE.ORGANIZATION_ID));
  }

  /**
   * Build organization from db record.
   *
   * @param record db record
   * @return organization
   */
  public static Organization buildOrganization(final Record record) {
    return new Organization()
        .withOrganizationId(record.get(ORGANIZATION.ID))
        .withName(record.get(ORGANIZATION.NAME))
        .withUserId(record.get(ORGANIZATION.USER_ID))
        .withEmail(record.get(ORGANIZATION.EMAIL))
        .withPba(record.get(ORGANIZATION.PBA))
        .withOrgLevelBilling(record.get(ORGANIZATION.ORG_LEVEL_BILLING));
  }

  /**
   * Build source from db record.
   *
   * @param record db record
   * @return source
   */
  public static SourceConnection buildSourceConnection(final Record record) {
    return new SourceConnection()
        .withSourceId(record.get(ACTOR.ID))
        .withConfiguration(Jsons.deserialize(record.get(ACTOR.CONFIGURATION).data()))
        .withWorkspaceId(record.get(ACTOR.WORKSPACE_ID))
        .withDefaultVersionId(record.get(ACTOR.DEFAULT_VERSION_ID))
        .withSourceDefinitionId(record.get(ACTOR.ACTOR_DEFINITION_ID))
        .withTombstone(record.get(ACTOR.TOMBSTONE))
        .withName(record.get(ACTOR.NAME));
  }

  /**
   * Build destination from db record.
   *
   * @param record db record
   * @return destination
   */
  public static DestinationConnection buildDestinationConnection(final Record record) {
    return new DestinationConnection()
        .withDestinationId(record.get(ACTOR.ID))
        .withConfiguration(Jsons.deserialize(record.get(ACTOR.CONFIGURATION).data()))
        .withWorkspaceId(record.get(ACTOR.WORKSPACE_ID))
        .withDefaultVersionId(record.get(ACTOR.DEFAULT_VERSION_ID))
        .withDestinationDefinitionId(record.get(ACTOR.ACTOR_DEFINITION_ID))
        .withTombstone(record.get(ACTOR.TOMBSTONE))
        .withName(record.get(ACTOR.NAME));
  }

  /**
   * Build source definition from db record.
   *
   * @param record db record
   * @return source definition
   */
  public static StandardSourceDefinition buildStandardSourceDefinition(final Record record, final long defaultMaxSecondsBetweenMessages) {
    return new StandardSourceDefinition()
        .withSourceDefinitionId(record.get(ACTOR_DEFINITION.ID))
        .withDefaultVersionId(record.get(ACTOR_DEFINITION.DEFAULT_VERSION_ID))
        .withIcon(record.get(ACTOR_DEFINITION.ICON))
        .withName(record.get(ACTOR_DEFINITION.NAME))
        .withSourceType(record.get(ACTOR_DEFINITION.SOURCE_TYPE) == null ? null
            : Enums.toEnum(record.get(ACTOR_DEFINITION.SOURCE_TYPE, String.class), SourceType.class).orElseThrow())
        .withTombstone(record.get(ACTOR_DEFINITION.TOMBSTONE))
        .withPublic(record.get(ACTOR_DEFINITION.PUBLIC))
        .withCustom(record.get(ACTOR_DEFINITION.CUSTOM))
        .withResourceRequirements(record.get(ACTOR_DEFINITION.RESOURCE_REQUIREMENTS) == null
            ? null
            : Jsons.deserialize(record.get(ACTOR_DEFINITION.RESOURCE_REQUIREMENTS).data(), ActorDefinitionResourceRequirements.class))
        .withMaxSecondsBetweenMessages(record.get(ACTOR_DEFINITION.MAX_SECONDS_BETWEEN_MESSAGES) == null
            ? defaultMaxSecondsBetweenMessages
            : record.get(ACTOR_DEFINITION.MAX_SECONDS_BETWEEN_MESSAGES).longValue());
  }

  /**
   * Build destination definition from db record.
   *
   * @param record db record
   * @return destination definition
   */
  public static StandardDestinationDefinition buildStandardDestinationDefinition(final Record record) {
    return new StandardDestinationDefinition()
        .withDestinationDefinitionId(record.get(ACTOR_DEFINITION.ID))
        .withDefaultVersionId(record.get(ACTOR_DEFINITION.DEFAULT_VERSION_ID))
        .withIcon(record.get(ACTOR_DEFINITION.ICON))
        .withName(record.get(ACTOR_DEFINITION.NAME))
        .withTombstone(record.get(ACTOR_DEFINITION.TOMBSTONE))
        .withPublic(record.get(ACTOR_DEFINITION.PUBLIC))
        .withCustom(record.get(ACTOR_DEFINITION.CUSTOM))
        .withResourceRequirements(record.get(ACTOR_DEFINITION.RESOURCE_REQUIREMENTS) == null
            ? null
            : Jsons.deserialize(record.get(ACTOR_DEFINITION.RESOURCE_REQUIREMENTS).data(), ActorDefinitionResourceRequirements.class));
  }

  /**
   * Build destination oauth parameters from db record.
   *
   * @param record db record
   * @return destination oauth parameter
   */
  public static DestinationOAuthParameter buildDestinationOAuthParameter(final Record record) {
    return new DestinationOAuthParameter()
        .withOauthParameterId(record.get(ACTOR_OAUTH_PARAMETER.ID))
        .withConfiguration(Jsons.deserialize(record.get(ACTOR_OAUTH_PARAMETER.CONFIGURATION).data()))
        .withWorkspaceId(record.get(ACTOR_OAUTH_PARAMETER.WORKSPACE_ID))
        .withDestinationDefinitionId(record.get(ACTOR_OAUTH_PARAMETER.ACTOR_DEFINITION_ID));
  }

  /**
   * Build source oauth parameters from db record.
   *
   * @param record db record
   * @return source oauth parameters
   */
  public static SourceOAuthParameter buildSourceOAuthParameter(final Record record) {
    return new SourceOAuthParameter()
        .withOauthParameterId(record.get(ACTOR_OAUTH_PARAMETER.ID))
        .withConfiguration(Jsons.deserialize(record.get(ACTOR_OAUTH_PARAMETER.CONFIGURATION).data()))
        .withWorkspaceId(record.get(ACTOR_OAUTH_PARAMETER.WORKSPACE_ID))
        .withSourceDefinitionId(record.get(ACTOR_OAUTH_PARAMETER.ACTOR_DEFINITION_ID));
  }

  /**
   * Build actor catalog from db record.
   *
   * @param record db record
   * @return actor catalog
   */
  public static ActorCatalog buildActorCatalog(final Record record) {
    return new ActorCatalog()
        .withId(record.get(ACTOR_CATALOG.ID))
        .withCatalog(Jsons.jsonNode(parseAirbyteCatalog(record.get(ACTOR_CATALOG.CATALOG).toString())))
        .withCatalogHash(record.get(ACTOR_CATALOG.CATALOG_HASH));
  }

  /**
   * Build actor catalog with updated at from db record.
   *
   * @param record db record
   * @return actor catalog with last updated at
   */
  public static ActorCatalogWithUpdatedAt buildActorCatalogWithUpdatedAt(final Record record) {
    return new ActorCatalogWithUpdatedAt()
        .withId(record.get(ACTOR_CATALOG.ID))
        .withCatalog(Jsons.jsonNode(parseAirbyteCatalog(record.get(ACTOR_CATALOG.CATALOG).toString())))
        .withCatalogHash(record.get(ACTOR_CATALOG.CATALOG_HASH))
        .withUpdatedAt(record.get(ACTOR_CATALOG_FETCH_EVENT.CREATED_AT, LocalDateTime.class).toEpochSecond(ZoneOffset.UTC));
  }

  /**
   * Parse airbyte catalog from JSON string.
   *
   * @param airbyteCatalogString catalog as JSON string
   * @return airbyte catalog
   */
  public static AirbyteCatalog parseAirbyteCatalog(final String airbyteCatalogString) {
    final AirbyteCatalog airbyteCatalog = Jsons.deserialize(airbyteCatalogString, AirbyteCatalog.class);
    // On-the-fly migration of persisted data types related objects (protocol v0->v1)
    // TODO feature flag this for data types rollout
    // CatalogMigrationV1Helper.upgradeSchemaIfNeeded(airbyteCatalog);
    CatalogMigrationV1Helper.downgradeSchemaIfNeeded(airbyteCatalog);
    return airbyteCatalog;
  }

  /**
   * Build actor catalog fetch event from db record.
   *
   * @param record db record
   * @return actor catalog fetch event
   */
  public static ActorCatalogFetchEvent buildActorCatalogFetchEvent(final Record record) {
    return new ActorCatalogFetchEvent()
        .withActorId(record.get(ACTOR_CATALOG_FETCH_EVENT.ACTOR_ID))
        .withActorCatalogId(record.get(ACTOR_CATALOG_FETCH_EVENT.ACTOR_CATALOG_ID))
        .withCreatedAt(record.get(ACTOR_CATALOG_FETCH_EVENT.CREATED_AT, LocalDateTime.class).toEpochSecond(ZoneOffset.UTC));
  }

  /**
   * Build workspace service account from db record.
   *
   * @param record db record
   * @return workspace service account
   */
  public static WorkspaceServiceAccount buildWorkspaceServiceAccount(final Record record) {
    return new WorkspaceServiceAccount()
        .withWorkspaceId(record.get(WORKSPACE_SERVICE_ACCOUNT.WORKSPACE_ID))
        .withServiceAccountId(record.get(WORKSPACE_SERVICE_ACCOUNT.SERVICE_ACCOUNT_ID))
        .withServiceAccountEmail(record.get(WORKSPACE_SERVICE_ACCOUNT.SERVICE_ACCOUNT_EMAIL))
        .withJsonCredential(record.get(WORKSPACE_SERVICE_ACCOUNT.JSON_CREDENTIAL) == null ? null
            : Jsons.deserialize(record.get(WORKSPACE_SERVICE_ACCOUNT.JSON_CREDENTIAL).data()))
        .withHmacKey(record.get(WORKSPACE_SERVICE_ACCOUNT.HMAC_KEY) == null ? null
            : Jsons.deserialize(record.get(WORKSPACE_SERVICE_ACCOUNT.HMAC_KEY).data()));
  }

  /**
   * Builder connector builder with manifest project from db record.
   *
   * @param record db record
   * @return connector builder project
   */
  public static ConnectorBuilderProject buildConnectorBuilderProject(final Record record) {
    return buildConnectorBuilderProjectWithoutManifestDraft(record)
        .withManifestDraft(record.get(CONNECTOR_BUILDER_PROJECT.MANIFEST_DRAFT) == null ? null
            : Jsons.deserialize(record.get(CONNECTOR_BUILDER_PROJECT.MANIFEST_DRAFT).data()));
  }

  /**
   * Builder connector builder without manifest project from db record.
   *
   * @param record db record
   * @return connector builder project
   */
  public static ConnectorBuilderProject buildConnectorBuilderProjectWithoutManifestDraft(final Record record) {
    return new ConnectorBuilderProject()
        .withWorkspaceId(record.get(CONNECTOR_BUILDER_PROJECT.WORKSPACE_ID))
        .withBuilderProjectId(record.get(CONNECTOR_BUILDER_PROJECT.ID))
        .withName(record.get(CONNECTOR_BUILDER_PROJECT.NAME))
        .withHasDraft((Boolean) record.get("hasDraft"))
        .withTombstone(record.get(CONNECTOR_BUILDER_PROJECT.TOMBSTONE))
        .withActorDefinitionId(record.get(CONNECTOR_BUILDER_PROJECT.ACTOR_DEFINITION_ID))
        .withActiveDeclarativeManifestVersion(record.get(ACTIVE_DECLARATIVE_MANIFEST.VERSION));
  }

  /**
   * Builder declarative manifest from db record.
   *
   * @param record db record
   * @return declarative manifest
   */
  public static DeclarativeManifest buildDeclarativeManifest(final Record record) {
    return buildDeclarativeManifestWithoutManifestAndSpec(record).withManifest(Jsons.deserialize(record.get(DECLARATIVE_MANIFEST.MANIFEST).data()))
        .withSpec(Jsons.deserialize(record.get(DECLARATIVE_MANIFEST.SPEC).data()));
  }

  /**
   * Builder declarative manifest without manifest from db record.
   *
   * @param record db record
   * @return declarative manifest
   */
  public static DeclarativeManifest buildDeclarativeManifestWithoutManifestAndSpec(final Record record) {
    return new DeclarativeManifest()
        .withActorDefinitionId(record.get(DECLARATIVE_MANIFEST.ACTOR_DEFINITION_ID))
        .withDescription(record.get(DECLARATIVE_MANIFEST.DESCRIPTION))
        .withVersion(record.get(DECLARATIVE_MANIFEST.VERSION));
  }

  /**
   * Actor definition config injection from db record.
   *
   * @param record db record
   * @return actor definition config injection
   */
  public static ActorDefinitionConfigInjection buildActorDefinitionConfigInjection(final Record record) {
    return new ActorDefinitionConfigInjection()
        .withActorDefinitionId(record.get(ACTOR_DEFINITION_CONFIG_INJECTION.ACTOR_DEFINITION_ID))
        .withInjectionPath(record.get(ACTOR_DEFINITION_CONFIG_INJECTION.INJECTION_PATH))
        .withJsonToInject(Jsons.deserialize(record.get(ACTOR_DEFINITION_CONFIG_INJECTION.JSON_TO_INJECT).data()));
  }

  /**
   * Actor definition breaking change from db record.
   *
   * @param record db record
   * @return actor definition breaking change
   */
  public static ActorDefinitionBreakingChange buildActorDefinitionBreakingChange(final Record record) {
    return new ActorDefinitionBreakingChange()
        .withActorDefinitionId(record.get(ACTOR_DEFINITION_BREAKING_CHANGE.ACTOR_DEFINITION_ID))
        .withVersion(new Version(record.get(ACTOR_DEFINITION_BREAKING_CHANGE.VERSION)))
        .withMessage(record.get(ACTOR_DEFINITION_BREAKING_CHANGE.MESSAGE))
        .withUpgradeDeadline(record.get(ACTOR_DEFINITION_BREAKING_CHANGE.UPGRADE_DEADLINE).toString())
        .withMigrationDocumentationUrl(record.get(ACTOR_DEFINITION_BREAKING_CHANGE.MIGRATION_DOCUMENTATION_URL));
  }

  /**
   * Actor definition version from a db record.
   *
   * @param record db record
   * @return actor definition version
   */
  public static ActorDefinitionVersion buildActorDefinitionVersion(final Record record) {
    return new ActorDefinitionVersion()
        .withVersionId(record.get(ACTOR_DEFINITION_VERSION.ID))
        .withActorDefinitionId(record.get(ACTOR_DEFINITION_VERSION.ACTOR_DEFINITION_ID))
        .withDockerRepository(record.get(ACTOR_DEFINITION_VERSION.DOCKER_REPOSITORY))
        .withDockerImageTag(record.get(ACTOR_DEFINITION_VERSION.DOCKER_IMAGE_TAG))
        .withSpec(Jsons.deserialize(record.get(ACTOR_DEFINITION_VERSION.SPEC).data(), ConnectorSpecification.class))
        .withDocumentationUrl(record.get(ACTOR_DEFINITION_VERSION.DOCUMENTATION_URL))
        .withSupportLevel(record.get(ACTOR_DEFINITION_VERSION.SUPPORT_LEVEL) == null ? null
            : Enums.toEnum(record.get(ACTOR_DEFINITION_VERSION.SUPPORT_LEVEL, String.class), SupportLevel.class).orElseThrow())
        .withProtocolVersion(AirbyteProtocolVersion.getWithDefault(record.get(ACTOR_DEFINITION_VERSION.PROTOCOL_VERSION)).serialize())
        .withReleaseStage(record.get(ACTOR_DEFINITION_VERSION.RELEASE_STAGE) == null ? null
            : Enums.toEnum(record.get(ACTOR_DEFINITION_VERSION.RELEASE_STAGE, String.class), ReleaseStage.class).orElseThrow())
        .withReleaseDate(record.get(ACTOR_DEFINITION_VERSION.RELEASE_DATE) == null ? null
            : record.get(ACTOR_DEFINITION_VERSION.RELEASE_DATE).toString())
        .withAllowedHosts(record.get(ACTOR_DEFINITION_VERSION.ALLOWED_HOSTS) == null
            ? null
            : Jsons.deserialize(record.get(ACTOR_DEFINITION_VERSION.ALLOWED_HOSTS).data(), AllowedHosts.class))
        .withSuggestedStreams(record.get(ACTOR_DEFINITION_VERSION.SUGGESTED_STREAMS) == null
            ? null
            : Jsons.deserialize(record.get(ACTOR_DEFINITION_VERSION.SUGGESTED_STREAMS).data(),
                SuggestedStreams.class))
        .withSupportsDbt(record.get(ACTOR_DEFINITION_VERSION.SUPPORTS_DBT))
        .withNormalizationConfig(
            Objects.nonNull(record.get(ACTOR_DEFINITION_VERSION.NORMALIZATION_REPOSITORY))
                && Objects.nonNull(record.get(ACTOR_DEFINITION_VERSION.NORMALIZATION_TAG))
                && Objects.nonNull(record.get(ACTOR_DEFINITION_VERSION.NORMALIZATION_INTEGRATION_TYPE))
                    ? new NormalizationDestinationDefinitionConfig()
                        .withNormalizationRepository(record.get(ACTOR_DEFINITION_VERSION.NORMALIZATION_REPOSITORY))
                        .withNormalizationTag(record.get(ACTOR_DEFINITION_VERSION.NORMALIZATION_TAG))
                        .withNormalizationIntegrationType(record.get(ACTOR_DEFINITION_VERSION.NORMALIZATION_INTEGRATION_TYPE))
                    : null)
        .withSupportState(Enums.toEnum(record.get(ACTOR_DEFINITION_VERSION.SUPPORT_STATE, String.class), SupportState.class).orElseThrow());
  }

}
