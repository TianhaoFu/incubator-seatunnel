/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.clickhouse.sink.client;

import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.BULK_SIZE;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.CLICKHOUSE_PREFIX;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.DATABASE;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.FIELDS;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.HOST;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.PASSWORD;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.SHARDING_KEY;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.SPLIT_MODE;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.TABLE;
import static org.apache.seatunnel.connectors.seatunnel.clickhouse.config.Config.USERNAME;

import org.apache.seatunnel.api.common.PrepareFailException;
import org.apache.seatunnel.api.common.SeaTunnelContext;
import org.apache.seatunnel.api.serialization.DefaultSerializer;
import org.apache.seatunnel.api.serialization.Serializer;
import org.apache.seatunnel.api.sink.SeaTunnelSink;
import org.apache.seatunnel.api.sink.SinkWriter;
import org.apache.seatunnel.api.table.type.SeaTunnelDataType;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;
import org.apache.seatunnel.api.table.type.SeaTunnelRowType;
import org.apache.seatunnel.common.config.CheckConfigUtil;
import org.apache.seatunnel.common.config.CheckResult;
import org.apache.seatunnel.common.config.TypesafeConfigUtils;
import org.apache.seatunnel.common.constants.PluginType;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.config.ReaderOption;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.shard.Shard;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.shard.ShardMetadata;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.sink.file.ClickhouseTable;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.state.CKAggCommitInfo;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.state.CKCommitInfo;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.state.ClickhouseSinkState;
import org.apache.seatunnel.connectors.seatunnel.clickhouse.util.ClickhouseUtil;

import org.apache.seatunnel.shade.com.typesafe.config.Config;
import org.apache.seatunnel.shade.com.typesafe.config.ConfigFactory;

import com.clickhouse.client.ClickHouseNode;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

@AutoService(SeaTunnelSink.class)
public class ClickhouseSink implements SeaTunnelSink<SeaTunnelRow, ClickhouseSinkState, CKCommitInfo, CKAggCommitInfo> {

    private SeaTunnelContext seaTunnelContext;
    private ReaderOption option;

    @Override
    public String getPluginName() {
        return "Clickhouse";
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    @Override
    public void prepare(Config config) throws PrepareFailException {
        CheckResult result = CheckConfigUtil.checkAllExists(config, HOST, DATABASE, TABLE);

        boolean isCredential = config.hasPath(USERNAME) || config.hasPath(PASSWORD);

        if (isCredential) {
            result = CheckConfigUtil.checkAllExists(config, USERNAME, PASSWORD);
        }

        if (!result.isSuccess()) {
            throw new PrepareFailException(getPluginName(), PluginType.SINK, result.getMsg());
        }
        Map<String, Object> defaultConfig = ImmutableMap.<String, Object>builder()
                .put(BULK_SIZE, 20_000)
                .put(SPLIT_MODE, false)
                .build();

        config = config.withFallback(ConfigFactory.parseMap(defaultConfig));

        List<ClickHouseNode> nodes;
        if (!isCredential) {
            nodes = ClickhouseUtil.createNodes(config.getString(HOST), config.getString(DATABASE),
                    null, null);
        } else {
            nodes = ClickhouseUtil.createNodes(config.getString(HOST),
                    config.getString(DATABASE), config.getString(USERNAME), config.getString(PASSWORD));
        }

        Properties clickhouseProperties = new Properties();
        if (TypesafeConfigUtils.hasSubConfig(config, CLICKHOUSE_PREFIX)) {
            TypesafeConfigUtils.extractSubConfig(config, CLICKHOUSE_PREFIX, false).entrySet().forEach(e -> {
                clickhouseProperties.put(e.getKey(), String.valueOf(e.getValue().unwrapped()));
            });
        }

        if (isCredential) {
            clickhouseProperties.put("user", config.getString(USERNAME));
            clickhouseProperties.put("password", config.getString(PASSWORD));
        }

        ClickhouseProxy proxy = new ClickhouseProxy(nodes.get(0));
        Map<String, String> tableSchema = proxy.getClickhouseTableSchema(config.getString(TABLE));
        String shardKey = null;
        String shardKeyType = null;
        if (config.getBoolean(SPLIT_MODE)) {
            ClickhouseTable table = proxy.getClickhouseTable(config.getString(DATABASE),
                    config.getString(TABLE));
            if (!"Distributed".equals(table.getEngine())) {
                throw new IllegalArgumentException("split mode only support table which engine is " +
                        "'Distributed' engine at now");
            }
            if (config.hasPath(SHARDING_KEY)) {
                shardKey = config.getString(SHARDING_KEY);
                shardKeyType = tableSchema.get(shardKey);
            }
        }
        ShardMetadata metadata;

        if (isCredential) {
            metadata = new ShardMetadata(
                    shardKey,
                    shardKeyType,
                    config.getString(DATABASE),
                    config.getString(TABLE),
                    config.getBoolean(SPLIT_MODE),
                    new Shard(1, 1, nodes.get(0)), config.getString(USERNAME), config.getString(PASSWORD));
        } else {
            metadata = new ShardMetadata(
                    shardKey,
                    shardKeyType,
                    config.getString(DATABASE),
                    config.getString(TABLE),
                    config.getBoolean(SPLIT_MODE),
                    new Shard(1, 1, nodes.get(0)));
        }

        List<String> fields = new ArrayList<>();
        if (config.hasPath(FIELDS)) {
            fields.addAll(config.getStringList(FIELDS));
            // check if the fields exist in schema
            for (String field : fields) {
                if (!tableSchema.containsKey(field)) {
                    throw new RuntimeException("Field " + field + " does not exist in table " + config.getString(TABLE));
                }
            }
        } else {
            fields.addAll(tableSchema.keySet());
        }
        proxy.close();
        this.option = new ReaderOption(metadata, clickhouseProperties, fields, tableSchema, config.getInt(BULK_SIZE));
    }

    @Override
    public SinkWriter<SeaTunnelRow, CKCommitInfo, ClickhouseSinkState> createWriter(SinkWriter.Context context) throws IOException {
        return new ClickhouseSinkWriter(option, context);
    }

    @Override
    public SinkWriter<SeaTunnelRow, CKCommitInfo, ClickhouseSinkState> restoreWriter(SinkWriter.Context context, List<ClickhouseSinkState> states) throws IOException {
        return SeaTunnelSink.super.restoreWriter(context, states);
    }

    @Override
    public Optional<Serializer<ClickhouseSinkState>> getWriterStateSerializer() {
        return Optional.of(new DefaultSerializer<>());
    }

    @Override
    public void setTypeInfo(SeaTunnelRowType seaTunnelRowType) {
        this.option.setSeaTunnelRowType(seaTunnelRowType);
    }

    @Override
    public SeaTunnelDataType<SeaTunnelRow> getConsumedType() {
        return this.option.getSeaTunnelRowType();
    }

    @Override
    public void setSeaTunnelContext(SeaTunnelContext seaTunnelContext) {
        this.seaTunnelContext = seaTunnelContext;
    }
}
