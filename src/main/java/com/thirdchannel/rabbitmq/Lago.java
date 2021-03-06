package com.thirdchannel.rabbitmq;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ExceptionHandler;
import com.rabbitmq.client.QueueingConsumer;
import com.thirdchannel.rabbitmq.config.ExchangeConfig;
import com.thirdchannel.rabbitmq.config.QueueConsumerConfig;
import com.thirdchannel.rabbitmq.config.RabbitMQConfig;
import com.thirdchannel.rabbitmq.exceptions.LagoConfigLoadException;
import com.thirdchannel.rabbitmq.exceptions.LagoDefaultExceptionHandler;
import com.thirdchannel.rabbitmq.exceptions.RPCTimeoutException;
import com.thirdchannel.rabbitmq.exceptions.RabbitMQSetupException;
import com.thirdchannel.rabbitmq.interfaces.EventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

/**
 * Will keep a main channel open for publishing, although one can publish with an additional channel
 *
 * @author Steve Pember
 */
public class Lago implements com.thirdchannel.rabbitmq.interfaces.Lago {

    public static ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new AfterburnerModule())
        .configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(FAIL_ON_EMPTY_BEANS, false);

    private static final int NETWORK_RECOVERY_INTERVAL = 1000;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private Connection connection;
    private Channel channel;

    private ExceptionHandler exceptionHandler = new LagoDefaultExceptionHandler();

    private final List<EventConsumer> registeredConsumers = new ArrayList<>();

    private final RabbitMQConfig config;

    public Lago() throws LagoConfigLoadException {
        final PropertiesManager propertiesManager = new PropertiesManager();
        config = propertiesManager.load();
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    @Override
    public void setObjectMapper(ObjectMapper mapper) {
        OBJECT_MAPPER = mapper;
    }

    public RabbitMQConfig getConfig() {
        return config;
    }

    @Override
    public void registerConsumer(EventConsumer consumer) throws RabbitMQSetupException {
        if (consumer.isConfigured()) {
            log.info("{} appears to be already configured", consumer.getClass().getSimpleName());
        }
        else {
            consumer.setConfig(config.findQueueConfig(consumer));
        }
        if (consumer.getConfig().getCount() > 0) {
            log.debug("About to spin up {} instances of {}",
                consumer.getConfig().getCount(),
                consumer.getClass().getSimpleName()
            );
            bindConsumer(consumer, 0);
            for (int i = 1; i < consumer.getConfig().getCount(); i++) {
                bindConsumer(consumer.spawn(), i);
            }
            log.info("Registered Consumer: {}", consumer.getClass().getSimpleName());
        }
        else {
            log.warn("Count of less then one provided for Consumer: {}", consumer.getClass().getSimpleName());
        }
    }

    private void bindConsumer(EventConsumer consumer, int count) throws RabbitMQSetupException {
        consumer.setChannel(createChannel());
        consumer.setQueueName(consumer.getConfig().getName());

        try {
            log.debug("About to make queue with name: {}", consumer.getQueueName());
            Channel consumerChannel = consumer.getChannel();

            QueueConsumerConfig queueConsumerConfig = consumer.getConfig();

            consumerChannel.basicQos(queueConsumerConfig.getPrefetch());

            consumerChannel.queueDeclare(
                    consumer.getQueueName(),
                    queueConsumerConfig.isDurable(),
                    queueConsumerConfig.getCount() > 1,
                    queueConsumerConfig.isAutoDelete(),
                    null
            );

            consumer.setLago(this);

            for(String key : queueConsumerConfig.getKeys()) {
                // bind the queue to each key
                consumerChannel.queueBind(
                    consumer.getQueueName(),
                    queueConsumerConfig.getExchangeName(),
                    key
                );
            }

            // but ony one bind for the consumer in general
            consumerChannel.basicConsume(
                    consumer.getQueueName(),
                    queueConsumerConfig.isAutoAck(),
                    consumer.getClass().getSimpleName() + "-" + (count + 1),
                    consumer
            );

            registeredConsumers.add(consumer);
        } catch (IOException e) {
            log.error("Could not declare queue and bind to consumer:", e);
        }
    }

    @Override
    public List<EventConsumer> getRegisteredConsumers() {
        return registeredConsumers;
    }


    @Override
    public Connection connect() throws RabbitMQSetupException {
        // if environment variable present, use that
        // otherwise, use config. if no config, then throw exception
        String connectionUrl = config.getConnectionEnvironmentUrl();
        if (!connectionUrl.isEmpty()) {
            connect(connectionUrl);
        }
        else if (config.hasConnectionConfig()) {
            connect(
                config.getUsername(),
                config.getPassword(),
                config.getVirtualHost(),
                config.getHost(),
                config.getPort()
            );
        }
        else {
            throw new RabbitMQSetupException(
                "Could not located rabbit mq configuration in environment or config");
        }
        return getConnection();

    }


    @Override
    public Connection connect(String url) throws RabbitMQSetupException {
        log.debug("Connecting to RabbitMQ with URI: {}", url);

        if(url == null) {
            throw new RabbitMQSetupException("Given null RabbitMQ URL");
        }

        final ConnectionFactory connectionFactory = baseConnectionFactory();

        try {
            connectionFactory.setUri(url);
        } catch (final NoSuchAlgorithmException | KeyManagementException | URISyntaxException | NullPointerException e) {
            // setURI can throw NPE with valid URIs - seems like a bug
            throw new RabbitMQSetupException("Could not set URI on connection factory", e);
        }

        return connect(connectionFactory);
    }

    @Override
    public Connection connect(String userName, String password, String virtualHost, String host, int port)
        throws RabbitMQSetupException {

        final ConnectionFactory connectionFactory = baseConnectionFactory();

        connectionFactory.setUsername(userName);
        connectionFactory.setPassword(password);
        connectionFactory.setVirtualHost(virtualHost);
        connectionFactory.setHost(host);
        connectionFactory.setPort(port);

        return connect(connectionFactory);
    }

    /**
     * Connects using ConnectionFactory, allowing for custom configuration by the service.
     * Warning: no configuration will be provided. Make sure that you've set values like automatic recovery
     *
     * @param connectionFactory Lyra connection options
     * @return a connection
     * @throws RabbitMQSetupException if the connection cannot be created
     */
    @Override
    public Connection connect(ConnectionFactory connectionFactory) throws RabbitMQSetupException {
        try {
            log.debug("Connecting to RabbitMQ with connection factory: {}", connectionFactory);

            if (connection != null) {
                throw new RabbitMQSetupException("Connection already opened");
            }

            try {
                connection = connectionFactory.newConnection();
            } catch (final IOException | TimeoutException e) {
                throw new RabbitMQSetupException("Could not open connection to RabbitMQ", e);
            }
            log.debug("Connected to Rabbit");

            if (channel != null) {
                throw new RabbitMQSetupException("Channel already opened");
            }
            channel = createChannel();
            log.debug("Created channel");

            for (ExchangeConfig exchangeConfig : config.getExchanges()) {
                log.debug("Declaring exchange: {}", exchangeConfig.getName());
                try {
                    channel.exchangeDeclare(
                        exchangeConfig.getName(),
                        exchangeConfig.getType(),
                        exchangeConfig.isDurable(),
                        exchangeConfig.isAutoDelete(),
                        null
                    );
                } catch (final IOException e) {
                    throw new RabbitMQSetupException("Could not declare exchange " + exchangeConfig.getName(), e);
                }
            }
            return connection;
        } catch(final RabbitMQSetupException e) {
            close();
            throw e;
        }
    }

    @Override
    public Channel createChannel() throws RabbitMQSetupException {
        try {
            return connection.createChannel();
        } catch (IOException e) {
            throw new RabbitMQSetupException("Could not create channel", e);
        }
    }

    @Override
    public Channel getChannel() {
        return channel;
    }

    @Override
    public void setExceptionHandler(ExceptionHandler handler) {
        exceptionHandler = handler;
    }

    @Override
    public void close() {
        if(channel != null){
            try {
                channel.close();
                channel = null;
            } catch (final IOException | TimeoutException e) {
                log.error("Could not close channel {}", channel, e);
            }
        }
        if(connection != null){
            try {
                connection.close();
                connection = null;
            } catch (final IOException e) {
                log.error("Could not close connection {}", connection, e);
            }
        }
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public void publish(String exchangeName, String key, Object message, AMQP.BasicProperties properties) throws IOException {
        publish(exchangeName, key, message, properties, this.channel);
    }

    /**
     *
     * @param message Object containing the information you want to transmit. Could be as simple as a single value, a Map, an Object, etc. This object will be serialized using Jackson, so Jackson Annotations will be respected
     * @param key String The routing key for outgoing message
     * @param properties BasicProperties Standard RabbitMQ Basic Properties
     * @param channel Channel The Channel to transmit on
     * @param exchangeName String The name of the exchange to transmit on
     */
    @Override
    public void publish(String exchangeName, String key, Object message, AMQP.BasicProperties properties, Channel channel) throws IOException {
        final String payload = OBJECT_MAPPER.writeValueAsString(message);
        log.info("Publishing to exchange {} on topic {} with message: {}", exchangeName, key, payload);
        channel.basicPublish(exchangeName, key, properties, payload.getBytes());
    }

    /**
     *
     * @param exchangeName The name of the exchange to publish on
     * @param key String The routing key to publish on
     * @param message Object representing the outgoing data. Will typically encapsulate some sort of query information
     * @param collectionClazz specified when the return data is a Collection. Typically a List
     * @param clazz Clazz The class of the expected return data
     * @param channel Channel Channel to broadcast on
     * @return Object Will be an instance of clazz
     * @throws IOException If unable to connect or bind the queuetion
     * @throws RPCTimeoutException on timeout
     */
    @Deprecated
    @Override
    public Object rpc(String exchangeName, String key, Object message, Class<? extends Collection> collectionClazz, Class clazz, Channel channel) throws IOException, RPCTimeoutException {
        return rpc(exchangeName, key, message, collectionClazz, clazz, channel, UUID.randomUUID().toString(), null);
    }



    /**
     *
     * @param exchangeName The name of the exchange to publish on
     * @param key String The routing key to publish on
     * @param message Object representing the outgoing data. Will typically encapsulate some sort of query information
     * @param clazz Clazz The class of the expected return data
     * @param channel Channel Channel to broadcast on
     * @return Object Will be an instance of clazz
     * @throws IOException If unable to connect or bind the queuetion
     * @throws RPCTimeoutException on timeout
     */
    @Deprecated
    @Override
    public Object rpc(String exchangeName, String key, Object message, Class clazz, Channel channel) throws IOException, RPCTimeoutException {
        return rpc(exchangeName, key, message, null, clazz, channel, UUID.randomUUID().toString(), null);
    }

    /**
     *
     * @param exchangeName The name of the exchange to publish on
     * @param key String The routing key to publish on
     * @param message Object representing the outgoing data. Will typically encapsulate some sort of query information
     * @param collectionClazz this will typically be a List
     * @param clazz Clazz The class of the expected return data
     * @param channel Channel Channel to broadcast on
     * @param rpcTimeout Integer in millis of a custom timeout for a particular RPC
     * @return Object Will be an instance of clazz
     * @throws IOException If unable to connect or bind the queuetion
     * @throws RPCTimeoutException on timeout
     */
    @Deprecated
    public Object rpc(String exchangeName, String key, Object message, Class<? extends Collection> collectionClazz, Class clazz, Channel channel, Integer rpcTimeout) throws IOException, RPCTimeoutException {
        return rpc(exchangeName, key, message, collectionClazz, clazz, channel, UUID.randomUUID().toString(), rpcTimeout);
    }

    /**
     *
     * @param exchangeName The name of the exchange to publish on
     * @param key String The routing key to publish on
     * @param message Object representing the outgoing data. Will typically encapsulate some sort of query information
     * @param clazz Clazz The class of the expected return data
     * @param channel Channel Channel to broadcas
     * @param traceId A unique identifier for tracing communications on
     * @param rpcTimeout Integer in millis of a custom timeout for a particular RPC
     * @return Object Will be an instance of clazz
     * @throws IOException If unable to connect or bind the queuetion
     * @throws RPCTimeoutException On timeout
     */
    @Deprecated
    @Override
    public Object rpc(String exchangeName, String key, Object message, Class<? extends Collection> collectionClazz, Class clazz, Channel channel, String traceId, Integer rpcTimeout) throws IOException, RPCTimeoutException {
        // to do an RPC (synchronous, in this case) in RabbitMQ, we must do the following:
        // 1. create a unique response queue for the rpc call
        // 2. create a new channel for the queue //todo: eventually make this optional
        // 3. define a response correlation id. create a basic properties object with the response id
        // 4. publish
        // 5. wait for the response on the unique queue. if timeout, prepare empty response
        // 6. destroy unique queue
        // 7. return response
        // Also, allow configuration for logging response times, or timeouts on rpc calls
        //
        //
        // Ok, furthermore, the RabbitMq java library has implementations of RPC and AsyncRPC on the channel class.
        // Assuming they do what I think they do, they would be amazing to use. However:
        // * I cannot find any documentation on how to use them, all searches for things like 'rabbitmq java client channel rpc' result in
        //      documentation about how to programatically do an rpc call (e.g. what we do here).
        // * The official java rabbitmq documentation also says to do what we do here.
        RpcStopWatch stopWatch = null;
        if (config.isLogRpcTime()) {
            stopWatch = new RpcStopWatch().start();
        }

        JavaType javaType;
        if (collectionClazz != null) {
            javaType = OBJECT_MAPPER.getTypeFactory().constructCollectionLikeType(collectionClazz, clazz);
        } else {
            javaType = OBJECT_MAPPER.getTypeFactory().constructType(clazz);
        }
        ObjectReader objectReader = OBJECT_MAPPER.readerFor(javaType);
        String replyQueueName = channel.queueDeclare("", false, false, true, null).getQueue();
        log.debug("Listening for rpc response on " + replyQueueName);
        try {
            QueueingConsumer consumer = new QueueingConsumer(channel);

            channel.queueBind(replyQueueName, exchangeName, replyQueueName);
            channel.basicConsume(replyQueueName, true, consumer);

            RabbitMQDeliveryDetails rpcDetails = buildRpcRabbitMQDeliveryDetails(exchangeName, key, replyQueueName, traceId, rpcTimeout);

            log.debug("Expiration for RPC: " + rpcDetails.getBasicProperties().getExpiration());

            publish(exchangeName, key, message, rpcDetails.getBasicProperties(), channel);
            log.debug("Waiting for rpc response delivery on " + key);

            QueueingConsumer.Delivery delivery = null;
            try {
                delivery = consumer.nextDelivery(chooseTimeout(rpcTimeout));
            } catch (InterruptedException e) {
                log.error("Thread interrupted while waiting for rpc response:", e);
            }

            if (delivery != null) {
                log.trace("RPC response received.");
                if (delivery.getProperties().getCorrelationId().equals(
                        rpcDetails.getBasicProperties().getCorrelationId())) {

                    log.trace("Correlation ids are equal.");
                    channel.basicCancel(consumer.getConsumerTag());
                } else {
                    log.warn("Correlation ids not equal! key: " + key);
                    return null;
                }
            } else {
                throw new RPCTimeoutException(key);
            }
            // we must clean up!

            if (config.isLogRpcTime() && stopWatch != null) {
                stopWatch.stopAndPublish(rpcDetails);
            }
            log.info("Received response to RPC on topic {}: {}", key, new String(delivery.getBody()));
            return objectReader.readValue(delivery.getBody());
        } finally {
            try {
                channel.queueUnbind(replyQueueName, exchangeName, replyQueueName);
            } finally {
                channel.queueDelete(replyQueueName);
            }
        }
    }

    @Override
    @Deprecated
    public Optional<Object> optionalRpc(String exchangeName, String key, Object message, Class clazz, Channel channel) throws IOException, RPCTimeoutException {
            return Optional.ofNullable(rpc(exchangeName, key, message, clazz, channel));
    }

    @Override
    @Deprecated
    public Optional<Object> optionalRpc(String exchangeName, String key, Object message, Class<? extends Collection> collectionClazz, Class clazz, Channel channel) throws IOException, RPCTimeoutException {
        return Optional.ofNullable(rpc(exchangeName, key, message, collectionClazz, clazz, channel));
    }

    @Override
    @Deprecated
    public Optional<Object> optionalRpc(String exchangeName, String key, Object message, Class<? extends Collection> collectionClazz, Class clazz, Channel channel, String traceId, Integer rpcTimeout) throws IOException, RPCTimeoutException {
        return Optional.ofNullable(rpc(exchangeName, key, message, collectionClazz, clazz, channel, traceId, rpcTimeout));
    }

    private int chooseTimeout(Integer timeoutOverride) {
        if(timeoutOverride != null) {
            return timeoutOverride;
        } else {
            return config.getRpcTimeout();
        }
    }

    private RabbitMQDeliveryDetails buildRpcRabbitMQDeliveryDetails(String exchangeName, String key, String replyQueueName, String traceId, Integer rpcTimeout ) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(RpcStopWatch.TRACE_ID, traceId);
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .correlationId(UUID.randomUUID().toString())
                .replyTo(replyQueueName)
                .headers(headers)
                .build();

        if(rpcTimeout != null) {
            props = props.builder().expiration(rpcTimeout.toString()).build();
        }

        return new RabbitMQDeliveryDetails(new Envelope(0, true, exchangeName, key), props, "temp-rpc");
    }

    private ConnectionFactory baseConnectionFactory() {
        final ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        connectionFactory.setNetworkRecoveryInterval(NETWORK_RECOVERY_INTERVAL);
        connectionFactory.setTopologyRecoveryEnabled(true);
        connectionFactory.setExceptionHandler(exceptionHandler);
        return connectionFactory;
    }

}
