package me.shenfeng.http.server;

import clojure.lang.ISeq;
import clojure.lang.Seqable;
import me.shenfeng.http.DynamicBytes;
import me.shenfeng.http.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.nio.channels.SelectionKey.*;
import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.server.ServerConstant.*;
import static me.shenfeng.http.server.ServerDecoderState.ALL_READ;

public class HttpServer {

    static Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private static void doWrite(SelectionKey key) throws IOException {
        ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        ReqeustDecoder decoder = atta.decoder;
        ByteBuffer header = atta.respHeader;
        ByteBuffer body = atta.respBody;

        if (body == null) {
            ch.write(header);
            if (!header.hasRemaining()) {
                if (decoder.request.isKeepAlive()) {
                    decoder.reset();
                    key.interestOps(OP_READ);
                } else {
                    closeQuiety(ch);
                }
            }
        } else {
            if (header.hasRemaining()) {
                ch.write(new ByteBuffer[] {header, body});
            } else {
                ch.write(body);
            }
            if (!body.hasRemaining()) {
                if (decoder.request.isKeepAlive()) {
                    decoder.reset();
                    key.interestOps(OP_READ);
                } else {
                    closeQuiety(ch);
                }
            }
        }
    }

    private IHandler handler;
    private int port;
    private final int maxBody;
    private String ip;
    private Selector selector;
    private Thread serverThread;
    private ServerSocketChannel serverChannel;

    ConcurrentLinkedQueue<SelectionKey> pendings = new ConcurrentLinkedQueue<SelectionKey>();

    // shared, single thread
    private ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 64);

    private Runnable eventLoop = new Runnable() {
        public void run() {
            SelectionKey key = null;
            while (true) {
                try {
                    while ((key = pendings.poll()) != null) {
                        if (key.isValid()) {
                            key.interestOps(OP_WRITE);
                        }
                    }
                    int select = selector.select(SELECT_TIMEOUT);
                    if (select <= 0) {
                        continue;
                    }
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> ite = selectedKeys.iterator();
                    while (ite.hasNext()) {
                        key = ite.next();
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isAcceptable()) {
                            accept(key, selector);
                        } else if (key.isReadable()) {
                            doRead(key);
                        } else if (key.isWritable()) {
                            doWrite(key);
                        }
                    }
                    selectedKeys.clear();
                } catch (ClosedSelectorException ignore) {
                    logger.info("Selector closed, server stopped");
                    selector = null;
                    return;
                } catch (Exception e) { // catch any exception, print it
                    if (key != null) {
                        closeQuiety(key.channel());
                    }
                    logger.error("server event loop error", e);
                }
            } // end of while loop
        }
    };

    public HttpServer(String ip, int port, IHandler handler, int maxBody) {
        this.handler = handler;
        this.ip = ip;
        this.port = port;
        this.maxBody = maxBody;
    }

    void accept(SelectionKey key, Selector selector) throws IOException {
        ServerSocketChannel ch = (ServerSocketChannel) key.channel();
        SocketChannel s;
        while ((s = ch.accept()) != null) {
            s.configureBlocking(false);
            s.register(selector, OP_READ, new ServerAtta(maxBody));
        }
    }

    void bind() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        InetSocketAddress addr = new InetSocketAddress(ip, port);
        serverChannel.socket().bind(addr);
        serverChannel.register(selector, OP_ACCEPT);
        logger.info("server start {}@{}, max body: {}", new Object[] {ip,
                port, maxBody});
    }

    private class Callback implements IResponseCallback {
        private final SelectionKey key;

        public Callback(SelectionKey key) {
            this.key = key;
        }

        // maybe in another thread
        public void run(int status, Map<String, Object> headers, Object body) {
            ServerAtta atta = (ServerAtta) key.attachment();
            // extend ring spec to support async
            if (body instanceof IListenableFuture) {
                final int status2 = status;
                final Map<String, Object> headers2 = headers;
                final IListenableFuture future = (IListenableFuture) body;
                future.addListener(new Runnable() {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    public void run() {
                        Object r = future.get();
                        // if is a ring spec response
                        if (r instanceof Map) {
                            Map resp = (Map) r;
                            Object s = resp.get(STATUS);
                            int status = 200;
                            if (s instanceof Long) {
                                status = ((Long) s).intValue();
                            } else if (s instanceof Integer) {
                                status = (Integer) s;
                            }
                            Map<String, Object> headers = (Map) resp.get(HEADERS);
                            new Callback(key).run(status, headers, resp.get(BODY));
                        } else {
                            // treat it as just body
                            new Callback(key).run(status2, headers2, r);
                        }
                    }
                });
                return;
            }

            if (headers != null) {
                // copy to modify
                headers = new TreeMap<String, Object>(headers);
            } else {
                headers = new TreeMap<String, Object>();
            }
            try {
                if (body == null) {
                    atta.respBody = null;
                    headers.put(CONTENT_LENGTH, "0");
                } else if (body instanceof String) {
                    byte[] b = ((String) body).getBytes(UTF_8);
                    atta.respBody = ByteBuffer.wrap(b);
                    headers.put(CONTENT_LENGTH, Integer.toString(b.length));
                } else if (body instanceof InputStream) {
                    DynamicBytes b = readAll((InputStream) body);
                    atta.respBody = ByteBuffer.wrap(b.get(), 0, b.length());
                    headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
                } else if (body instanceof File) {
                    File f = (File) body;
                    // serving file is better be done by nginx
                    long length = f.length();
                    byte[] b = readAll(f, (int) length);
                    atta.respBody = ByteBuffer.wrap(b);
                } else if (body instanceof Seqable) {
                    ISeq seq = ((Seqable) body).seq();
                    DynamicBytes b = new DynamicBytes(seq.count() * 512);
                    while (seq != null) {
                        b.append(seq.first().toString(), UTF_8);
                        seq = seq.next();
                    }
                    atta.respBody = ByteBuffer.wrap(b.get(), 0, b.length());
                    headers.put(CONTENT_LENGTH, Integer.toString(b.length()));
                } else {
                    logger.error(body.getClass() + " is not understandable");
                }
            } catch (IOException e) {
                byte[] b = e.getMessage().getBytes(ASCII);
                status = 500;
                headers.clear();
                headers.put(CONTENT_LENGTH, Integer.toString(b.length));
                atta.respBody = ByteBuffer.wrap(b);
            }
            DynamicBytes bytes = encodeResponseHeader(status, headers);
            atta.respHeader = ByteBuffer.wrap(bytes.get(), 0, bytes.length());
            pendings.offer(key);
            selector.wakeup();
        }
    }

    private void doRead(final SelectionKey key) {
        final ServerAtta atta = (ServerAtta) key.attachment();
        SocketChannel ch = (SocketChannel) key.channel();
        try {
            buffer.clear(); // clear for read
            int read = ch.read(buffer);
            if (read == -1) {
                // remote entity shut the socket down cleanly.
                closeQuiety(ch);
            } else if (read > 0) {
                buffer.flip(); // flip for read
                ReqeustDecoder decoder = atta.decoder;
                if (decoder.decode(buffer) == ALL_READ) {
                    HttpRequest request = decoder.request;
                    request.setRemoteAddr(ch.socket().getRemoteSocketAddress());
                    handler.handle(request, new Callback(key));
                }
            }
        } catch (IOException e) {
            closeQuiety(ch); // the remote forcibly closed the connection
        } catch (ProtocolException e) {
            closeQuiety(ch);
            // LineTooLargeException, RequestTooLargeException
        } catch (Exception e) {
            byte[] body = e.getMessage().getBytes(ASCII);
            Map<String, Object> headers = new TreeMap<String, Object>();
            headers.put(CONTENT_LENGTH, body.length);
            DynamicBytes db = encodeResponseHeader(400, headers);
            db.append(body, 0, body.length);
            atta.respBody = null;
            atta.respHeader = ByteBuffer.wrap(db.get(), 0, db.length());
            key.interestOps(OP_WRITE);
        }
    }

    public void start() throws IOException {
        bind();
        serverThread = new Thread(eventLoop, "http-server");
        serverThread.start();
    }

    public void stop() {
        if (selector != null) {
            try {
                serverChannel.close();
                serverChannel = null;
                Set<SelectionKey> keys = selector.keys();
                for (SelectionKey k : keys) {
                    k.channel().close();
                }
                selector.close();
            } catch (IOException ignore) {
            }
            serverThread.interrupt();
        }
    }
}
