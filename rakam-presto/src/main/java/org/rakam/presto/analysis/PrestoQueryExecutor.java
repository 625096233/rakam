package org.rakam.presto.analysis;

import com.facebook.presto.jdbc.internal.airlift.units.Duration;
import com.facebook.presto.jdbc.internal.client.ClientSession;
import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Singleton;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.collection.SchemaField;
import org.rakam.report.QueryExecutor;
import org.rakam.util.RakamException;

import javax.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.facebook.presto.jdbc.internal.client.ClientSession.withTransactionId;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static java.time.ZoneOffset.UTC;
import static org.rakam.presto.analysis.PrestoMaterializedViewService.MATERIALIZED_VIEW_PREFIX;

@Singleton
public class PrestoQueryExecutor
        implements QueryExecutor
{
    private final PrestoConfig prestoConfig;

    private final Metastore metastore;
    private ClientSession defaultSession;

    @Inject
    public PrestoQueryExecutor(PrestoConfig prestoConfig, Metastore metastore)
    {
        this.prestoConfig = prestoConfig;
        this.metastore = metastore;
        this.defaultSession = new ClientSession(
                prestoConfig.getAddress(),
                "rakam",
                "api-server",
                prestoConfig.getColdStorageConnector(),
                "default",
                TimeZone.getTimeZone(UTC).getID(),
                Locale.ENGLISH,
                ImmutableMap.of(),
                null,
                false, new Duration(1, TimeUnit.MINUTES));
    }

    @Override
    public PrestoQueryExecution executeRawQuery(String query)
    {
        return new PrestoQueryExecution(defaultSession, query);
    }

    public PrestoQueryExecution executeRawQuery(String query, String transactionId)
    {
        return new PrestoQueryExecution(withTransactionId(defaultSession, transactionId), query);
    }

    public PrestoQueryExecution executeRawQuery(String query, Map<String, String> sessionProperties, String catalog)
    {
        return new PrestoQueryExecution(new ClientSession(
                prestoConfig.getAddress(),
                "rakam",
                "api-server",
                catalog == null ? "default" : catalog,
                "default",
                TimeZone.getDefault().getID(),
                Locale.ENGLISH,
                sessionProperties,
                null, false, new Duration(1, TimeUnit.MINUTES)), query);
    }

    @Override
    public PrestoQueryExecution executeRawStatement(String sqlQuery)
    {
        return executeRawQuery(sqlQuery);
    }

    @Override
    public String formatTableReference(String project, QualifiedName node)
    {
        if (node.getPrefix().isPresent()) {
            String prefix = node.getPrefix().get().toString();
            if (prefix.equals("continuous")) {
                return prestoConfig.getStreamingConnector() + ".\"" + project + "\".\"" + node.getSuffix() + '"';
            }
            else if (prefix.equals("materialized")) {
                return prestoConfig.getColdStorageConnector() + ".\"" + project + "\".\"" + MATERIALIZED_VIEW_PREFIX + node.getSuffix() + '"';
            }
            else if (!prefix.equals("collection")) {
                throw new RakamException("Schema does not exist: " + prefix, BAD_REQUEST);
            }
        }

        // special prefix for all columns
        if (node.getSuffix().equals("_all") && !node.getPrefix().isPresent()) {
            List<Map.Entry<String, List<SchemaField>>> collections = metastore.getCollections(project).entrySet().stream()
                    .filter(c -> !c.getKey().startsWith("_"))
                    .collect(Collectors.toList());
            if (!collections.isEmpty()) {
                String sharedColumns = collections.get(0).getValue().stream()
                        .filter(col -> collections.stream().allMatch(list -> list.getValue().contains(col)))
                        .map(f -> f.getName())
                        .collect(Collectors.joining(", "));

                return "(" + collections.stream().map(Map.Entry::getKey)
                        .map(collection -> format("select '%s' as \"$collection\", %s from %s",
                                collection,
                                sharedColumns.isEmpty() ? "1" : sharedColumns,
                                getTableReference(project, QualifiedName.of(collection))))
                        .collect(Collectors.joining(" union all ")) + ") _all";
            }
            else {
                return "(select null as \"$collection\", null as _user, null as _time limit 0) _all";
            }
        }
        else {
            if (node.getSuffix().equals("users")) {
                return prestoConfig.getUserConnector() + ".users." + project;
            }
            return getTableReference(project, node);
        }
    }

    private String getTableReference(String project, QualifiedName node)
    {
        QualifiedName prefix = QualifiedName.of(prestoConfig.getColdStorageConnector());
        String hotStorageConnector = prestoConfig.getHotStorageConnector();
        String table = '"' + project + "\".\"" + node.getSuffix() + '\"';

        if (hotStorageConnector != null) {
            return "((select * from " + prefix.getSuffix() + "." + table + " union all " +
                    "select * from " + hotStorageConnector + "." + table + ")" +
                    " as " + node.getSuffix() + ")";
        }
        else {
            return prefix.getSuffix() + "." + table;
        }
    }
}
