package dk.trustworks.servicemanager;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.proxy.SimpleProxyClientProvider;
import org.apache.curator.x.discovery.ServiceProvider;

import java.net.URI;

/**
 * Created by hans on 16/03/15.
 */
public class ServiceApplication {

    static ServiceProvider serviceProvider;

    public static void main(String[] args) throws Exception {
        new ServiceApplication();
    }

    public ServiceApplication() throws Exception {
        System.out.println("Application");
        ClientProxyZookeeper userManagerProxy = new ClientProxyZookeeper("userservice");
        ClientProxyZookeeper clientManagerProxy = new ClientProxyZookeeper("clientservice");
        ClientProxyZookeeper timeManagerProxy = new ClientProxyZookeeper("timeservice");
        ClientProxyZookeeper biManagerProxy = new ClientProxyZookeeper("biservice");

        SimpleProxyClientProvider provider = new SimpleProxyClientProvider(new URI("http://localhost:8081"));

        Undertow reverseProxy = Undertow.builder()
                .addHttpListener(8080, "localhost").addHttpListener(80, "52.28.152.190")
                .setIoThreads(4)
                .setHandler(Handlers.path()
                        .addPrefixPath("/userservice", new ProxyHandler(userManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                        .addPrefixPath("/clientservice", new ProxyHandler(clientManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                        .addPrefixPath("/biservice", new ProxyHandler(biManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                        .addPrefixPath("/timeservice", new ProxyHandler(timeManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                        .addPrefixPath("/", new ProxyHandler(provider, 30000, ResponseCodeHandler.HANDLE_404)))
                .build();
        try {
            reverseProxy.start();
        } catch (RuntimeException e) {
            reverseProxy = Undertow.builder()
                    .addHttpListener(9090, "localhost")
                    .setIoThreads(4)
                    .setHandler(Handlers.path()
                            .addPrefixPath("/userservice", new ProxyHandler(userManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                            .addPrefixPath("/clientservice", new ProxyHandler(clientManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                            .addPrefixPath("/biservice", new ProxyHandler(biManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                            .addPrefixPath("/timeservice", new ProxyHandler(timeManagerProxy, 30000, ResponseCodeHandler.HANDLE_404))
                            .addPrefixPath("/", new ProxyHandler(provider, 30000, ResponseCodeHandler.HANDLE_404)))
                    .build();
            reverseProxy.start();
        }

    }
}
