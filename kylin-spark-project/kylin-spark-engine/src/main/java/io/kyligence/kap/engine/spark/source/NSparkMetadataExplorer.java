/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package io.kyligence.kap.engine.spark.source;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.ISourceAware;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.source.ISampleDataDeployer;
import org.apache.kylin.source.ISourceMetadataExplorer;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparderEnv;

import io.kyligence.kap.metadata.model.NTableMetadataManager;

public class NSparkMetadataExplorer implements ISourceMetadataExplorer, ISampleDataDeployer, Serializable {

    public NSparkTableMetaExplorer getTableMetaExplorer() {
        return new NSparkTableMetaExplorer();
    }

    @Override
    public List<String> listDatabases() throws Exception {
        Dataset<Row> dataset = SparderEnv.getSparkSession().sql("show databases").select("databaseName");
        return dataset.collectAsList().stream().map(row-> row.getString(0)).collect(Collectors.toList());
    }

    @Override
    public List<String> listTables(String database) throws Exception {
        String sql = "show tables";
        if (StringUtils.isNotBlank(database)) {
            sql = String.format(sql + " in %s", database);
        }
        Dataset<Row> dataset = SparderEnv.getSparkSession().sql(sql).select("tableName");
        return dataset.collectAsList().stream().map(row-> row.getString(0)).collect(Collectors.toList());
    }

    @Override
    public Pair<TableDesc, TableExtDesc> loadTableMetadata(final String database, String tableName, String prj)
            throws Exception {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        NTableMetadataManager metaMgr = NTableMetadataManager.getInstance(config, prj);

        NSparkTableMeta tableMeta = getTableMetaExplorer().getSparkTableMeta(database, tableName);
        TableDesc tableDesc = metaMgr.getTableDesc(database + "." + tableName);

        // make a new TableDesc instance, don't modify the one in use
        if (tableDesc == null) {
            tableDesc = new TableDesc();
            tableDesc.setDatabase(database.toUpperCase());
            tableDesc.setName(tableName.toUpperCase());
            tableDesc.setUuid(UUID.randomUUID().toString());
            tableDesc.setLastModified(0);
        } else {
            tableDesc = new TableDesc(tableDesc);
        }

        if (tableMeta.tableType != null) {
            tableDesc.setTableType(tableMeta.tableType);
        }
        //set table type = spark
        tableDesc.setSourceType(ISourceAware.ID_SPARK);
        int columnNumber = tableMeta.allColumns.size();
        List<ColumnDesc> columns = new ArrayList<ColumnDesc>(columnNumber);
        for (int i = 0; i < columnNumber; i++) {
            NSparkTableMeta.SparkTableColumnMeta field = tableMeta.allColumns.get(i);
            ColumnDesc cdesc = new ColumnDesc();
            cdesc.setName(field.name.toUpperCase());
            // use "double" in kylin for "float"
            if ("float".equalsIgnoreCase(field.dataType)) {
                cdesc.setDatatype("double");
            } else {
                cdesc.setDatatype(field.dataType);
            }
            cdesc.setId(String.valueOf(i + 1));
            cdesc.setComment(field.comment);
            columns.add(cdesc);
        }
        tableDesc.setColumns(columns.toArray(new ColumnDesc[columnNumber]));

        StringBuffer partitionColumnString = new StringBuffer();
        for (int i = 0, n = tableMeta.partitionColumns.size(); i < n; i++) {
            if (i > 0)
                partitionColumnString.append(", ");
            partitionColumnString.append(tableMeta.partitionColumns.get(i).name.toUpperCase());
        }

        TableExtDesc tableExtDesc = new TableExtDesc();
        tableExtDesc.setIdentity(tableDesc.getIdentity());
        tableExtDesc.setUuid(UUID.randomUUID().toString());
        tableExtDesc.setLastModified(0);
        tableExtDesc.init(prj);

        tableExtDesc.addDataSourceProp("location", tableMeta.sdLocation);
        tableExtDesc.addDataSourceProp("owner", tableMeta.owner);
        tableExtDesc.addDataSourceProp("create_time", tableMeta.createTime);
        tableExtDesc.addDataSourceProp("last_access_time", tableMeta.lastAccessTime);
        tableExtDesc.addDataSourceProp("partition_column", partitionColumnString.toString());
        tableExtDesc.addDataSourceProp("total_file_size", String.valueOf(tableMeta.fileSize));
        tableExtDesc.addDataSourceProp("total_file_number", String.valueOf(tableMeta.fileNum));
        tableExtDesc.addDataSourceProp("hive_inputFormat", tableMeta.sdInputFormat);
        tableExtDesc.addDataSourceProp("hive_outputFormat", tableMeta.sdOutputFormat);
        return Pair.newPair(tableDesc, tableExtDesc);
    }

    @Override
    public List<String> getRelatedKylinResources(TableDesc table) {
        return Collections.emptyList();
    }

    @Override
    public void createSampleDatabase(String database) throws Exception {
        SparderEnv.getSparkSession().sql(generateCreateSchemaSql(database));
    }

    private String generateCreateSchemaSql(String schemaName) {
        return String.format("CREATE DATABASE IF NOT EXISTS %s", schemaName);
    }

    @Override
    public void createSampleTable(TableDesc table) throws Exception {
        String[] createTableSqls = generateCreateTableSql(table);
        for (String sql : createTableSqls) {
            SparderEnv.getSparkSession().sql(sql);
        }
    }

    private String[] generateCreateTableSql(TableDesc tableDesc) {
        String dropSql = "DROP TABLE IF EXISTS " + tableDesc.getIdentity();

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE " + tableDesc.getIdentity() + "\n");
        ddl.append("(" + "\n");

        for (int i = 0; i < tableDesc.getColumns().length; i++) {
            ColumnDesc col = tableDesc.getColumns()[i];
            if (i > 0) {
                ddl.append(",");
            }
            ddl.append(col.getName() + " " + col.getDatatype() + "\n");
        }

        ddl.append(")" + "\n");
        ddl.append("USING com.databricks.spark.csv");

        return new String[] { dropSql, ddl.toString() };
    }

    @Override
    public void loadSampleData(String tableName, String tableFileDir) throws Exception {
        Dataset<Row> dataset = SparderEnv.getSparkSession().read().csv(tableFileDir + "/" + tableName + ".csv").toDF();
        if (tableName.indexOf(".") > 0) {
            tableName = tableName.substring(tableName.indexOf(".") + 1);
        }
        dataset.createOrReplaceTempView(tableName);
    }

    @Override
    public void createWrapperView(String origTableName, String viewName) throws Exception {
        throw new UnsupportedOperationException("unsupport create wrapper view");
    }
}