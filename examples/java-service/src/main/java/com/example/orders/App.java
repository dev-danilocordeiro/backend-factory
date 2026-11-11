package com.example.orders;

import com.example.orders.api.CustomerController;
import com.example.orders.api.OrderController;
import com.example.orders.repository.InMemoryOrderRepository;
import com.example.orders.service.OrderService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** Wires the layers and starts the HTTP server. */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        createServer(port).start();
        System.out.println("listening on " + port);
    }

    /** Creates an unstarted server whose routes share one OrderService. */
    static HttpServer createServer(int port) throws IOException {
        OrderService service = new OrderService(new InMemoryOrderRepository());
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/orders", new OrderController(service));
        server.createContext("/customers", new CustomerController(service));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        return server;
    }
}
