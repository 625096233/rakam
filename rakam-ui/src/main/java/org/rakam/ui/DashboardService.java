/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rakam.ui;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.IgnoreApi;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.ui.UIPermissionParameterProvider.Project;
import org.rakam.ui.user.WebUserService;
import org.rakam.util.AlreadyExistsException;
import org.rakam.util.JsonHelper;
import org.rakam.util.SuccessMessage;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.IntegerMapper;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Path;

import java.util.List;
import java.util.Map;

// todo check permissions
@Path("/ui/dashboard")
@IgnoreApi
public class DashboardService
        extends HttpService
{
    private final DBI dbi;

    @Inject
    public DashboardService(@Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource)
    {
        dbi = new DBI(dataSource);
    }

    @JsonRequest
    @ApiOperation(value = "Create dashboard")
    @Path("/create")
    @ProtectEndpoint(writeOperation = true)
    public Dashboard create(
            @Named("user_id") Project project,
            @ApiParam("name") String name,
            @ApiParam(value = "options", required = false) Map<String, Object> options)
    {
        try (Handle handle = dbi.open()) {
            int id;
            try {
                id = handle.createQuery("INSERT INTO dashboard (project_id, name, options) VALUES (:project, :name, :options) RETURNING id")
                        .bind("project", project.project)
                        .bind("options", JsonHelper.encode(options))
                        .bind("name", name).map(IntegerMapper.FIRST).first();
            }
            catch (Exception e) {
                if (handle.createQuery("SELECT 1 FROM dashboard WHERE (project_id, name) = (:project, :name)")
                        .bind("project", project.project)
                        .bind("name", name).first() != null) {
                    throw new AlreadyExistsException("Dashboard", HttpResponseStatus.BAD_REQUEST);
                }

                throw e;
            }
            return new Dashboard(id, name, options);
        }
    }

    @JsonRequest
    @ApiOperation(value = "Create dashboard")
    @Path("/delete")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage delete(@Named("user_id") Project project, @ApiParam(value = "name") String name)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM dashboard WHERE project_id = :project AND name = :name")
                    .bind("project", project.project)
                    .bind("name", name).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Get Report")
    @Path("/get")
    public List<DashboardItem> get(@Named("user_id") Project project,
            @ApiParam("name") String name)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT id, name, directive, data FROM dashboard_items WHERE dashboard = (SELECT id FROM dashboard WHERE project_id = :project AND name = :name)")
                    .bind("project", project.project)
                    .bind("name", name)
                    .map((i, r, statementContext) -> {
                        return new DashboardItem(r.getInt(1), r.getString(2), r.getString(3), JsonHelper.read(r.getString(4), Map.class));
                    }).list();
        }
    }

    @JsonRequest
    @ApiOperation(value = "List Report")
    @Path("/list")
    public List<Dashboard> list(@Named("user_id") Project project)
    {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT id, name, options FROM dashboard WHERE project_id = :project ORDER BY id")
                    .bind("project", project.project).map((i, resultSet, statementContext) -> {
                        String options = resultSet.getString(3);
                        Map data = JsonHelper.read(options, Map.class);
                        return new Dashboard(resultSet.getInt(1), resultSet.getString(2),
                                options == null ? null : data);
                    }).list();
        }
    }

    public static class Dashboard
    {
        public final int id;
        public final String name;
        public final Map<String, Object> options;

        public Dashboard(int id, String name, Map<String, Object> options)
        {
            this.id = id;
            this.name = name;
            this.options = options;
        }
    }

    public static class DashboardItem
    {
        public final Integer id;
        public final Map data;
        public final String directive;
        public final String name;

        @JsonCreator
        public DashboardItem(
                @JsonProperty("id") Integer id,
                @JsonProperty("name") String name,
                @JsonProperty("directive") String directive,
                @JsonProperty("data") Map data)
        {
            this.id = id;
            this.directive = directive;
            this.name = name;
            this.data = data;
        }
    }

    @JsonRequest
    @ApiOperation(value = "Add item to dashboard")
    @Path("/add_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage addToDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("name") String itemName,
            @ApiParam("directive") String directive,
            @ApiParam("data") Map data)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("INSERT INTO dashboard_items (dashboard, name, directive, data) VALUES (:dashboard, :name, :directive, :data)")
                    .bind("project", project.project)
                    .bind("dashboard", dashboard)
                    .bind("name", itemName)
                    .bind("directive", directive)
                    .bind("data", JsonHelper.encode(data)).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Update dashboard items")
    @Path("/update_dashboard_items")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage updateDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("items") List<DashboardItem> items)
    {

        dbi.inTransaction((handle, transactionStatus) -> {
            for (DashboardItem item : items) {
                // TODO: verify dashboard is in project
                handle.createStatement("UPDATE dashboard_items SET name = :name, directive = :directive, data = :data WHERE id = :id")
                        .bind("id", item.id)
                        .bind("name", item.name)
                        .bind("directive", item.directive)
                        .bind("data", JsonHelper.encode(item.data)).execute();
            }
            return null;
        });
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Update dashboard options")
    @Path("/update_dashboard_options")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage updateDashboardOptions(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("options") Map<String, Object> options)
    {

        dbi.inTransaction((handle, transactionStatus) -> {
            handle.createStatement("UPDATE dashboard SET options = :options WHERE id = :id AND project = :project")
                    .bind("id", dashboard)
                    .bind("options", JsonHelper.encode(options))
                    .bind("project", project.project)
                    .execute();
            return null;
        });
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Rename dashboard item")
    @Path("/rename_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage renameDashboardItem(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("id") int id,
            @ApiParam("name") String name)
    {
        try (Handle handle = dbi.open()) {
            // todo: check project
            handle.createStatement("UPDATE dashboard_items SET name = :name WHERE id = :id")
                    .bind("id", id)
                    .bind("name", name).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete dashboard item")
    @Path("/delete_item")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage removeFromDashboard(
            @Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard,
            @ApiParam("id") int id)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM dashboard_items " +
                    "WHERE dashboard = :dashboard AND id = :id")
                    .bind("project", project.project)
                    .bind("dashboard", dashboard)
                    .bind("id", id).execute();
        }
        return SuccessMessage.success();
    }

    @JsonRequest
    @ApiOperation(value = "Delete dashboard item")
    @Path("/delete")
    @ProtectEndpoint(writeOperation = true)
    public SuccessMessage delete(@Named("user_id") Project project,
            @ApiParam("dashboard") int dashboard)
    {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM dashboard WHERE id = :id and project_id = :project")
                    .bind("id", dashboard).bind("project", project.project).execute();
        }
        return SuccessMessage.success();
    }
}
