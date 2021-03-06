package org.rakam.presto;

import com.facebook.presto.sql.tree.QualifiedName;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Binder;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Names;
import org.rakam.analysis.ApiKeyService;
import org.rakam.analysis.ConfigManager;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.EscapeIdentifier;
import org.rakam.analysis.EventExplorer;
import org.rakam.analysis.FunnelQueryExecutor;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.MaterializedViewService;
import org.rakam.analysis.RealtimeService;
import org.rakam.analysis.RealtimeService.RealtimeAggregations;
import org.rakam.analysis.RetentionQueryExecutor;
import org.rakam.analysis.TimestampToEpochFunction;
import org.rakam.analysis.metadata.JDBCQueryMetadata;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.analysis.metadata.QueryMetadataStore;
import org.rakam.aws.kinesis.ForStreamer;
import org.rakam.config.JDBCConfig;
import org.rakam.config.MetadataConfig;
import org.rakam.plugin.CopyEvent;
import org.rakam.plugin.EventMapper;
import org.rakam.plugin.RakamModule;
import org.rakam.plugin.SystemEvents;
import org.rakam.plugin.SystemEvents.ProjectCreatedEvent;
import org.rakam.plugin.TimestampEventMapper;
import org.rakam.plugin.stream.EventStream;
import org.rakam.plugin.stream.EventStreamConfig;
import org.rakam.plugin.user.AbstractUserService;
import org.rakam.plugin.user.UserPluginConfig;
import org.rakam.postgresql.analysis.JDBCApiKeyService;
import org.rakam.postgresql.plugin.user.AbstractPostgresqlUserStorage;
import org.rakam.presto.analysis.MysqlConfigManager;
import org.rakam.presto.analysis.PrestoConfig;
import org.rakam.presto.analysis.PrestoContinuousQueryService;
import org.rakam.presto.analysis.PrestoEventExplorer;
import org.rakam.presto.analysis.PrestoEventStream;
import org.rakam.presto.analysis.PrestoFunnelQueryExecutor;
import org.rakam.presto.analysis.PrestoMaterializedViewService;
import org.rakam.presto.analysis.PrestoMetastore;
import org.rakam.presto.analysis.PrestoQueryExecutor;
import org.rakam.presto.analysis.PrestoRetentionQueryExecutor;
import org.rakam.presto.analysis.PrestoUserService;
import org.rakam.presto.collection.PrestoCopyEvent;
import org.rakam.presto.plugin.EventExplorerListener;
import org.rakam.presto.plugin.user.PrestoExternalUserStorageAdapter;
import org.rakam.report.QueryExecutor;
import org.rakam.report.eventexplorer.EventExplorerConfig;
import org.rakam.report.realtime.AggregationType;
import org.rakam.report.realtime.RealTimeConfig;
import org.rakam.util.ConditionalModule;
import org.rakam.util.RakamException;

import javax.inject.Inject;

import java.util.List;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static org.rakam.presto.analysis.PrestoUserService.ANONYMOUS_ID_MAPPING;

@AutoService(RakamModule.class)
@ConditionalModule(config = "store.adapter", value = "presto")
public class PrestoModule extends RakamModule {
    @Override
    protected void setup(Binder binder) {
        configBinder(binder).bindConfig(MetadataConfig.class);
        configBinder(binder).bindConfig(PrestoConfig.class);

        binder.bind(QueryExecutor.class).to(PrestoQueryExecutor.class);
        binder.bind(char.class).annotatedWith(EscapeIdentifier.class).toInstance('"');
        binder.bind(ContinuousQueryService.class).to(PrestoContinuousQueryService.class);
        binder.bind(MaterializedViewService.class).to(PrestoMaterializedViewService.class);
        binder.bind(String.class).annotatedWith(TimestampToEpochFunction.class).toInstance("to_unixtime");

        if (buildConfigObject(EventStreamConfig.class).isEventStreamEnabled()) {
            httpClientBinder(binder).bindHttpClient("streamer", ForStreamer.class);
            binder.bind(EventStream.class).to(PrestoEventStream.class).in(Scopes.SINGLETON);
        }

        OptionalBinder.newOptionalBinder(binder, CopyEvent.class)
                .setBinding().to(PrestoCopyEvent.class);
        JDBCPoolDataSource metadataDataSource = bindJDBCConfig(binder, "presto.metastore.jdbc");

        binder.bind(ApiKeyService.class).toInstance(new JDBCApiKeyService(metadataDataSource));
        binder.bind(new TypeLiteral<List<AggregationType>>(){}).annotatedWith(RealtimeAggregations.class)
                .toInstance(ImmutableList.of(
                        AggregationType.COUNT,
                AggregationType.SUM,
                AggregationType.MINIMUM,
                AggregationType.MAXIMUM,
                AggregationType.APPROXIMATE_UNIQUE));

        binder.bind(RealtimeService.class).to(PrestoRealtimeService.class);

        // use same jdbc pool if report.metadata.store is not set explicitly.
        if(getConfig("report.metadata.store") == null) {
            binder.bind(JDBCPoolDataSource.class)
                    .annotatedWith(Names.named("report.metadata.store.jdbc"))
                    .toInstance(metadataDataSource);

            binder.bind(ConfigManager.class).to(MysqlConfigManager.class);
            binder.bind(QueryMetadataStore.class).to(JDBCQueryMetadata.class)
                    .in(Scopes.SINGLETON);
        }

        binder.bind(Metastore.class).to(PrestoMetastore.class);
        if ("postgresql".equals(getConfig("plugin.user.storage"))) {
            binder.bind(AbstractPostgresqlUserStorage.class).to(PrestoExternalUserStorageAdapter.class)
                    .in(Scopes.SINGLETON);
            binder.bind(AbstractUserService.class).to(PrestoUserService.class)
                    .in(Scopes.SINGLETON);
        }

        if (buildConfigObject(EventExplorerConfig.class).isEventExplorerEnabled()) {
            binder.bind(EventExplorerListener.class).asEagerSingleton();
            binder.bind(EventExplorer.class).to(PrestoEventExplorer.class);
        }
        UserPluginConfig userPluginConfig = buildConfigObject(UserPluginConfig.class);

        if(userPluginConfig.getEnableUserMapping()) {
            binder.bind(UserMergeTableHook.class).asEagerSingleton();
        }

        if (userPluginConfig.isFunnelAnalysisEnabled()) {
            binder.bind(FunnelQueryExecutor.class).to(PrestoFunnelQueryExecutor.class);
        }

        if (userPluginConfig.isRetentionAnalysisEnabled()) {
            binder.bind(RetentionQueryExecutor.class).to(PrestoRetentionQueryExecutor.class);
        }

        Multibinder<EventMapper> timeMapper = Multibinder.newSetBinder(binder, EventMapper.class);
        timeMapper.addBinding().to(TimestampEventMapper.class).in(Scopes.SINGLETON);
    }

    @Override
    public String name() {
        return "PrestoDB backend for Rakam";
    }

    @Override
    public String description() {
        return "Rakam backend for high-throughput systems.";
    }

    private JDBCPoolDataSource bindJDBCConfig(Binder binder, String config) {
        JDBCPoolDataSource dataSource = JDBCPoolDataSource.getOrCreateDataSource(
                buildConfigObject(JDBCConfig.class, config));
        binder.bind(JDBCPoolDataSource.class)
                .annotatedWith(Names.named(config))
                .toInstance(dataSource);
        return dataSource;
    }

    public static class UserMergeTableHook {
        private final PrestoQueryExecutor executor;

        @Inject
        public UserMergeTableHook(PrestoQueryExecutor executor) {
            this.executor = executor;
        }

        @Subscribe
        public void onCreateProject(ProjectCreatedEvent event) {
            executor.executeRawStatement(String.format("CREATE TABLE %s(id VARCHAR, _user VARCHAR, " +
                    "created_at TIMESTAMP, merged_at TIMESTAMP)",
                    executor.formatTableReference(event.project, QualifiedName.of(ANONYMOUS_ID_MAPPING))));
        }
    }

    public static class PrestoRealtimeService
            extends RealtimeService
    {
        @Inject
        public PrestoRealtimeService(ContinuousQueryService service, QueryExecutor executor, @RealtimeAggregations List<AggregationType> aggregationTypes, RealTimeConfig config, @TimestampToEpochFunction String timestampToEpochFunction, @EscapeIdentifier char escapeIdentifier)
        {
            super(service, executor, aggregationTypes, config, timestampToEpochFunction, escapeIdentifier);
        }

        @Override
        public String timeColumn()
        {
            return "_time";
        }

        @Override
        public String getIntermediateFunction(AggregationType type) {
            String format;
            switch (type) {
                case MAXIMUM:
                    format = "max(%s)";
                    break;
                case MINIMUM:
                    format = "min(%s)";
                    break;
                case COUNT:
                    format = "count(%s)";
                    break;
                case SUM:
                    format = "sum(%s)";
                    break;
                case APPROXIMATE_UNIQUE:
                    format = "approx_set(%s)";
                    break;
                case COUNT_UNIQUE:
                    format = "set(%s)";
                    break;
                default:
                    throw new RakamException("Aggregation type couldn't found.", BAD_REQUEST);
            }

            return format;
        }
        @Override
        public String combineFunction(AggregationType aggregationType)
        {
            switch (aggregationType) {
                case COUNT:
                case SUM:
                    return "sum(%s)";
                case MINIMUM:
                    return "min(%s)";
                case MAXIMUM:
                    return "max(%s)";
                case APPROXIMATE_UNIQUE:
                    return "cardinality(merge(%s))";
                case COUNT_UNIQUE:
                    return "cardinality(merge(%s))";
                default:
                    throw new RakamException("Aggregation type couldn't found.", BAD_REQUEST);
            }
        }
    }
}
