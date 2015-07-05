package dk.trustworks.servicemanager;

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
import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.Channel;
import java.util.concurrent.TimeUnit;

/**
 * Created by hans on 22/03/15.
 */
public class ClientProxyZookeeper implements ProxyClient {
    //private final URI uri;
    private final AttachmentKey<ClientConnection> clientAttachmentKey = AttachmentKey.create(ClientConnection.class);
    private final UndertowClient client;

    private static final ProxyTarget TARGET = new ProxyTarget() {};

    private ServiceProvider serviceProvider;

    public ClientProxyZookeeper(String service) {
        System.out.println("ClientProxyZookeeper");
        client = UndertowClient.getInstance();

        CuratorFramework curatorFramework = CuratorFrameworkFactory.newClient("ip-172-31-20-150.eu-central-1.compute.internal:2181", new RetryNTimes(5, 1000));
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
            e.printStackTrace();
        }
    }

    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange) {
        return TARGET;
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit) {
        System.out.println("getConnection");
        ClientConnection existing = exchange.getConnection().getAttachment(clientAttachmentKey);
        URI uri = getUri(exchange);
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
        System.out.println("getURI for service: "+exchange.getRequestPath().split("/")[1]);
        URI uri = null;
        try {
            uri = new URI(serviceProvider.getInstance().buildUriSpec());
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("returning: "+uri.toString());
        return uri;
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
            System.out.println("completed");
            final ServerConnection serverConnection = exchange.getConnection();
            //we attach to the connection so it can be re-used
            serverConnection.putAttachment(clientAttachmentKey, connection);
            serverConnection.addCloseListener(new ServerConnection.CloseListener() {
                @Override
                public void closed(ServerConnection serverConnection) {
                    IoUtils.safeClose(connection);
                }
            });
            connection.getCloseSetter().set(new ChannelListener<Channel>() {
                @Override
                public void handleEvent(Channel channel) {
                    serverConnection.removeAttachment(clientAttachmentKey);
                }
            });
            String path = getUri(exchange).getPath();
            callback.completed(exchange, new ProxyConnection(connection, path == null ? "/" : path));
        }

        @Override
        public void failed(IOException e) {
            callback.failed(exchange);
        }
    }
}
