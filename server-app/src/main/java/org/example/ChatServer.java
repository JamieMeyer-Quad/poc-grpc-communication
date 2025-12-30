package org.example;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import com.example.chat.ChatServiceGrpc;
import com.example.chat.MessageRequest;
import com.example.chat.MessageResponse;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ChatServer {

    private static final List<String> messageLog = Collections.synchronizedList(new ArrayList<>());

    private record Client(String name, StreamObserver<MessageResponse> observer) {
    }

    private static final ConcurrentHashMap<Long, Client> sessions = new ConcurrentHashMap<>();
    private static final AtomicLong nextId = new AtomicLong(1);

    private static void broadcastSystemMessage(String text) {
        String timestamp = Instant.now().toString();
        MessageResponse systemMsg = MessageResponse.newBuilder()
                .setSender("[System]")
                .setContent(text)
                .setTimestamp(timestamp)
                .build();

        messageLog.add("[" + timestamp + "] " + text);
        sessions.forEach((id, client) -> {
            try {
                client.observer.onNext(systemMsg);
            } catch (Exception e) {
                sessions.remove(id);
            }
        });
    }

    private static class ChatServiceImpl extends ChatServiceGrpc.ChatServiceImplBase {
        @Override
        public StreamObserver<MessageRequest> chat(StreamObserver<MessageResponse> responseObserver) {
            AtomicLong clientIdRef = new AtomicLong(-1);
            AtomicLong firstMessageHandled = new AtomicLong(0);

            return new StreamObserver<>() {
                @Override
                public void onNext(MessageRequest request) {
                    if (firstMessageHandled.compareAndSet(0, 1)) {
                        long clientId = nextId.getAndIncrement();
                        clientIdRef.set(clientId);
                        String senderName = request.getSender();
                        sessions.put(clientId, new Client(senderName, responseObserver));
                        broadcastSystemMessage(senderName + " joined the chat.");
                    }

                    String timestamp = Instant.now().toString();
                    String logEntry = "[" + timestamp + "] " + request.getSender() + ": " + request.getContent();
                    messageLog.add(logEntry);
                    if (messageLog.size() > 200) {
                        messageLog.removeFirst();
                    }

                    MessageResponse msg = MessageResponse.newBuilder()
                            .setSender(request.getSender())
                            .setContent(request.getContent())
                            .setTimestamp(timestamp)
                            .build();

                    long senderId = clientIdRef.get();

                    sessions.forEach((id, client) -> {
                        if (id == senderId) {
                            return;
                        }
                        try {
                            client.observer.onNext(msg);
                        } catch (Exception e) {
                            sessions.remove(id);
                            broadcastSystemMessage(client.name + " left the chat.");
                        }
                    });
                }

                @Override
                public void onError(Throwable t) {
                    cleanup();
                }

                @Override
                public void onCompleted() {
                    cleanup();
                }

                private void cleanup() {
                    long clientId = clientIdRef.get();
                    if (clientId != -1) {
                        Client client = sessions.remove(clientId);
                        if (client != null) {
                            broadcastSystemMessage(client.name + " left the chat.");
                        }
                    }
                }
            };
        }
    }

    public static void main(String[] args) throws Exception {
        Server grpcServer = NettyServerBuilder.forPort(50051)
                .addService(new ChatServiceImpl())
                .build()
                .start();

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(8080), 0);
        httpServer.createContext("/messages", exchange -> {
            String body = String.join("\n", messageLog) + "\n";
            exchange.sendResponseHeaders(200, body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        httpServer.start();

        System.out.println("gRPC Chat Server started on port 50051");
        System.out.println("HTTP log available at http://localhost:8080/messages");

        grpcServer.awaitTermination();
    }
}