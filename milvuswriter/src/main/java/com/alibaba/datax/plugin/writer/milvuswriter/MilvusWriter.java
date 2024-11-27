package com.alibaba.datax.plugin.writer.milvuswriter;

import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class MilvusWriter extends Writer {
    public static class Job extends Writer.Job {
        private Configuration originalConfig = null;
        /**
         * 切分任务。<br>
         *
         * @param mandatoryNumber 为了做到Reader、Writer任务数对等，这里要求Writer插件必须按照源端的切分数进行切分。否则框架报错！
         */
        @Override
        public List<Configuration> split(int mandatoryNumber) {
            List<Configuration> configList = new ArrayList<Configuration>();
            for(int i = 0; i < mandatoryNumber; i++) {
                configList.add(this.originalConfig.clone());
            }
            return configList;
        }

        @Override
        public void init() {
            this.originalConfig = super.getPluginJobConf();
        }

        @Override
        public void destroy() {

        }
    }
    public static class Task extends Writer.Task {

        private MilvusClientV2 milvusClientV2;

        private MilvusSinkConverter milvusSinkConverter;
        private MilvusBufferWriter milvusBufferWriter;

        private String collection = null;
        private JSONArray milvusColumnMeta;

        private String schemaCreateMode = "createWhenTableNotExit";

        @Override
        public void startWrite(RecordReceiver lineReceiver) {
            Record record = lineReceiver.getFromReader();
            JsonObject data = milvusSinkConverter.convertByType(milvusColumnMeta, record);
            milvusBufferWriter.write(data);
            if(milvusBufferWriter.needCommit()){
                log.info("Reached buffer limit, Committing data");
                milvusBufferWriter.commit();
                log.info("Data committed");
            }
        }

        @Override
        public void init() {
            log.info("Initializing Milvus writer");
            // get configuration
            Configuration writerSliceConfig = this.getPluginJobConf();
            this.collection = writerSliceConfig.getString(KeyConstant.COLLECTION);
            this.milvusColumnMeta = JSON.parseArray(writerSliceConfig.getString(KeyConstant.COLUMN));
            this.schemaCreateMode = writerSliceConfig.getString(KeyConstant.schemaCreateMode) == null ?
                    "createWhenTableNotExit" : writerSliceConfig.getString(KeyConstant.schemaCreateMode);
            int batchSize = writerSliceConfig.getInt(KeyConstant.BATCH_SIZE, 100);
            log.info("Collection:{}", this.collection);
            // connect to milvus
            ConnectConfig connectConfig = ConnectConfig.builder()
                    .uri(writerSliceConfig.getString(KeyConstant.URI))
                    .token(writerSliceConfig.getString(KeyConstant.TOKEN))
                    .build();
            if(writerSliceConfig.getString(KeyConstant.DATABASE) == null) {
                log.warn("Database is set, using database{}", writerSliceConfig.getString(KeyConstant.DATABASE));
                connectConfig.setDbName(writerSliceConfig.getString(KeyConstant.DATABASE));
            }
            this.milvusClientV2 = new MilvusClientV2(connectConfig);
            this.milvusSinkConverter = new MilvusSinkConverter();
            this.milvusBufferWriter = new MilvusBufferWriter(milvusClientV2, collection, batchSize);
            log.info("Milvus writer initialized");
        }
        @Override
        public void prepare() {
            super.prepare();
            Boolean hasCollection = milvusClientV2.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
            if (!hasCollection) {
                log.info("Collection not exist");
                if (schemaCreateMode.equals("createWhenTableNotExit")) {
                    // create collection
                    log.info("Creating collection:{}", this.collection);
                    CreateCollectionReq.CollectionSchema collectionSchema = milvusSinkConverter.prepareCollectionSchema(milvusColumnMeta);

                    CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
                            .collectionName(collection)
                            .collectionSchema(collectionSchema)
                            .build();
                    milvusClientV2.createCollection(createCollectionReq);
                } else if (schemaCreateMode.equals("exception")) {
                    log.error("Collection not exist, throw exception");
                    throw new RuntimeException("Collection not exist");
                }
            }
        }

        @Override
        public void destroy() {
            log.info("Closing Milvus writer, committing data and closing connection");
            this.milvusBufferWriter.commit();
            this.milvusClientV2.close();
        }
    }
}
