package com.github.alemures.fasttcp;

import com.github.alemures.fasttcp.futures.FutureCallback;
import com.github.alemures.fasttcp.futures.FutureExecutor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Socket {
    public static final String EVENT_CONNECT = "connect";
    public static final String EVENT_SOCKET_CONNECT = "socket_connect";
    public static final String EVENT_END = "end";
    public static final String EVENT_CLOSE = "close";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_RECONNECTING = "reconnecting";

    private static final int MAX_MESSAGE_ID = Integer.MAX_VALUE;

    private String host;
    private int port;
    private Opts opts;

    private ExecutorService executorService;
    private FutureExecutor futureExecutor;

    private java.net.Socket socket;
    private boolean connected;
    private boolean manuallyClosed;
    private int messageId = 1;
    private BufferedInputStream bufferedInputStream;
    private BufferedOutputStream bufferedOutputStream;

    private Emitter emitter = new Emitter();

    private LinkedList<byte[]> queue;
    private Map<Integer, Callback> acks = new HashMap<>();

    public String id;

    public Socket(String host, int port) {
        this(host, port, new Opts());
    }

    public Socket(String host, int port, Opts opts) {
        this.host = host;
        this.port = port;
        this.opts = opts;

        if (opts.useQueue) {
            queue = new LinkedList<>();
        }
    }

    public void connect() {
        if (!connected) {
            manuallyClosed = false;
            executorService = Executors.newSingleThreadExecutor();
            futureExecutor = new FutureExecutor(executorService);
            futureExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    socket = new java.net.Socket(host, port);
                    bufferedInputStream = new BufferedInputStream(socket.getInputStream());
                    bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
                    new SocketReceiverThread().start();
                    return null;
                }
            }).addCallback(new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    connected = true;

                    try {
                        flushQueue();
                    } catch (Exception e) {
                        emitter.emit(EVENT_ERROR, e);
                    }

                    emitter.emit(EVENT_SOCKET_CONNECT);
                }

                @Override
                public void onFailure(Throwable failure) {
                    emitter.emit(EVENT_ERROR, failure);
                    emitter.emit(EVENT_CLOSE);

                    if (opts.reconnect && !manuallyClosed) {
                        reconnect();
                    }
                }
            });
        }
    }

    public void end() {
        destroy();
    }

    public void destroy() {
        if (connected) {
            manuallyClosed = true;
            futureExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    socket.close();
                    return null;
                }
            }).addCallback(new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    emitter.emit(EVENT_END);
                    emitter.emit(EVENT_CLOSE);

                    executorService.shutdown();
                }

                @Override
                public void onFailure(Throwable failure) {
                    emitter.emit(EVENT_ERROR, failure);
                }
            });
        }
    }

    public void emit(String event, String data) throws IOException {
        send(event, data.getBytes(), Serializer.MT_DATA, Serializer.DT_STRING);
    }

    public void emit(String event, String data, Callback cb) throws IOException {
        send(event, data.getBytes(), Serializer.MT_DATA_WITH_ACK, Serializer.DT_STRING, cb);
    }

    public void emit(String event, long data) throws IOException {
        send(event, Utils.int48ToByteArray(data), Serializer.MT_DATA, Serializer.DT_INT);
    }

    public void emit(String event, long data, Callback cb) throws IOException {
        send(event, Utils.int48ToByteArray(data), Serializer.MT_DATA_WITH_ACK, Serializer.DT_INT, cb);
    }

    public void emit(String event, double data) throws IOException {
        send(event, Utils.doubleToByteArray(data), Serializer.MT_DATA, Serializer.DT_DOUBLE);
    }

    public void emit(String event, double data, Callback cb) throws IOException {
        send(event, Utils.doubleToByteArray(data), Serializer.MT_DATA_WITH_ACK, Serializer.DT_DOUBLE, cb);
    }

    public void emit(String event, JSONObject data) throws IOException {
        send(event, data.toString().getBytes(), Serializer.MT_DATA, Serializer.DT_OBJECT);
    }

    public void emit(String event, JSONObject data, Callback cb) throws IOException {
        send(event, data.toString().getBytes(), Serializer.MT_DATA_WITH_ACK, Serializer.DT_OBJECT, cb);
    }

    public void emit(String event, JSONArray data) throws IOException {
        send(event, data.toString().getBytes(), Serializer.MT_DATA, Serializer.DT_OBJECT);
    }

    public void emit(String event, JSONArray data, Callback cb) throws IOException {
        send(event, data.toString().getBytes(), Serializer.MT_DATA_WITH_ACK, Serializer.DT_OBJECT, cb);
    }

    public void emit(String event, byte[] data) throws IOException {
        send(event, data, Serializer.MT_DATA, Serializer.DT_BUFFER);
    }

    public void emit(String event, byte[] data, Callback cb) throws IOException {
        send(event, data, Serializer.MT_DATA_WITH_ACK, Serializer.DT_BUFFER, cb);
    }

    public void join(String room) throws IOException {
        send("", room.getBytes(), Serializer.MT_JOIN_ROOM, Serializer.DT_STRING);
    }

    public void leave(String room) throws IOException {
        send("", room.getBytes(), Serializer.MT_LEAVE_ROOM, Serializer.DT_STRING);
    }

    public void leaveAll() throws IOException {
        send("", new byte[0], Serializer.MT_LEAVE_ALL_ROOMS, Serializer.DT_STRING);
    }

    public int getVersion() {
        return Serializer.VERSION;
    }

    public void on(String event, Emitter.Listener fn) {
        emitter.on(event, fn);
    }

    public void once(String event, Emitter.Listener fn) {
        emitter.once(event, fn);
    }

    public void off(String event, Emitter.Listener fn) {
        emitter.off(event, fn);
    }

    public void off(String event) {
        emitter.off(event);
    }

    public void off() {
        emitter.off();
    }

    private void flushQueue() throws IOException {
        if (!opts.useQueue || queue.size() == 0) {
            return;
        }

        for (byte[] data : queue) {
            bufferedOutputStream.write(data);
        }
        bufferedOutputStream.flush();

        queue.clear();
    }

    private void reconnect() {
        futureExecutor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Thread.sleep(opts.reconnectInterval);
                emitter.emit(EVENT_RECONNECTING);
                connect();
                return null;
            }
        });
    }

    private void send(String event, byte[] data, byte mt, byte dt) throws IOException {
        send(event, data, mt, dt, -1, null);
    }

    private void send(String event, byte[] data, byte mt, byte dt, int messageId) throws IOException {
        send(event, data, mt, dt, messageId, null);
    }

    private void send(String event, byte[] data, byte mt, byte dt, Callback cb) throws IOException {
        send(event, data, mt, dt, -1, cb);
    }

    private void send(String event, byte[] data, byte mt, byte dt, int messageId, Callback cb) throws IOException {
        messageId = messageId > 0 ? messageId : nextMessageId();

        if (cb != null) {
            acks.put(messageId, cb);
        }

        byte[] message = Serializer.serialize(event.getBytes(), data, mt, dt, messageId);

        if (connected) {
            bufferedOutputStream.write(message);
            bufferedOutputStream.flush();
        } else if (opts.useQueue) {
            if (queue.size() + 1 > opts.queueSize) {
                queue.poll();
            }
            queue.offer(message);
        }
    }

    private int nextMessageId() {
        if (++messageId >= MAX_MESSAGE_ID) {
            messageId = 1;
        }

        return messageId;
    }

    private Ack createAck(Message message) {
        return new Ack() {
            @Override
            public void send(String data) {
                send(data.getBytes(), Serializer.DT_STRING);
            }

            @Override
            public void send(long data) {
                send(Utils.int48ToByteArray(data), Serializer.DT_INT);
            }

            @Override
            public void send(double data) {
                send(Utils.doubleToByteArray(data), Serializer.DT_DOUBLE);
            }

            @Override
            public void send(JSONObject data) {
                send(data.toString().getBytes(), Serializer.DT_OBJECT);
            }

            @Override
            public void send(byte[] data) {
                send(data, Serializer.DT_BUFFER);
            }

            private void send(byte[] data, byte dt) {
                try {
                    Socket.this.send("", data, Serializer.MT_ACK, dt, message.messageId);
                } catch (IOException e) {
                    emitter.emit(EVENT_ERROR, e);
                }
            }
        };
    }

    private class SocketReceiverThread extends Thread {
        private Reader reader = new Reader();
        private byte[] chunk = new byte[1024];

        @Override
        public void run() {
            int bytesRead;
            try {
                while ((bytesRead = bufferedInputStream.read(chunk)) != -1) {
                    ArrayList<byte[]> buffers = reader.read(chunk, bytesRead);
                    for (byte[] buffer : buffers) {
                        process(Serializer.deserialize(buffer));
                    }
                }

            } catch (IOException e) {
                emitter.emit(EVENT_ERROR, e);
            }

            if (opts.reconnect && !manuallyClosed) {
                reconnect();
            }

            emitter.emit(EVENT_END);
            emitter.emit(EVENT_CLOSE);

            connected = false;
        }

        private void process(final Message message) {
            switch (message.mt) {
                case Serializer.MT_DATA:
                    emitter.emit(message.event, message.data);
                    break;
                case Serializer.MT_DATA_WITH_ACK:
                    emitter.emit(message.event, message.data, createAck(message));
                    break;
                case Serializer.MT_ACK:
                    if (acks.containsKey(message.messageId)) {
                        acks.get(message.messageId).call(message.data);
                        acks.remove(message.messageId);
                    }
                    break;
                case Serializer.MT_REGISTER:
                    id = (String) message.data;
                    emitter.emit(EVENT_CONNECT);
                    break;
                default:
                    throw new RuntimeException("Not implemented message type " + message.mt);
            }
        }
    }

    public interface Ack {
        void send(String data);
        void send(long data);
        void send(double data);
        void send(JSONObject data);
        void send(byte[] data);
    }

    public interface Callback {
        void call(Object data);
    }

    public static class Opts {
        private boolean reconnect = true;
        private long reconnectInterval = 1000;
        private boolean useQueue = true;
        private int queueSize = 1024;

        public Opts reconnect(boolean reconnect) {
            this.reconnect = reconnect;
            return this;
        }

        public Opts reconnectInterval(long reconnectInterval) {
            this.reconnectInterval = reconnectInterval;
            return this;
        }

        public Opts useQueue(boolean useQueue) {
            this.useQueue = useQueue;
            return this;
        }

        public Opts queueSize(int queueSize) {
            this.queueSize = queueSize;
            return this;
        }
    }
}
