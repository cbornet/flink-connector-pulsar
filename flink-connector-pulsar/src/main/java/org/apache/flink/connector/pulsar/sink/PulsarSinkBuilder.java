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

package org.apache.flink.connector.pulsar.sink;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.pulsar.common.config.PulsarConfigBuilder;
import org.apache.flink.connector.pulsar.common.config.PulsarOptions;
import org.apache.flink.connector.pulsar.common.crypto.PulsarCrypto;
import org.apache.flink.connector.pulsar.sink.config.SinkConfiguration;
import org.apache.flink.connector.pulsar.sink.writer.delayer.MessageDelayer;
import org.apache.flink.connector.pulsar.sink.writer.router.TopicRouter;
import org.apache.flink.connector.pulsar.sink.writer.router.TopicRoutingMode;
import org.apache.flink.connector.pulsar.sink.writer.serializer.PulsarSchemaWrapper;
import org.apache.flink.connector.pulsar.sink.writer.serializer.PulsarSerializationSchema;
import org.apache.flink.connector.pulsar.sink.writer.serializer.PulsarSerializationSchemaWrapper;
import org.apache.flink.connector.pulsar.sink.writer.topic.MetadataListener;

import org.apache.pulsar.client.api.ProducerCryptoFailureAction;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.schema.KeyValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.flink.connector.pulsar.common.config.PulsarOptions.PULSAR_ADMIN_URL;
import static org.apache.flink.connector.pulsar.common.config.PulsarOptions.PULSAR_AUTH_PARAMS;
import static org.apache.flink.connector.pulsar.common.config.PulsarOptions.PULSAR_AUTH_PARAM_MAP;
import static org.apache.flink.connector.pulsar.common.config.PulsarOptions.PULSAR_AUTH_PLUGIN_CLASS_NAME;
import static org.apache.flink.connector.pulsar.common.config.PulsarOptions.PULSAR_ENABLE_TRANSACTION;
import static org.apache.flink.connector.pulsar.common.config.PulsarOptions.PULSAR_SERVICE_URL;
import static org.apache.flink.connector.pulsar.sink.PulsarSinkOptions.PULSAR_PRODUCER_CRYPTO_FAILURE_ACTION;
import static org.apache.flink.connector.pulsar.sink.PulsarSinkOptions.PULSAR_PRODUCER_NAME;
import static org.apache.flink.connector.pulsar.sink.PulsarSinkOptions.PULSAR_SEND_TIMEOUT_MS;
import static org.apache.flink.connector.pulsar.sink.PulsarSinkOptions.PULSAR_WRITE_DELIVERY_GUARANTEE;
import static org.apache.flink.connector.pulsar.sink.PulsarSinkOptions.PULSAR_WRITE_SCHEMA_EVOLUTION;
import static org.apache.flink.connector.pulsar.sink.PulsarSinkOptions.PULSAR_WRITE_TRANSACTION_TIMEOUT;
import static org.apache.flink.connector.pulsar.sink.config.PulsarSinkConfigUtils.SINK_CONFIG_VALIDATOR;
import static org.apache.flink.connector.pulsar.source.enumerator.topic.TopicNameUtils.distinctTopics;
import static org.apache.flink.util.InstantiationUtil.isSerializable;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * The builder class for {@link PulsarSink} to make it easier for the users to construct a {@link
 * PulsarSink}.
 *
 * <p>The following example shows the minimum setup to create a PulsarSink that reads the String
 * values from a Pulsar topic.
 *
 * <pre>{@code
 * PulsarSink<String> sink = PulsarSink.builder()
 *     .setServiceUrl(operator().serviceUrl())
 *     .setAdminUrl(operator().adminUrl())
 *     .setTopics(topic)
 *     .setSerializationSchema(Schema.STRING)
 *     .build();
 * }</pre>
 *
 * <p>The service url, admin url, and the record serializer are required fields that must be set. If
 * you don't set the topics, make sure you have provided a custom {@link TopicRouter}. Otherwise,
 * you must provide the topics to produce.
 *
 * <p>To specify the delivery guarantees of PulsarSink, one can call {@link
 * #setDeliveryGuarantee(DeliveryGuarantee)}. The default value of the delivery guarantee is {@link
 * DeliveryGuarantee#NONE}, and it wouldn't promise the consistence when write the message into
 * Pulsar.
 *
 * <pre>{@code
 * PulsarSink<String> sink = PulsarSink.builder()
 *     .setServiceUrl(operator().serviceUrl())
 *     .setAdminUrl(operator().adminUrl())
 *     .setTopics(topic)
 *     .setSerializationSchema(Schema.STRING)
 *     .setDeliveryGuarantee(deliveryGuarantee)
 *     .build();
 * }</pre>
 *
 * @see PulsarSink for a more detailed explanation of the different guarantees.
 * @param <IN> The input type of the sink.
 */
@PublicEvolving
public class PulsarSinkBuilder<IN> {
    private static final Logger LOG = LoggerFactory.getLogger(PulsarSinkBuilder.class);

    private final PulsarConfigBuilder configBuilder;

    private PulsarSerializationSchema<IN> serializationSchema;
    private MetadataListener metadataListener;
    private TopicRoutingMode topicRoutingMode;
    private TopicRouter<IN> topicRouter;
    private MessageDelayer<IN> messageDelayer;
    private PulsarCrypto pulsarCrypto;

    // private builder constructor.
    PulsarSinkBuilder() {
        this.configBuilder = new PulsarConfigBuilder();
    }

    /**
     * Sets the admin endpoint for the PulsarAdmin of the PulsarSink.
     *
     * @param adminUrl The url for the PulsarAdmin.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setAdminUrl(String adminUrl) {
        return setConfig(PULSAR_ADMIN_URL, adminUrl);
    }

    /**
     * Sets the server's link for the PulsarProducer of the PulsarSink.
     *
     * @param serviceUrl The server url of the Pulsar cluster.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setServiceUrl(String serviceUrl) {
        return setConfig(PULSAR_SERVICE_URL, serviceUrl);
    }

    /**
     * The producer name is informative, and it can be used to identify a particular producer
     * instance from the topic stats.
     *
     * @param producerName The name of the producer used in Pulsar sink.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setProducerName(String producerName) {
        return setConfig(PULSAR_PRODUCER_NAME, producerName);
    }

    /**
     * Set a pulsar topic list for flink sink. Some topic may not exist currently, write to this
     * non-existed topic wouldn't throw any exception.
     *
     * @param topics The topic list you would like to consume message.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setTopics(String... topics) {
        return setTopics(Arrays.asList(topics));
    }

    /**
     * Set a pulsar topic list for flink sink. Some topic may not exist currently, consuming this
     * non-existed topic wouldn't throw any exception.
     *
     * @param topics The topic list you would like to consume message.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setTopics(List<String> topics) {
        checkState(metadataListener == null, "setTopics couldn't be set twice.");
        // Making sure the topic should be distinct.
        List<String> topicSet = distinctTopics(topics);
        this.metadataListener = new MetadataListener(topicSet);
        return this;
    }

    /**
     * Sets the wanted the {@link DeliveryGuarantee}. The default delivery guarantee is {@link
     * DeliveryGuarantee#NONE}.
     *
     * @param deliveryGuarantee Deliver guarantees.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setDeliveryGuarantee(DeliveryGuarantee deliveryGuarantee) {
        checkNotNull(deliveryGuarantee, "deliveryGuarantee");
        configBuilder.override(PULSAR_WRITE_DELIVERY_GUARANTEE, deliveryGuarantee);
        return this;
    }

    /**
     * Set a routing mode for choosing right topic partition to send messages.
     *
     * @param topicRoutingMode Routing policy for choosing the desired topic.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setTopicRoutingMode(TopicRoutingMode topicRoutingMode) {
        checkArgument(
                topicRoutingMode != TopicRoutingMode.CUSTOM,
                "CUSTOM mode should be set by using setTopicRouter method.");
        this.topicRoutingMode = checkNotNull(topicRoutingMode, "topicRoutingMode");
        return this;
    }

    /**
     * Use a custom topic router instead predefine topic routing.
     *
     * @param topicRouter The router for choosing topic to send message.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setTopicRouter(TopicRouter<IN> topicRouter) {
        if (topicRoutingMode != null && topicRoutingMode != TopicRoutingMode.CUSTOM) {
            LOG.warn("We would override topicRoutingMode to CUSTOM if you provide TopicRouter.");
        }
        this.topicRoutingMode = TopicRoutingMode.CUSTOM;
        this.topicRouter = checkNotNull(topicRouter, "topicRouter");
        return this;
    }

    /**
     * Send messages to Pulsar by using the flink's {@link SerializationSchema}. It would serialize
     * the message into a byte array and send it to Pulsar with {@link Schema#BYTES}.
     */
    public <T extends IN> PulsarSinkBuilder<T> setSerializationSchema(
            SerializationSchema<T> serializationSchema) {
        return setSerializationSchema(new PulsarSerializationSchemaWrapper<>(serializationSchema));
    }

    /**
     * Send messages to Pulsar by using the Pulsar {@link Schema} instance. It would serialize the
     * message into a byte array and send it to Pulsar with {@link Schema#BYTES}. You can directly
     * use the Schema you provided by enabling the {@link #enableSchemaEvolution()}.
     *
     * <p>We only support <a
     * href="https://pulsar.apache.org/docs/en/schema-understand/#primitive-type">primitive
     * types</a> here.
     */
    public <T extends IN> PulsarSinkBuilder<T> setSerializationSchema(Schema<T> schema) {
        return setSerializationSchema(new PulsarSchemaWrapper<>(schema));
    }

    /**
     * Send messages to Pulsar by using the Pulsar {@link Schema} instance. It would serialize the
     * message into a byte array and send it to Pulsar with {@link Schema#BYTES}. You can directly
     * use the Schema you provided by enabling the {@link #enableSchemaEvolution()}.
     *
     * <p>We only support <a
     * href="https://pulsar.apache.org/docs/en/schema-understand/#struct">struct types</a> here.
     */
    public <T extends IN> PulsarSinkBuilder<T> setSerializationSchema(
            Schema<T> schema, Class<T> typeClass) {
        return setSerializationSchema(new PulsarSchemaWrapper<>(schema, typeClass));
    }

    /**
     * Send messages to Pulsar by using the Pulsar {@link Schema} instance. It would serialize the
     * message into a byte array and send it to Pulsar with {@link Schema#BYTES}. You can directly
     * use the Schema you provided by enabling the {@link #enableSchemaEvolution()}.
     *
     * <p>We only support <a
     * href="https://pulsar.apache.org/docs/en/schema-understand/#keyvalue">keyvalue types</a> here.
     */
    public <K, V, T extends IN> PulsarSinkBuilder<T> setSerializationSchema(
            Schema<KeyValue<K, V>> schema, Class<K> keyClass, Class<V> valueClass) {
        return setSerializationSchema(new PulsarSchemaWrapper<>(schema, keyClass, valueClass));
    }

    /**
     * Sets the {@link PulsarSerializationSchema} that transforms incoming records to bytes.
     *
     * @param serializationSchema Pulsar specified serialize logic.
     * @return this PulsarSinkBuilder.
     */
    public <T extends IN> PulsarSinkBuilder<T> setSerializationSchema(
            PulsarSerializationSchema<T> serializationSchema) {
        PulsarSinkBuilder<T> self = specialized();
        self.serializationSchema = serializationSchema;
        return self;
    }

    /**
     * If you enable this option, we would consume and deserialize the message by using Pulsar
     * {@link Schema}.
     *
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> enableSchemaEvolution() {
        configBuilder.override(PULSAR_WRITE_SCHEMA_EVOLUTION, true);
        return this;
    }

    /**
     * Set a message delayer for enable Pulsar message delay delivery.
     *
     * @param messageDelayer The delayer which would defined when to send the message to the
     *     consumer.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> delaySendingMessage(MessageDelayer<IN> messageDelayer) {
        this.messageDelayer = checkNotNull(messageDelayer);
        return this;
    }

    /**
     * Sets a {@link PulsarCrypto}. Configure the key reader and keys to be used to encrypt the
     * message payloads.
     *
     * @param pulsarCrypto PulsarCrypto object.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setPulsarCrypto(
            PulsarCrypto pulsarCrypto, ProducerCryptoFailureAction action) {
        this.pulsarCrypto = checkNotNull(pulsarCrypto);
        configBuilder.set(PULSAR_PRODUCER_CRYPTO_FAILURE_ACTION, action);
        return this;
    }

    /**
     * Configure the authentication provider to use in the Pulsar client instance.
     *
     * @param authPluginClassName name of the Authentication-Plugin you want to use
     * @param authParamsString string which represents parameters for the Authentication-Plugin,
     *     e.g., "key1:val1,key2:val2"
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setAuthentication(
            String authPluginClassName, String authParamsString) {
        configBuilder.set(PULSAR_AUTH_PLUGIN_CLASS_NAME, authPluginClassName);
        configBuilder.set(PULSAR_AUTH_PARAMS, authParamsString);
        return this;
    }

    /**
     * Configure the authentication provider to use in the Pulsar client instance.
     *
     * @param authPluginClassName name of the Authentication-Plugin you want to use
     * @param authParams map which represents parameters for the Authentication-Plugin
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setAuthentication(
            String authPluginClassName, Map<String, String> authParams) {
        configBuilder.set(PULSAR_AUTH_PLUGIN_CLASS_NAME, authPluginClassName);
        configBuilder.set(PULSAR_AUTH_PARAM_MAP, authParams);
        return this;
    }

    /**
     * Set an arbitrary property for the PulsarSink and Pulsar Producer. The valid keys can be found
     * in {@link PulsarSinkOptions} and {@link PulsarOptions}.
     *
     * <p>Make sure the option could be set only once or with same value.
     *
     * @param key The key of the property.
     * @param value The value of the property.
     * @return this PulsarSinkBuilder.
     */
    public <T> PulsarSinkBuilder<IN> setConfig(ConfigOption<T> key, T value) {
        configBuilder.set(key, value);
        return this;
    }

    /**
     * Set arbitrary properties for the PulsarSink and Pulsar Producer. The valid keys can be found
     * in {@link PulsarSinkOptions} and {@link PulsarOptions}.
     *
     * @param config The config to set for the PulsarSink.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setConfig(Configuration config) {
        configBuilder.set(config);
        return this;
    }

    /**
     * Set arbitrary properties for the PulsarSink and Pulsar Producer. The valid keys can be found
     * in {@link PulsarSinkOptions} and {@link PulsarOptions}.
     *
     * <p>This method is mainly used for future flink SQL binding.
     *
     * @param properties The config properties to set for the PulsarSink.
     * @return this PulsarSinkBuilder.
     */
    public PulsarSinkBuilder<IN> setProperties(Properties properties) {
        configBuilder.set(properties);
        return this;
    }

    /**
     * Build the {@link PulsarSink}.
     *
     * @return a PulsarSink with the settings made for this builder.
     */
    public PulsarSink<IN> build() {
        // Change delivery guarantee.
        DeliveryGuarantee deliveryGuarantee = configBuilder.get(PULSAR_WRITE_DELIVERY_GUARANTEE);
        if (deliveryGuarantee == DeliveryGuarantee.NONE) {
            LOG.warn(
                    "You haven't set delivery guarantee or set it to NONE, this would cause data loss. Make sure you have known this shortcoming.");
        } else if (deliveryGuarantee == DeliveryGuarantee.EXACTLY_ONCE) {
            LOG.info(
                    "Exactly once require flink checkpoint and your pulsar cluster should support the transaction.");
            configBuilder.override(PULSAR_ENABLE_TRANSACTION, true);
            configBuilder.override(PULSAR_SEND_TIMEOUT_MS, 0L);

            if (!configBuilder.contains(PULSAR_WRITE_TRANSACTION_TIMEOUT)) {
                LOG.warn(
                        "The default pulsar transaction timeout is 3 hours, make sure it was greater than your checkpoint interval.");
            } else {
                Long timeout = configBuilder.get(PULSAR_WRITE_TRANSACTION_TIMEOUT);
                LOG.warn(
                        "The configured transaction timeout is {} mille seconds, make sure it was greater than your checkpoint interval.",
                        timeout);
            }
        }

        if (!configBuilder.contains(PULSAR_PRODUCER_NAME)) {
            LOG.warn(
                    "We recommend set a readable producer name through setProducerName(String) in production mode.");
        } else {
            String producerName = configBuilder.get(PULSAR_PRODUCER_NAME);
            if (!producerName.contains("%s")) {
                configBuilder.override(PULSAR_PRODUCER_NAME, producerName + " - %s");
            }
        }

        checkNotNull(serializationSchema, "serializationSchema must be set.");
        // Schema evolution validation.
        if (Boolean.TRUE.equals(configBuilder.get(PULSAR_WRITE_SCHEMA_EVOLUTION))) {
            checkState(
                    serializationSchema instanceof PulsarSchemaWrapper,
                    "When enabling schema evolution, you must provide a Pulsar Schema in builder's setSerializationSchema method.");
        } else if (serializationSchema instanceof PulsarSchemaWrapper) {
            LOG.info(
                    "It seems like you are sending messages by using Pulsar Schema."
                            + " You can builder.enableSchemaEvolution() to enable schema evolution for better Pulsar Schema check."
                            + " We would use bypass Schema check by default.");
        }

        // Topic metadata listener validation.
        if (metadataListener == null) {
            if (topicRouter == null) {
                throw new NullPointerException(
                        "No topic names or custom topic router are provided.");
            } else {
                LOG.warn(
                        "No topic set has been provided, make sure your custom topic router support empty topic set.");
                this.metadataListener = new MetadataListener();
            }
        }

        // Topic routing mode validation.
        if (topicRoutingMode == null) {
            LOG.info("No topic routing mode has been chosen. We use round-robin mode as default.");
            this.topicRoutingMode = TopicRoutingMode.ROUND_ROBIN;
        }

        if (messageDelayer == null) {
            this.messageDelayer = MessageDelayer.never();
        }

        if (pulsarCrypto == null) {
            this.pulsarCrypto = PulsarCrypto.disabled();
        }

        // Make sure they are serializable.
        checkState(
                isSerializable(serializationSchema),
                "PulsarSerializationSchema isn't serializable");
        checkState(isSerializable(messageDelayer), "MessageDelayer isn't serializable");
        checkState(isSerializable(pulsarCrypto), "PulsarCrypto isn't serializable");

        // This is an unmodifiable configuration for Pulsar.
        // We don't use Pulsar's built-in configure classes for compatible requirement.
        SinkConfiguration sinkConfiguration =
                configBuilder.build(SINK_CONFIG_VALIDATOR, SinkConfiguration::new);

        return new PulsarSink<>(
                sinkConfiguration,
                serializationSchema,
                metadataListener,
                topicRoutingMode,
                topicRouter,
                messageDelayer,
                pulsarCrypto);
    }

    // ------------- private helpers  --------------

    /** Helper method for java compiler recognizes the generic type. */
    @SuppressWarnings("unchecked")
    private <T extends IN> PulsarSinkBuilder<T> specialized() {
        return (PulsarSinkBuilder<T>) this;
    }
}
