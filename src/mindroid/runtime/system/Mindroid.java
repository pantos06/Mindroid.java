/*
 * Copyright (C) 2018 E.S.R.Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mindroid.runtime.system;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mindroid.os.Binder;
import mindroid.os.IBinder;
import mindroid.os.IInterface;
import mindroid.os.Parcel;
import mindroid.os.RemoteException;
import mindroid.util.Log;
import mindroid.util.concurrent.Executors;
import mindroid.util.concurrent.Promise;

public class Mindroid extends Plugin {
    private static final String LOG_TAG = "Mindroid";
    private static final String TIMEOUT = "timeout";
    private static final long DEFAULT_TRANSACTION_TIMEOUT = 10000;
    private static final ScheduledThreadPoolExecutor sExecutor;

    private Configuration.Plugin mConfiguration;
    private Server mServer;
    private Map<Integer, Client> mClients = new HashMap<>();
    private final Map<Integer, Map<Long, WeakReference<IBinder>>> mProxies = new HashMap<>();

    static {
        sExecutor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("ThreadPoolExecutorDaemon");
                return t;
            }
        });
        sExecutor.setKeepAliveTime(10, TimeUnit.SECONDS);
        sExecutor.allowCoreThreadTimeOut(true);
        sExecutor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void start() {
        int nodeId = mRuntime.getNodeId();
        Configuration configuration = mRuntime.getConfiguration();
        if (configuration != null) {
            mConfiguration = configuration.plugins.get("mindroid");
            if (mConfiguration != null) {
                Set<Long> ids = new HashSet<>();
                for (Configuration.Service service : mConfiguration.services.values()) {
                    if (service.node.id == nodeId) {
                        long id = ((long) nodeId << 32) | (service.id & 0xFFFFFFFFL);
                        ids.add(id);
                    } else {
                        try {
                            URI descriptor = new URI(service.interfaceDescriptor);
                            URI uri = new URI("mindroid", service.node.id + "." + service.id, "/if=" + descriptor.getPath().substring(1), null, null);
                            IBinder proxy = new Binder.Proxy(uri);
                            mRuntime.addService(new URI("mindroid", service.name, null, null, null), proxy);
                        } catch (Exception e) {
                            Log.println('E', LOG_TAG, "Binder proxy registration failed: " + service.name, e);
                        }
                    }
                }
                mRuntime.addIds(ids);

                Configuration.Node node = mConfiguration.nodes.get(nodeId);
                try {
                    mServer = new Server(node.uri);
                } catch (IOException e) {
                    Log.println('E', LOG_TAG, e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void stop() {
        mServer.shutdown();
    }

    @Override
    public void attachBinder(Binder binder) {
    }

    @Override
    public void detachBinder(long id) {
    }

    @Override
    public synchronized void attachProxy(long proxyId, Binder.Proxy proxy) {
        int nodeId = (int) ((proxy.getId() >> 32) & 0xFFFFFFFFL);
        if (!mProxies.containsKey(nodeId)) {
            mProxies.put(nodeId, new HashMap<>());
        }
        mProxies.get(nodeId).put(proxyId, new WeakReference<>(proxy));
    }

    @Override
    public synchronized void detachProxy(long proxyId, long binderId) {
        // TODO: Lazy connection shutdown for clients without proxies.
//        int nodeId = (int) ((binderId >> 32) & 0xFFFFFFFFL);
//        if (mProxies.containsKey(nodeId)) {
//            Map<Long, WeakReference<IBinder>> proxies = mProxies.get(nodeId);
//            proxies.remove(proxyId);
//            if (proxies.isEmpty()) {
//                mProxies.remove(nodeId);
//                Client client = mClients.get(nodeId);
//                if (client != null) {
//                    client.shutdown();
//                    mClients.remove(nodeId);
//                }
//            }
//        } else {
//            Client client = mClients.get(nodeId);
//            if (client != null) {
//                client.shutdown();
//                mClients.remove(nodeId);
//            }
//        }
    }

    @Override
    public Binder getStub(Binder service) {
        return null;
    }

    @Override
    public IInterface getProxy(IBinder binder) {
        return null;
    }

    @Override
    public Promise<Parcel> transact(IBinder binder, int what, Parcel data, int flags) throws RemoteException {
        int nodeId = (int) ((binder.getId() >> 32) & 0xFFFFFFFFL);
        Client client = mClients.get(nodeId);
        if (client == null) {
            Configuration.Node node;
            if (mConfiguration != null && (node = mConfiguration.nodes.get(nodeId)) != null) {
                if (!mClients.containsKey(nodeId)) {
                    try {
                        mClients.put(nodeId, new Client(node.id, node.uri));
                    } catch (IOException e) {
                        throw new RemoteException("Binder transaction failure");
                    }
                }
                client = mClients.get(nodeId);
            } else {
                throw new RemoteException("Binder transaction failure");
            }
        }
        return client.transact(binder, what, data, flags);
    }

    public void onShutdown(Client client) {
        mClients.remove(client.getNodeId());
    }

    private static class Message {
        public static final int MESSAGE_TYPE_TRANSACTION = 1;
        public static final int MESSAGE_TYPE_EXCEPTION_TRANSACTION = 2;

        private Message(int type, String uri, int transactionId, int what, byte[] data) {
            this.type = type;
            this.uri = uri;
            this.transactionId = transactionId;
            this.what = what;
            this.data = data;
        }

        public static Message newMessage(String uri, int transactionId, int what, byte[] data) {
            return new Message(MESSAGE_TYPE_TRANSACTION, uri, transactionId, what, data);
        }

        public static Message newExceptionMessage(String uri, int transactionId, int what, byte[] data) {
            return new Message(MESSAGE_TYPE_EXCEPTION_TRANSACTION, uri, transactionId, what, data);
        }

        int type;
        String uri;
        int transactionId;
        int what;
        byte[] data;
    }

    private class Server {
        private static final int SHUTDOWN_TIMEOUT = 10000; //ms
        private static final boolean DEBUG = false;

        private Thread mThread;
        private ServerSocket mServerSocket;
        private Set<Connection> mConnections = ConcurrentHashMap.newKeySet();

        public Server(String uri) throws IOException {
            URI url;
            try {
                url = new URI(uri);
            } catch (URISyntaxException e) {
                throw new IOException("Invalid URI: " + uri);
            }

            if ("tcp".equals(url.getScheme())) {
                try {
                    mServerSocket = new ServerSocket();
                    mServerSocket.setReuseAddress(true);
                    mServerSocket.bind(new InetSocketAddress(InetAddress.getByName(url.getHost()), url.getPort()));

                    mThread = new Thread("Server [" + mServerSocket.getLocalSocketAddress() + "]") {
                        public void run() {
                            while (!isInterrupted()) {
                                try {
                                    Socket socket = mServerSocket.accept();
                                    if (DEBUG) {
                                        Log.d(LOG_TAG, "New connection from " + socket.getRemoteSocketAddress());
                                    }
                                    mConnections.add(new Connection(socket));
                                } catch (IOException e) {
                                    Log.e(LOG_TAG, e.getMessage(), e);
                                }
                            }
                        }
                    };
                    mThread.start();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Cannot bind to server socket on port " + url.getPort());
                }
            } else {
                throw new IllegalArgumentException("Invalid URI scheme: " + url.getScheme());
            }
        }

        public void shutdown() {
            for (Connection connection : mConnections) {
                connection.shutdown();
            }
            try {
                mServerSocket.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "Cannot close server socket", e);
            }
            mThread.interrupt();
            try {
                mThread.join(SHUTDOWN_TIMEOUT);
            } catch (InterruptedException ignore) {
            }
            if (mThread.isAlive()) {
                Log.e(LOG_TAG, "Cannot shutdown server");
            }
        }

        private class Connection {
            private final byte[] BINDER_TRANSACTION_FAILURE = "Binder transaction failure".getBytes();
            private final Socket mSocket;
            private Reader mReader;
            private Writer mWriter;

            public Connection(Socket socket) {
                mSocket = socket;
                try {
                    mReader = new Reader("Server.Reader: " + mSocket.getLocalSocketAddress() + " << " + mSocket.getRemoteSocketAddress(),
                            socket.getInputStream());
                    mWriter = new Writer("Server.Writer: " + mSocket.getLocalSocketAddress() + " >> " + mSocket.getRemoteSocketAddress(),
                            socket.getOutputStream());
                    mReader.start();
                    mWriter.start();
                } catch (IOException e) {
                    Log.d(LOG_TAG, "Failed to set up connection", e);
                    shutdown();
                }
            }

            void shutdown() {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Disconnecting from " + mSocket.getRemoteSocketAddress());
                }
                mConnections.remove(this);

                sExecutor.execute(() -> {
                    try {
                        mSocket.close();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "Cannot close socket", e);
                    }
                    if (mReader != null) {
                        try {
                            if (DEBUG) {
                                Log.d(LOG_TAG, "Shutting down reader");
                            }
                            mReader.shutdown();
                            if (DEBUG) {
                                Log.d(LOG_TAG, "Reader has been shut down");
                            }
                            mReader = null;
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Cannot shutdown reader", e);
                        }
                    }
                    if (mWriter != null) {
                        try {
                            if (DEBUG) {
                                Log.d(LOG_TAG, "Shutting down writer");
                            }
                            mWriter.shutdown();
                            if (DEBUG) {
                                Log.d(LOG_TAG, "Writer has been shut down");
                            }
                            mWriter = null;
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Cannot shutdown writer", e);
                        }
                    }
                });
            }

            private class Reader extends Thread {
                private final DataInputStream mInputStream;

                public Reader(String name, InputStream inputStream) {
                    super(name);
                    mInputStream = new DataInputStream(inputStream);
                }

                void shutdown() {
                    interrupt();
                    try {
                        try {
                            mInputStream.close();
                        } catch (IOException ignore) {
                        }
                        join(SHUTDOWN_TIMEOUT);
                        if (isAlive()) {
                            Log.e(LOG_TAG, "Cannot shutdown reader");
                        }
                    } catch (InterruptedException ignore) {
                    }
                }

                public void run() {
                    while (!isInterrupted()) {
                        try {
                            int type = mInputStream.readInt();
                            String uri = mInputStream.readUTF();
                            int transactionId = mInputStream.readInt();
                            int what = mInputStream.readInt();
                            int size = mInputStream.readInt();
                            byte[] data = new byte[size];
                            mInputStream.readFully(data, 0, size);

                            if (type == Message.MESSAGE_TYPE_TRANSACTION) {
                                try {
                                    IBinder binder = mRuntime.getBinder(URI.create(uri));
                                    if (binder != null) {
                                        Promise<Parcel> result = binder.transact(what, Parcel.obtain(data), 0);
                                        if (result != null) {
                                            result.then((value, exception) -> {
                                                if (exception == null) {
                                                    mWriter.write(Message.newMessage(uri, transactionId, what, value.toByteArray()));
                                                } else {
                                                    mWriter.write(Message.newExceptionMessage(uri, transactionId, what, BINDER_TRANSACTION_FAILURE));
                                                }
                                            });
                                        }
                                    } else {
                                        mWriter.write(Message.newExceptionMessage(uri, transactionId, what, BINDER_TRANSACTION_FAILURE));
                                    }
                                } catch (IllegalArgumentException e) {
                                    Log.e(LOG_TAG, e.getMessage(), e);
                                    mWriter.write(Message.newExceptionMessage(uri, transactionId, what, BINDER_TRANSACTION_FAILURE));
                                } catch (RemoteException e) {
                                    Log.e(LOG_TAG, e.getMessage(), e);
                                    mWriter.write(Message.newExceptionMessage(uri, transactionId, what, BINDER_TRANSACTION_FAILURE));
                                }
                            } else {
                                Log.e(LOG_TAG, "Invalid message type: " + type);
                            }
                        } catch (IOException e) {
                            if (DEBUG) {
                                Log.e(LOG_TAG, e.getMessage(), e);
                            }
                            Connection.this.shutdown();
                            break;
                        }
                    }

                    if (DEBUG) {
                        Log.d(LOG_TAG, "Reader is terminating");
                    }
                }
            };

            private class Writer extends Thread {
                private final LinkedList<Message> mQueue = new LinkedList<>();
                private final DataOutputStream mOutputStream;

                public Writer(String name, OutputStream outputStream) {
                    super(name);
                    mOutputStream = new DataOutputStream(outputStream);
                }

                public void shutdown() throws IOException {
                    interrupt();
                    try {
                        try {
                            mOutputStream.close();
                        } catch (IOException ignore) {
                        }
                        join(SHUTDOWN_TIMEOUT);
                        if (isAlive()) {
                            Log.e(LOG_TAG, "Cannot shutdown writer");
                        }
                    } catch (InterruptedException ignore) {
                    }
                }

                public void write(Message message) {
                    synchronized (mQueue) {
                        mQueue.add(message);
                        mQueue.notify();
                    }
                }

                public void run() {
                    Message message;

                    while (!isInterrupted()) {
                        synchronized (mQueue) {
                            try {
                                while (mQueue.isEmpty()) {
                                    mQueue.wait();
                                }
                            } catch (InterruptedException e) {
                                break;
                            }

                            message = mQueue.get(0);
                            mQueue.remove(0);
                        }

                        try {
                            mOutputStream.writeInt(message.type);
                            mOutputStream.writeUTF(message.uri);
                            mOutputStream.writeInt(message.transactionId);
                            mOutputStream.writeInt(message.what);
                            mOutputStream.writeInt(message.data.length);
                            mOutputStream.write(message.data);
                            mOutputStream.flush();
                        } catch (IOException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                            Connection.this.shutdown();
                            break;
                        }
                    }

                    if (DEBUG) {
                        Log.d(LOG_TAG, "Writer is terminating");
                    }
                }
            };
        }
    }

    private class Client {
        private static final int SHUTDOWN_TIMEOUT = 10000; //ms
        private static final boolean DEBUG = false;

        private Thread mThread;
        private final Socket mSocket;
        private String mHost;
        private int mPort;
        private final Connection mConnection;
        private final AtomicInteger mTransactionIdGenerator = new AtomicInteger(1);
        private Map<Integer, Promise<Parcel>> mTransactions = new ConcurrentHashMap<>();
        private final int mNodeId;

        public Client(int nodeId, String uri) throws IOException {
            mNodeId = nodeId;

            try {
                URI url = new URI(uri);
                if (!"tcp".equals(url.getScheme())) {
                    throw new IllegalArgumentException("Invalid URI scheme: " + url.getScheme());
                }
                mHost = url.getHost();
                mPort = url.getPort();
                mSocket = new Socket();
                mConnection = new Connection();

                mThread = new Thread("Client [" + uri + "]") {
                    public void run() {
                        try {
                            mSocket.connect(new InetSocketAddress(mHost, mPort));
                            mConnection.start(mSocket);
                        } catch (IOException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                            shutdown();
                        }
                    }
                };
                mThread.start();
            } catch (URISyntaxException e) {
                throw new IOException("Invalid URI: " + uri);
            }
        }

        void shutdown() {
            Mindroid.this.onShutdown(this);

            sExecutor.execute(() -> {
                if (mConnection != null) {
                    mConnection.shutdown();
                }

                mThread.interrupt();
                try {
                    mThread.join(SHUTDOWN_TIMEOUT);
                } catch (InterruptedException ignore) {
                }
                if (mThread.isAlive()) {
                    Log.e(LOG_TAG, "Cannot shutdown client");
                }

                for (Promise<Parcel> promise : mTransactions.values()) {
                    promise.completeWith(new RemoteException());
                }
            });
        }

        public Promise<Parcel> transact(IBinder binder, int what, Parcel data, int flags) throws RemoteException {
            final int transactionId = mTransactionIdGenerator.getAndIncrement();
            Promise<Parcel> result;
            if (flags == Binder.FLAG_ONEWAY) {
                result = null;
            } else {
                final Promise<Parcel> promise = new Promise<>(Executors.SYNCHRONOUS_EXECUTOR);
                result = promise.orTimeout(data.getLongExtra(TIMEOUT, DEFAULT_TRANSACTION_TIMEOUT))
                .then((value, exception) -> {
                    mTransactions.remove(transactionId);
                });
                mTransactions.put(transactionId, promise);
            }
            mConnection.mWriter.write(Message.newMessage(binder.getUri().toString(), transactionId, what, data.toByteArray()));
            return result;
        }

        int getNodeId() {
            return mNodeId;
        }

        private class Connection {
            private Socket mSocket;
            private Reader mReader;
            private Writer mWriter;

            public Connection() {
                mReader = new Reader();
                mWriter = new Writer();
            }

            public void start(Socket socket) {
                mSocket = socket;
                try {
                    mReader.start("Client.Reader: " + mSocket.getLocalSocketAddress() + " << " + mSocket.getRemoteSocketAddress(),
                            socket.getInputStream());
                    mWriter.start("Client.Writer: " + mSocket.getLocalSocketAddress() + " >> " + mSocket.getRemoteSocketAddress(),
                            socket.getOutputStream());
                } catch (IOException e) {
                    Log.d(LOG_TAG, "Failed to set up connection", e);
                    Client.this.shutdown();
                }
            }

            void shutdown() {
                if (mSocket != null) {
                    try {
                        mSocket.close();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "Cannot close socket", e);
                    }
                }
                if (mReader != null) {
                    try {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Shutting down reader");
                        }
                        mReader.shutdown();
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Reader has been shut down");
                        }
                        mReader = null;
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Cannot shutdown reader", e);
                    }
                }
                if (mWriter != null) {
                    try {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Shutting down writer");
                        }
                        mWriter.shutdown();
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Writer has been shut down");
                        }
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Cannot shutdown writer", e);
                    }
                }
            }

            private class Reader extends Thread {
                private DataInputStream mInputStream;

                public void start(String name, InputStream inputStream) {
                    setName(name);
                    mInputStream = new DataInputStream(inputStream);
                    super.start();
                }

                void shutdown() {
                    interrupt();
                    try {
                        if (mInputStream != null) {
                            try {
                                mInputStream.close();
                            } catch (IOException ignore) {
                            }
                        }
                        join(SHUTDOWN_TIMEOUT);
                        if (isAlive()) {
                            Log.e(LOG_TAG, "Cannot shutdown reader");
                        }
                    } catch (InterruptedException ignore) {
                    }
                }

                public void run() {
                    while (!isInterrupted()) {
                        try {
                            int type = mInputStream.readInt();
                            String uri = mInputStream.readUTF();
                            int transactionId = mInputStream.readInt();
                            int what = mInputStream.readInt();
                            int size = mInputStream.readInt();
                            byte[] data = new byte[size];
                            mInputStream.readFully(data, 0, size);

                            final Promise<Parcel> promise = mTransactions.get(transactionId);
                            if (promise != null) {
                                mTransactions.remove(transactionId);
                                if (type == Message.MESSAGE_TYPE_TRANSACTION) {
                                    promise.complete(Parcel.obtain(data).asInput());
                                } else {
                                    promise.completeWith(new RemoteException());
                                }
                            } else {
                                Log.e(LOG_TAG, "Invalid transaction id: " + transactionId);
                            }
                        } catch (IOException e) {
                            if (DEBUG) {
                                Log.e(LOG_TAG, e.getMessage(), e);
                            }
                            Client.this.shutdown();
                            break;
                        }
                    }

                    if (DEBUG) {
                        Log.d(LOG_TAG, "Reader is terminating");
                    }
                }
            };

            private class Writer extends Thread {
                private final LinkedList<Message> mQueue = new LinkedList<>();
                private DataOutputStream mOutputStream;

                public void start(String name, OutputStream outputStream) {
                    setName(name);
                    mOutputStream = new DataOutputStream(outputStream);
                    super.start();
                }

                public void shutdown() throws IOException {
                    interrupt();
                    try {
                        if (mOutputStream != null) {
                            try {
                                mOutputStream.close();
                            } catch (IOException ignore) {
                            }
                        }
                        join(SHUTDOWN_TIMEOUT);
                        if (isAlive()) {
                            Log.e(LOG_TAG, "Cannot shutdown writer");
                        }
                    } catch (InterruptedException ignore) {
                    }
                }

                public void write(Message message) {
                    synchronized (mQueue) {
                        mQueue.add(message);
                        mQueue.notify();
                    }
                }

                public void run() {
                    Message message;

                    while (!isInterrupted()) {
                        synchronized (mQueue) {
                            try {
                                while (mQueue.isEmpty()) {
                                    mQueue.wait();
                                }
                            } catch (InterruptedException e) {
                                break;
                            }

                            message = mQueue.get(0);
                            mQueue.remove(0);
                        }

                        try {
                            mOutputStream.writeInt(message.type);
                            mOutputStream.writeUTF(message.uri);
                            mOutputStream.writeInt(message.transactionId);
                            mOutputStream.writeInt(message.what);
                            mOutputStream.writeInt(message.data.length);
                            mOutputStream.write(message.data);
                            mOutputStream.flush();
                        } catch (IOException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                            Client.this.shutdown();
                            break;
                        }
                    }

                    if (DEBUG) {
                        Log.d(LOG_TAG, "Writer is terminating");
                    }
                }
            };
        }
    }
}
