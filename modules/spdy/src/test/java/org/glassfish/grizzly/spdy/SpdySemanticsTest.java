/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.spdy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.io.NIOInputStream;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.spdy.frames.CompressedHeadersBuilder;
import org.glassfish.grizzly.spdy.frames.DataFrame;
import org.glassfish.grizzly.spdy.frames.RstStreamFrame;
import org.glassfish.grizzly.spdy.frames.SettingsFrame;
import org.glassfish.grizzly.spdy.frames.SpdyFrame;
import org.glassfish.grizzly.spdy.frames.SynReplyFrame;
import org.glassfish.grizzly.spdy.frames.SynStreamFrame;
import org.glassfish.grizzly.utils.Futures;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Set of tests, which have to check Spdy semantics.
 *
 * @author Alexey Stashok
 */
public class SpdySemanticsTest extends AbstractSpdyTest {
    private static final int PORT = 18303;
    private static final Logger LOGGER = Grizzly.logger(SpdySemanticsTest.class);
    
    private static final SpdyFrame CLOSE_FRAME = new SpdyFrame() {
        @Override
        public Buffer toBuffer(final MemoryManager memoryManager) {
            return null;
        }
    };

    @Test
    @SuppressWarnings("unchecked")
    public void testSettingsFrameOnConnect() throws Exception {
        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createServer(
                HttpHandlerRegistration.of(new StaticHttpHandler(), "/"));
        
        try {
            server.start();
            final FilterChainBuilder clientFilterChainBuilder =
                    createRawClientFilterChainAsBuilder();
            
            final BlockingQueue<SpdyFrame> clientInQueue =
                    new LinkedBlockingQueue<SpdyFrame>();
            
            clientFilterChainBuilder.add(new BaseFilter() {
                @Override
                public NextAction handleRead(final FilterChainContext ctx)
                        throws Exception {
                    final Object msg = ctx.getMessage();
                    if (msg instanceof List) {
                        clientInQueue.addAll((List<SpdyFrame>) msg);
                    } else {
                        clientInQueue.offer((SpdyFrame) msg);
                    }

                    return ctx.getInvokeAction();
                }
            });
            
            clientTransport.setFilterChain(clientFilterChainBuilder.build());

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                
                final Deflater deflater =
                        CompressedHeadersBuilder.createSpdyDeflater();
                
                final Buffer headers = CompressedHeadersBuilder.newInstance()
                        .method(Method.GET)
                        .scheme("http")
                        .path("/test")
                        .version(Protocol.HTTP_1_1)
                        .host("localhost" + PORT)
                        .build(deflater);
                
                connection.write(SynStreamFrame.builder()
                        .streamId(1)
                        .compressedHeaders(headers)
                        .last(true)
                        .build());
                
                while (true) {
                    final SpdyFrame frame =
                            clientInQueue.poll(10, TimeUnit.SECONDS);
                    
                    assertNotNull(frame);
                    
                    if (frame instanceof SettingsFrame) {
                        final SettingsFrame settingsFrame = (SettingsFrame) frame;
                        assertEquals(1, settingsFrame.getNumberOfSettings());
                        assertEquals(Constants.DEFAULT_MAX_CONCURRENT_STREAMS,
                                settingsFrame.getSetting(SettingsFrame.SETTINGS_MAX_CONCURRENT_STREAMS));
                        
                        break;
                    }
                }
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testMaxConcurrentStreamsOnServer() throws Exception {
        final int maxConcurrentStreams = 50;
        
        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createServer(
                HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                request.getInputStream().read();
            }
        }, "/test"));
        spdyAddon.setMaxConcurrentStreams(maxConcurrentStreams);
        
        try {
            server.start();
            final FilterChainBuilder clientFilterChainBuilder =
                    createRawClientFilterChainAsBuilder();
            
            final BlockingQueue<SpdyFrame> clientInQueue =
                    new LinkedBlockingQueue<SpdyFrame>();
            
            clientFilterChainBuilder.add(new BaseFilter() {
                @Override
                public NextAction handleRead(final FilterChainContext ctx)
                        throws Exception {
                    final Object message = ctx.getMessage();
                    if (message instanceof List) {
                        final List<SpdyFrame> spdyFrames = (List<SpdyFrame>) message;
                        clientInQueue.addAll(spdyFrames);
                    } else {
                        final SpdyFrame spdyFrame = (SpdyFrame) message;
                        clientInQueue.offer(spdyFrame);
                    }
                    
                    return ctx.getInvokeAction();
                }
            });
            
            clientTransport.setFilterChain(clientFilterChainBuilder.build());

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                
                final Deflater deflater =
                        CompressedHeadersBuilder.createSpdyDeflater();
                
                for (int i = 0; i < maxConcurrentStreams + 1; i++) {

                    final Buffer headers = CompressedHeadersBuilder.newInstance()
                            .method(Method.POST)
                            .scheme("http")
                            .path("/test")
                            .version(Protocol.HTTP_1_1)
                            .host("localhost" + PORT)
                            .contentLength(10)
                            .build(deflater);

                    connection.write(SynStreamFrame.builder()
                            .streamId(i * 2 + 1)
                            .compressedHeaders(headers)
                            .build());
                }
                
                while (true) {
                    final SpdyFrame frame =
                            clientInQueue.poll(10, TimeUnit.SECONDS);
                    
                    assertNotNull(frame);
                    
                    if (frame instanceof SettingsFrame) {
                        // skip
                        continue;
                    } else if (frame instanceof RstStreamFrame) {
                        final RstStreamFrame rst = (RstStreamFrame) frame;
                        assertEquals(50 * 2 + 1, rst.getStreamId());
                        assertEquals(RstStreamFrame.REFUSED_STREAM, rst.getStatusCode());
                        break;
                    } else {
                        fail("Unexpected frame: " + frame);
                    }
                }
                
                
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    @Test
    @SuppressWarnings("unchecked")
    public void testMaxConcurrentStreamsOnClient() throws Exception {
        final int maxConcurrentStreams = 50;
        
        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createServer(
                HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                request.getInputStream().read();
            }
        }, "/test"));
        spdyAddon.setMaxConcurrentStreams(maxConcurrentStreams * 2);
        
        try {
            server.start();
            
            final FilterChain clientFilterChain =
                    createClientFilterChain(SpdyMode.PLAIN, false);
            setMaxConcurrentStreams(clientFilterChain, maxConcurrentStreams);
            clientTransport.setFilterChain(clientFilterChain);

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                
                boolean isExceptionThrown = false;
                
                for (int i = 0; i < maxConcurrentStreams + 1; i++) {
                    final HttpRequestPacket request = HttpRequestPacket.builder()
                            .method(Method.POST)
                            .uri("/test")
                            .protocol(Protocol.HTTP_1_1)
                            .contentLength(10)
                            .header(Header.Host, "localhost:" + PORT)
                            .build();
                    Future<WriteResult> sendFuture = connection.write(request);
                    
                    try {
                        sendFuture.get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException ee) {
                        final Throwable cause = ee.getCause();
                        assertTrue(cause instanceof SpdyStreamException);
                        
                        final SpdyStreamException rstException =
                                (SpdyStreamException) cause;
                        assertEquals(maxConcurrentStreams, i);
                        assertEquals(RstStreamFrame.REFUSED_STREAM,
                                rstException.getRstReason());
                        isExceptionThrown = true;
                    }
                }
                
                assertTrue(isExceptionThrown);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    /**
     * Check rst frame, when it comes during server read and write
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testRstFrame() throws Exception {
        final FutureImpl<Boolean> writeHandlerFuture = Futures.<Boolean>createSafeFuture();
        final FutureImpl<Boolean> readHandlerFuture = Futures.<Boolean>createSafeFuture();
        
        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createServer(
                HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                final byte[] outputBuffer = new byte[1024];
                int counter = 0;
                
                try {
                    while (true) {
                        Arrays.fill(outputBuffer, (byte) counter);
                        response.getOutputStream().write(outputBuffer);
                        counter = (++counter) % 0x7F;
                    }
                } catch (Exception e) {
                    writeHandlerFuture.failure(e);
                } finally {
                    writeHandlerFuture.result(Boolean.TRUE);
                }
            }
        }, "/write"),
                HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                try {
                    request.getInputStream().read();
                } catch (Exception e) {
                    readHandlerFuture.failure(e);
                } finally {
                    readHandlerFuture.result(Boolean.TRUE);
                }
            }
        }, "/read"));
        
        final NetworkListener listener = server.getListener("grizzly");
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        listener.getTransport().setBlockingWriteTimeout(-1, TimeUnit.MILLISECONDS);
        
        try {
            server.start();
            final FilterChainBuilder clientFilterChainBuilder =
                    createRawClientFilterChainAsBuilder();
            
            clientTransport.setFilterChain(clientFilterChainBuilder.build());

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                final int streamId1 = 1;
                final int streamId2 = 3;
                
                final Deflater deflater =
                        CompressedHeadersBuilder.createSpdyDeflater();
                
                final Buffer headers1 = CompressedHeadersBuilder.newInstance()
                        .method(Method.GET)
                        .scheme("http")
                        .path("/write")
                        .version(Protocol.HTTP_1_1)
                        .host("localhost" + PORT)
                        .build(deflater);
                
                connection.write(SynStreamFrame.builder()
                        .streamId(streamId1)
                        .compressedHeaders(headers1)
                        .last(true)
                        .build());
                
                final Buffer headers2 = CompressedHeadersBuilder.newInstance()
                        .method(Method.POST)
                        .scheme("http")
                        .path("/read")
                        .version(Protocol.HTTP_1_1)
                        .host("localhost" + PORT)
                        .contentLength(10)
                        .build(deflater);
                
                connection.write(SynStreamFrame.builder()
                        .streamId(streamId2)
                        .compressedHeaders(headers2)
                        .build());

                // Wait before sending rst
                Thread.sleep(2000);
                
                // sending rsts
                connection.write(
                        RstStreamFrame.builder()
                        .statusCode(RstStreamFrame.CANCEL)
                        .streamId(streamId1)
                        .build());
                
                connection.write(
                        RstStreamFrame.builder()
                        .statusCode(RstStreamFrame.INTERNAL_ERROR)
                        .streamId(streamId2)
                        .build());
                
                try {
                    writeHandlerFuture.get(10, TimeUnit.SECONDS);
                    fail("The IOException had to be thrown");
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof IOException);
                }
                
                try {
                    readHandlerFuture.get(10, TimeUnit.SECONDS);
                    fail("The IOException had to be thrown");
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof IOException);
                }
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    /**
     * Check the oversized control frame processing
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testOversizedControlFrame() throws Exception {
        final int maxFrameLen = 1024;
        
        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createServer(
                HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                request.getInputStream().read();
            }
        }, "/test"));
        
        spdyAddon.setMaxFrameLength(maxFrameLen);
        
        final NetworkListener listener = server.getListener("grizzly");
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        listener.getTransport().setBlockingWriteTimeout(-1, TimeUnit.MILLISECONDS);
        
        try {
            server.start();
            
            final BlockingQueue<SpdyFrame> clientInQueue =
                    new LinkedBlockingQueue<SpdyFrame>();
            final FilterChainBuilder clientFilterChainBuilder =
                    createRawClientFilterChainAsBuilder()
                    .add(new RawClientFilter(clientInQueue));
                        
            clientTransport.setFilterChain(clientFilterChainBuilder.build());

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                final int streamId = 1;
                
                final Deflater deflater =
                        CompressedHeadersBuilder.createSpdyDeflater();
                
                connection.write(
                        createSynStream(streamId, "/test", maxFrameLen, deflater));
                
                boolean hasRstCome = false;
                
                while (true) {
                    final SpdyFrame frame =
                            clientInQueue.poll(10, TimeUnit.SECONDS);
                    
                    assertNotNull(frame);
                    
                    if (frame instanceof SettingsFrame) {
                        // skip
                        continue;
                    } else if (frame instanceof RstStreamFrame) {
                        final RstStreamFrame rst = (RstStreamFrame) frame;
                        assertEquals(streamId, rst.getStreamId());
                        assertEquals(RstStreamFrame.FRAME_TOO_LARGE, rst.getStatusCode());
                        hasRstCome = true;
                        
                    } else if (frame == CLOSE_FRAME) {
                        if (!hasRstCome) {
                            // closed w/o RST frame - also ok, if the server could no
                            // extract stream-id.
                            // Print a warning just in case
                            LOGGER.warning("No RST frame");
                        }
                        break;
                    } else {
                        fail("Unexpected frame: " + frame);
                    }
                }
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    /**
     * Check the oversized control frame processing
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testOversizedDataFrame() throws Exception {
        final int maxFrameLen = 1024;
        
        final TCPNIOTransport clientTransport =
                TCPNIOTransportBuilder.newInstance().build();
        final HttpServer server = createServer(
                HttpHandlerRegistration.of(new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                final NIOInputStream inputStream = request.getInputStream();
                final byte[] buf = new byte[256];
                while (inputStream.read(buf) != -1) {
                    // read all the available input bytes
                }
            }
        }, "/test"));
        
        spdyAddon.setMaxFrameLength(maxFrameLen);
        
        final NetworkListener listener = server.getListener("grizzly");
        listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
        listener.getTransport().setBlockingWriteTimeout(-1, TimeUnit.MILLISECONDS);
        
        try {
            server.start();
            
            final BlockingQueue<SpdyFrame> clientInQueue =
                    new LinkedBlockingQueue<SpdyFrame>();
            final FilterChainBuilder clientFilterChainBuilder =
                    createRawClientFilterChainAsBuilder()
                    .add(new RawClientFilter(clientInQueue));
            
            clientTransport.setFilterChain(clientFilterChainBuilder.build());

            clientTransport.start();

            Future<Connection> connectFuture = clientTransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                final Deflater deflater =
                        CompressedHeadersBuilder.createSpdyDeflater();
                
                final MemoryManager mm = clientTransport.getMemoryManager();

                final Random r = new Random();
                int normalRequestsCounter = 0;
                Buffer remainder = null;
                
                final int dataSize = 4096;
                
                // Randomly try different testcases
                final Buffer[][] options = new Buffer[5][];
                options[0] = allowDispose(new Buffer[] {mm.allocate(dataSize)}, false);
                options[1] = allowDispose(new Buffer[] {mm.allocate(dataSize / 2), mm.allocate(dataSize / 2)}, false);
                options[2] = allowDispose(new Buffer[] {mm.allocate(dataSize - maxFrameLen), mm.allocate(maxFrameLen)}, false);
                options[3] = allowDispose(new Buffer[] {mm.allocate(maxFrameLen), mm.allocate(dataSize - maxFrameLen)}, false);
                options[4] = allowDispose(new Buffer[] {mm.allocate(maxFrameLen), mm.allocate(maxFrameLen), mm.allocate(maxFrameLen), mm.allocate(maxFrameLen)}, false);

                final int normalOption = 4;
                final int streamsCount = 100;
                
                for (int i = 0; i < streamsCount; i++) {
                    final int option = r.nextInt(options.length);
                    final boolean isNormal = (option == normalOption);
                    
                    if (isNormal) {
                        normalRequestsCounter++;
                    }
                    
                    final int streamId = i * 2 + 1;
                    final Buffer synStream =
                            createSynStream(streamId, Method.POST,
                            "/test", dataSize, 0, false, deflater)
                            .toBuffer(mm);

                    final Buffer[] data = toDataFrameBuffers(streamId, makeCopy(options[option]), mm);
                    
                    final boolean leaveRemainder = (i < (streamsCount - 1)) && r.nextBoolean();
                    
                    Buffer newRemainder = null;
                    if (leaveRemainder) {
                        final Buffer lastBuffer = data[data.length - 1];
                        newRemainder = lastBuffer.split(lastBuffer.position() + lastBuffer.remaining() / 2);
                    }
                    
                    final CompositeBuffer bufferToSend = CompositeBuffer.newBuffer(mm, data);
                    bufferToSend.prepend(synStream);
                    if (remainder != null) {
                        bufferToSend.prepend(remainder);
                    }
                    
                    remainder = newRemainder;
                    
                    connection.write(bufferToSend);
                    
                    Thread.sleep(2);
                }
                
                int repliesGot = 0;
                
                while (true) {
                    final SpdyFrame frame =
                            clientInQueue.poll(10, TimeUnit.SECONDS);
                    
                    assertNotNull("We expect more frames. Expected=" + normalRequestsCounter + " got=" + repliesGot, frame);
                    assertTrue("Connection  was unexpectedly closed", frame != CLOSE_FRAME);
                    
                    if (!frame.getHeader().isControl()) {
                        // skip DataFrame
                        continue;
                    }
                    
                    switch (frame.getHeader().getType()) {
                        case SynReplyFrame.TYPE:
                            repliesGot++;
                            if (repliesGot == normalRequestsCounter) {
                                return;
                            }
                        case SettingsFrame.TYPE:
                        case RstStreamFrame.TYPE:
                            break;
                        default:
                            fail("Unexpected frame: " + frame);
                    }
                }
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.closeSilently();
                }
            }
        } finally {
            clientTransport.stop();
            server.stop();
        }
    }
    
    private HttpServer createServer(final HttpHandlerRegistration... registrations) {
        return createServer(".", PORT, SpdyMode.PLAIN, false, registrations);
    }
    
    private static FilterChainBuilder createRawClientFilterChainAsBuilder() {
        final FilterChainBuilder builder = createClientFilterChainAsBuilder(
                SpdyMode.PLAIN, false);
        final int handlerIdx = builder.indexOfType(SpdyHandlerFilter.class);
        if (handlerIdx != -1) {
            builder.remove(handlerIdx);
        }
        
        return builder;
    }
    
    private void setMaxConcurrentStreams(final FilterChain filterChain,
            final int maxConcurrentStreams) {
        
        final int spdyFilterIdx = filterChain.indexOfType(SpdyHandlerFilter.class);
        final SpdyHandlerFilter spdyHandlerFilter =
                (SpdyHandlerFilter) filterChain.get(spdyFilterIdx);
        spdyHandlerFilter.setMaxConcurrentStreams(maxConcurrentStreams);
    }
    
    private SynStreamFrame createSynStream(final int streamId, final String uri,
            final int headersCount, final Deflater deflater) throws IOException {
        return createSynStream(streamId, Method.GET, uri, -1, headersCount,
                true, deflater);
    }
    
    private SynStreamFrame createSynStream(final int streamId, final Method method,
            final String uri, final int contentLength,
            final int headersCount, final boolean isLast,
            final Deflater deflater) throws IOException {

        final CompressedHeadersBuilder builder =
                CompressedHeadersBuilder.newInstance()
                .method(method)
                .scheme("http")
                .path(uri)
                .version(Protocol.HTTP_1_1)
                .host("localhost" + PORT);

        if (contentLength >= 0) {
            builder.contentLength(contentLength);
        }
        
        for (int i = 0; i < headersCount; i++) {
            builder.header("h-" + i, "v-" + i);
        }

        final Buffer headers = builder.build(deflater);

        return SynStreamFrame.builder()
                .streamId(streamId)
                .compressedHeaders(headers)
                .last(isLast)
                .build();
    }

    private Buffer[] allowDispose(Buffer[] buffers, boolean b) {
        if (buffers != null) {
            for (Buffer buffer : buffers) {
                buffer.allowBufferDispose(b);
            }
        }
        
        return buffers;
    }

    private Buffer[] makeCopy(Buffer[] buffers) {
        final Buffer[] newBuffers = new Buffer[buffers.length];
        for (int i = 0; i < buffers.length; i++) {
            final Buffer buffer = buffers[i];
            final Buffer copy = buffer.duplicate();
            copy.allowBufferDispose(false);
            
            newBuffers[i] = copy;
        }
        
        return newBuffers;
    }

    private Buffer[] toDataFrameBuffers(final int streamId,
            final Buffer[] data, final MemoryManager mm) {
        
        final Buffer[] dataFrameBuffers = new Buffer[data.length];
        for (int i = 0; i < data.length; i++) {
            dataFrameBuffers[i] = DataFrame.builder()
                    .streamId(streamId)
                    .data(data[i])
                    .last(i == data.length - 1)
                    .build()
                    .toBuffer(mm);
        }
        
        return dataFrameBuffers;
    }
    
    @SuppressWarnings("unchecked")
    private static class RawClientFilter extends BaseFilter {
        private final BlockingQueue<SpdyFrame> clientInQueue;

        public RawClientFilter(BlockingQueue<SpdyFrame> clientInQueue) {
            this.clientInQueue = clientInQueue;
        }
        
        @Override
        public NextAction handleRead(final FilterChainContext ctx)
                throws Exception {
            final Object msg = ctx.getMessage();
            if (msg instanceof List) {
                clientInQueue.addAll((List<SpdyFrame>) msg);
            } else {
                clientInQueue.offer((SpdyFrame) msg);
            }

            return ctx.getInvokeAction();
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx) throws Exception {
            clientInQueue.offer(CLOSE_FRAME);

            return ctx.getStopAction();
        }
    }
}