package dk.trustworks.servicemanager;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.util.AttachmentKey;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceProvider;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Created by hans on 22/03/15.
 */
public class ClientProxyZookeeper implements ProxyClient {

    final private Cache<String, HttpServerExchange> serviceCache = CacheBuilder.newBuilder().expireAfterAccess(2, TimeUnit.MINUTES).maximumSize(100).build();

    private final AttachmentKey<ClientConnection> clientAttachmentKey = AttachmentKey.create(ClientConnection.class);
    private final UndertowClient client;

    private static final ProxyTarget TARGET = new ProxyTarget() {};

    private final ServiceProvider serviceProvider;

    public ClientProxyZookeeper(String service) {
        client = UndertowClient.getInstance();

        CuratorFramework curatorFramework = CuratorFrameworkFactory.newClient("localhost:2181", new RetryNTimes(5, 1000)); //ip-172-31-20-150.eu-central-1.compute.internal
        curatorFramework.start();

        try {
            ServiceDiscovery<Object> serviceDiscovery = ServiceDiscoveryBuilder
                    .builder(Object.class)
                    .basePath("trustworks")
                    .client(curatorFramework).build();
            serviceDiscovery.start();
            serviceProvider = serviceDiscovery
                    .serviceProviderBuilder()
                    .serviceName(service)
                    .build();
            serviceProvider.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange) {
        return TARGET;
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit) {
        ClientConnection existing = exchange.getConnection().getAttachment(clientAttachmentKey);
        URI uri = getUri(exchange);
        HttpServerExchange cachedExchange = serviceCache.getIfPresent(exchange.getRequestURI() + "-" + exchange.getQueryString());
        if (existing != null) {
            if (existing.isOpen()) {
                callback.completed(exchange, new ProxyConnection(existing, uri.getPath() == null ? "/" : uri.getPath()));
                return;
            } else {
                exchange.getConnection().removeAttachment(clientAttachmentKey);
            }
        }
        client.connect(new ConnectNotifier(callback, exchange), uri, exchange.getIoThread(), exchange.getConnection().getBufferPool(), OptionMap.EMPTY);
    }

    private URI getUri(HttpServerExchange exchange) {
        try {
            URI uri = new URI(serviceProvider.getInstance().buildUriSpec());
            return uri;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final class ConnectNotifier implements ClientCallback<ClientConnection> {
        private final ProxyCallback<ProxyConnection> callback;
        private final HttpServerExchange exchange;

        private ConnectNotifier(ProxyCallback<ProxyConnection> callback, HttpServerExchange exchange) {
            this.callback = callback;
            this.exchange = exchange;
        }

        @Override
        public void completed(final ClientConnection connection) {
            final ServerConnection serverConnection = exchange.getConnection();
            serverConnection.putAttachment(clientAttachmentKey, connection);
            serverConnection.addCloseListener(serverConnection1 -> IoUtils.safeClose(connection));
            connection.getCloseSetter().set(channel -> serverConnection.removeAttachment(clientAttachmentKey));
            String path = getUri(exchange).getPath();
            callback.completed(exchange, new ProxyConnection(connection, path == null ? "/" : path));
        }

        @Override
        public void failed(IOException e) {
            callback.failed(exchange);
        }
    }
}
