/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual
 *  contributors.
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.modules.fetcher;

import org.archive.url.URIException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.http.client.CookieStore;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.archive.io.RecorderLengthExceededException;
import org.archive.io.RecorderTimeoutException;
import org.archive.modules.CrawlURI;
import org.archive.modules.Processor;
import org.archive.modules.net.CrawlHost;
import org.archive.modules.net.CrawlServer;
import org.archive.modules.net.ServerCache;
import org.archive.util.Recorder;
import org.eclipse.jetty.client.*;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.*;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.quiche.jna.LibQuiche;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.archive.modules.fetcher.FetchErrors.LENGTH_TRUNC;
import static org.archive.modules.fetcher.FetchErrors.TIMER_TRUNC;
import static org.archive.modules.fetcher.FetchStatusCodes.S_DOMAIN_PREREQUISITE_FAILURE;

/**
 * HTTP Fetcher that uses Jetty HttpClient to support HTTP/2 and HTTP/3.
 * <p>
 * Does not record the original on-the-wire HTTP messages but instead a simplified HTTP/1.1
 * representation without transfer encoding.
 */
public class FetchHTTP2 extends Processor implements Lifecycle, InitializingBean {
    private static final Logger logger = Logger.getLogger(FetchHTTP2.class.getName());
    protected HttpClient httpClient;
    protected final ServerCache serverCache;
    protected final AbstractCookieStore cookieStore;
    protected String digestAlgorithm = "sha1";
    protected boolean useHTTP2 = true;
    protected boolean useHTTP3 = false;

    public FetchHTTP2(@Autowired ServerCache serverCache, @Autowired(required = false) AbstractCookieStore cookieStore) {
        this.serverCache = serverCache;
        this.cookieStore = cookieStore;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (useHTTP3) {
            // Make sure the native library actually loads, as we don't ship it by default
            // or, we may be on platform the jetty project doesn't have a binary for.
            try {
                LibQuiche.INSTANCE.quiche_version();
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                String hint;
                if (getClass().getResource(
                        "/META-INF/maven/org.mortbay.jetty.quiche/jetty-quiche-native/pom.xml") != null) {
                    hint = "jetty-quiche-native may not have been compiled for this platform.";
                } else {
                    hint = "Try downloading jetty-quiche-native and placing it in Heritrix's lib directory: " +
                           "https://repo1.maven.org/maven2/org/mortbay/jetty/quiche/jetty-quiche-native/" +
                           LibQuiche.EXPECTED_QUICHE_VERSION + "/jetty-quiche-native-" +
                           LibQuiche.EXPECTED_QUICHE_VERSION + ".jar";
                }
                logger.log(Level.WARNING, "Failed to load LibQuiche, disabling HTTP/3. " + hint, e);
                useHTTP3 = false;
            }
        }
    }

    protected HttpClient createHttpClient(AbstractCookieStore cookieStore) {
        var sslContextFactory = new SslContextFactory.Client();
        var connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);
        var connectionFactories = new ArrayList<ClientConnectionFactory.Info>();

        // HTTP/2: always use if available (negotiated via ALPN)
        if (useHTTP2) {
            var http2Client = new HTTP2Client(connector);
            connectionFactories.add(new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client));
        }

        // HTTP/1.1: fallback if HTTP/2 is not available
        connectionFactories.add(HttpClientConnectionFactory.HTTP11);

        // HTTP/3: jetty can't auto-negotiate it, so we make it the lowest priority here
        // but later use request.version(HTTP3) for servers that have sent us an Alt-Svc header
        if (useHTTP3) {
            var quicConfig = new ClientQuicConfiguration(sslContextFactory, null);
            var http3Client = new HTTP3Client(quicConfig, connector);
            connectionFactories.add(new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client));
        }

        var transport = new HttpClientTransportDynamic(connector, connectionFactories.toArray(new ClientConnectionFactory.Info[0]));
        HttpClient httpClient = new HttpClient(transport);
        httpClient.setFollowRedirects(false); // we handle redirects ourselves
        httpClient.setDestinationIdleTimeout(5 * 60 * 1000);
        httpClient.setConnectTimeout(20 * 1000);
        httpClient.setMaxConnectionsPerDestination(6);
        if (serverCache != null) httpClient.setSocketAddressResolver(this::resolveSocketAddress);
        if (cookieStore != null) httpClient.setHttpCookieStore(new CookieStoreAdaptor(cookieStore));
        return httpClient;
    }

    /**
     * Resolves a socket address using the ServerCache (instead of a live DNS query).
     */
    protected void resolveSocketAddress(String host, int port, Promise<List<InetSocketAddress>> promise) {
        CrawlHost crawlHost = serverCache.getHostFor(host);
        if (crawlHost != null && crawlHost.getIP() != null) {
            var ip = crawlHost.getIP();
            if (!ip.getHostName().equals(host)) {
                // The host part of the address in serverCache can be a resolved CNAME so we need to set it back the
                // original host to avoid this:
                // javax.net.ssl.SSLHandshakeException: No subject alternative DNS name matching ${ip.hostName} found.
                try {
                    ip = InetAddress.getByAddress(host, crawlHost.getIP().getAddress());
                } catch (UnknownHostException e) {
                    promise.failed(e);
                    return;
                }
            }
            promise.succeeded(List.of(new InetSocketAddress(ip, port)));
        } else {
            promise.failed(new UnknownHostException());
        }
    }

    @Override
    protected boolean shouldProcess(CrawlURI curi) {
        String scheme = curi.getUURI().getScheme();
        if (!scheme.equals("http") && !scheme.equals("https")) return false;

        CrawlHost host = serverCache.getHostFor(curi.getUURI());
        if (host.getIP() == null && host.hasBeenLookedUp()) {
            curi.setFetchStatus(S_DOMAIN_PREREQUISITE_FAILURE);
            return false;
        }

        return true;
    }

    @Override
    protected void innerProcess(CrawlURI curi) throws InterruptedException {
        var listener = new InputStreamResponseListener();

        var recorder = curi.getRecorder();
        if (digestAlgorithm != null) recorder.getRecordedInput().setDigest(digestAlgorithm);
        recorder.getRecordedInput().setLimits(getMaxLengthBytes(),
                1000L * (long) getTimeoutSeconds(), getMaxFetchKBSec());
        curi.setFetchBeginTime(System.currentTimeMillis());

        try {
            Request request = httpClient.newRequest(curi.getURI())
                    .timeout(getTimeoutSeconds(), TimeUnit.SECONDS)
                    .method(curi.getFetchType() == CrawlURI.FetchType.HTTP_POST ? HttpMethod.POST : HttpMethod.GET)
                    .agent(getUserAgentProvider().getUserAgent());
            if (!curi.getUURI().getScheme().equals("https")) {
                request.version(HttpVersion.HTTP_1_1);
            } else if (useHTTP3 && curi.getFetchAttempts() == 0) {
                // use HTTP/3 if we've seen an Alt-Svc header
                CrawlServer crawlServer = serverCache.getServerFor(curi.getUURI());
                int http3Port = crawlServer.getHttp3AltSvcPort();
                if (http3Port > 0) {
                    // TODO: Support alternate Alt-Svc ports for HTTP/3.
                    //   Tricky to do because we need to preserve the original request URI.
                    //   Maybe changing the port in resolveSocketAddress() would work?
                    if (http3Port == curi.getUURI().getPort() || (curi.getUURI().getPort() == -1 && http3Port == 443)) {
                        request.version(HttpVersion.HTTP_3);
                    }
                }
            }
            request.send(listener);
            recordRequest(request, recorder);
            Response response = listener.get(getTimeoutSeconds(), TimeUnit.SECONDS);
            handleAltSvcHeader(curi, response);
            updateCrawlURIWithResponseHeader(curi, response);
            recordResponse(response, recorder, listener);
        } catch (RecorderTimeoutException ex) {
            curi.getAnnotations().add(TIMER_TRUNC);
        } catch (RecorderLengthExceededException ex) {
            curi.getAnnotations().add(LENGTH_TRUNC);
        } catch (TimeoutException | ExecutionException | IOException e) {
            if (logger.isLoggable(Level.INFO)) {
                logger.info(curi + ": " + e);
            }
            curi.getNonFatalFailures().add(e);
            if (e instanceof TimeoutException) {
                curi.setFetchStatus(FetchStatusCodes.S_TIMEOUT);
            } else {
                curi.setFetchStatus(FetchStatusCodes.S_CONNECT_FAILED);
            }
        } finally {
            IOUtils.closeQuietly(listener.getInputStream());
            recorder.close();
            recorder.closeRecorders();
            updateCrawlURIOnCompletion(curi, recorder);
        }
    }

    /**
     * Handles the Alt-Svc HTTP header to enable HTTP/3 alternative service.
     * Does nothing if useHTTP3 is disabled.
     */
    private void handleAltSvcHeader(CrawlURI curi, Response response) {
        if (!useHTTP3) return;
        if (!curi.getUURI().getScheme().equals("https")) return;

        HttpField field = response.getHeaders().getField(HttpHeader.ALT_SVC);
        if (field == null) return;
        CrawlServer crawlServer = serverCache.getServerFor(curi.getUURI());
        for (String value : field.getValueList()) {
            if (value.equals("clear")) {
                crawlServer.clearAltSvc();
                continue;
            }
            // Parse ;foo=bar parameters
            var parameters = new HashMap<String, String>();
            String valueWithoutParameters = HttpField.getValueParameters(value, parameters);

            // Split protocol-id=altAuthority
            var alternative = HttpField.NAME_VALUE_TOKENIZER.tokenize(valueWithoutParameters);
            if (!alternative.hasNext()) continue;
            String protocolId = alternative.next();
            if (!alternative.hasNext()) continue;
            String altAuthority = alternative.next();

            // We're only interested in HTTP/3 currently
            if (!protocolId.equals("h3")) continue;

            // Parse host:port
            int colon = altAuthority.lastIndexOf(':');
            if (colon < 0) continue;

            // For IPv6 addresses, check if this is an actual port separator
            if (altAuthority.indexOf('[') >= 0) {
                // This might be IPv6 format: [2001:db8::1]:8080
                int closeBracket = altAuthority.lastIndexOf(']');
                if (closeBracket < 0 || colon <= closeBracket) continue;
            }

            String host = altAuthority.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(altAuthority.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }

            // Currently, we don't support alternative hosts as we'd don't have their DNS resolved
            try {
                if (!host.isEmpty() && !host.equals(curi.getUURI().getHost())) {
                    continue;
                }
            } catch (URIException e) {
                return;
            }

            // Browsers only allow privileged ports, so let's do that too
            if (port < 1 || port >= 1024) continue;

            // Calculate the expiry time (default 24 hours)
            long maxAgeSeconds = 0;
            String maParameter = parameters.get("ma");
            if (maParameter != null) {
                try {
                    maxAgeSeconds = Long.parseLong(maParameter);
                } catch (NumberFormatException e) {
                    // ignore
                }
                if (maxAgeSeconds < 0) maxAgeSeconds = 24 * 60 * 60;
            }
            long expiryMillis = System.currentTimeMillis() + maxAgeSeconds * 1000;
            crawlServer.setHttp3AltSvc(port, expiryMillis);
            break; // we only support one alt-svc entry for now
        }
    }

    /**
     * Reconstructs the HTTP request and records it.
     */
    public static void recordRequest(Request request, Recorder recorder) throws IOException {
        String target = request.getPath();
        if (request.getQuery() != null) target += "?" + request.getQuery();
        String recordVersion = request.getVersion().equals(HttpVersion.HTTP_1_0) ? "HTTP/1.0" : "HTTP/1.1";
        String requestHeader = request.getMethod() + " " + target + " " + recordVersion + "\r\n" + request.getHeaders().asString();
        recorder.outputWrap(NullOutputStream.INSTANCE).write(requestHeader.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reconstructs the HTTP response and records it.
     */
    private static void recordResponse(Response response, Recorder recorder, InputStreamResponseListener listener) throws IOException {
        String header = formatResponseHeader(response);
        ByteArrayInputStream headerStream = new ByteArrayInputStream(header.getBytes(StandardCharsets.US_ASCII));
        try (InputStream inputStream = listener.getInputStream()) {
            var streams = List.of(headerStream, inputStream);
            recorder.inputWrap(new SequenceInputStream(Collections.enumeration(streams)));
            recorder.getRecordedInput().readFully();
        }
    }

    public static String formatResponseHeader(Response response) {
        // Since the transfer encoding has been decoded, we need to remove the header
        HttpFields headers = HttpFields.build(response.getHeaders())
                .remove(EnumSet.of(HttpHeader.TRANSFER_ENCODING));
        String recordVersion = response.getVersion().equals(HttpVersion.HTTP_1_0) ? "HTTP/1.0" : "HTTP/1.1";
        String reason = response.getReason() == null ? "" : response.getReason();
        String header = recordVersion + " " + response.getStatus() + " " + reason + "\r\n" +
                headers.asString();
        return header;
    }

    /**
     * Updates the CrawlURI with details from the HTTP response header.
     */
    public void updateCrawlURIWithResponseHeader(CrawlURI curi, Response response) {
        // Status
        curi.setFetchStatus(response.getStatus());

        // Server IP address
        var socketAddress = (InetSocketAddress) response.getRequest().getConnection().getRemoteSocketAddress();
        curi.setServerIP(socketAddress.getAddress().getHostAddress());

        // Request method
        if (curi.getFetchType() != CrawlURI.FetchType.HTTP_POST) {
            curi.setFetchType(CrawlURI.FetchType.HTTP_GET);
        }

        // Content-Type
        String contentType = response.getHeaders().getLast(HttpHeader.CONTENT_TYPE);
        curi.setContentType(contentType);
        Charset charset = StandardCharsets.ISO_8859_1;
        if (contentType != null) {
            String charsetName = MimeTypes.getCharsetFromContentType(contentType);
            if (charsetName != null) {
                try {
                    charset = Charset.forName(charsetName);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    // ignore
                }
            }
        }
        curi.getRecorder().setCharset(charset);

        // Content-Encoding
        String contentEncoding = response.getHeaders().getLast(HttpHeader.CONTENT_ENCODING);
        if (contentEncoding != null) {
            try {
                curi.getRecorder().setContentEncoding(contentEncoding);
            } catch (IllegalArgumentException e) {
                curi.getAnnotations().add("unsatisfiableContentEncoding:" + contentEncoding);
            }
        }

        // Response headers
        for (HttpField field : response.getHeaders()) {
            curi.putHttpResponseHeader(field.getName(), field.getValue());
        }

        // HTTP version annotations (for crawl log and WARC-Protocol)
        if (response.getVersion().equals(HttpVersion.HTTP_2)) {
            curi.getAnnotations().add("h2");
        } else if (response.getVersion().equals(HttpVersion.HTTP_3)) {
            curi.getAnnotations().add("h3");
        }
    }

    /**
     * Updates the CrawlURI with details from the Recorder after it is closed.
     */
    public void updateCrawlURIOnCompletion(CrawlURI curi, Recorder recorder) {
        curi.setFetchCompletedTime(System.currentTimeMillis());
        if (digestAlgorithm != null) {
            curi.setContentDigest(digestAlgorithm, recorder.getRecordedInput().getDigestValue());
        }
        curi.setContentSize(recorder.getRecordedInput().getSize());
        // add contentSize to extraInfo so it's available to log in the crawl log
        curi.addExtraInfo("contentSize", recorder.getRecordedInput().getSize());
    }

    @Override
    public void start() {
        if (isRunning()) return;
        super.start();
        if (httpClient == null) {
            httpClient = createHttpClient(cookieStore);
        }
        try {
            httpClient.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void stop() {
        if (!isRunning()) return;
        super.stop();
        try {
            httpClient.stop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public UserAgentProvider getUserAgentProvider() {
        return (UserAgentProvider) kp.get("userAgentProvider");
    }

    @Autowired
    public void setUserAgentProvider(UserAgentProvider provider) {
        kp.put("userAgentProvider", provider);
    }

    public void setTimeoutSeconds(int timeout) {
        kp.put("timeoutSeconds", timeout);
    }
    public int getTimeoutSeconds() {
        return (int) kp.get("timeoutSeconds");
    }
    {
        setTimeoutSeconds(20);
    }

    public void setDigestAlgorithm(String digestAlgorithm) {
        this.digestAlgorithm = digestAlgorithm;
    }

    public String getDigestAlgorithm() {
        return digestAlgorithm;
    }

    /**
     * Indicates whether the HTTP/2 protocol is enabled.
     */
    public boolean getUseHTTP2() {
        return useHTTP2;
    }

    /**
     * Configures whether the HTTP/2 protocol is enabled.
     */
    public void setUseHTTP2(boolean useHTTP2) {
        this.useHTTP2 = useHTTP2;
    }

    /**
     * Indicates whether the HTTP/3 protocol is enabled.
     */
    public boolean getUseHTTP3() {
        return useHTTP3;
    }

    /**
     * Configures whether HTTP/3 protocol should be enabled. Currently experimental and not enabled by default.
     */
    public void setUseHTTP3(boolean useHTTP3) {
        this.useHTTP3 = useHTTP3;
    }

    {
        setMaxLengthBytes(0L); // no limit
    }
    public long getMaxLengthBytes() {
        return (Long) kp.get("maxLengthBytes");
    }
    /**
     * Maximum length in bytes to fetch. Fetch is truncated at this length. A
     * value of 0 means no limit.
     */
    public void setMaxLengthBytes(long timeout) {
        kp.put("maxLengthBytes",timeout);
    }

    {
        setMaxFetchKBSec(0); // no limit
    }
    public int getMaxFetchKBSec() {
        return (Integer) kp.get("maxFetchKBSec");
    }
    /**
     * The maximum KB/sec to use when fetching data from a server. The default
     * of 0 means no maximum.
     */
    public void setMaxFetchKBSec(int rate) {
        kp.put("maxFetchKBSec",rate);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    private record CookieAdaptor(Cookie cookie) implements HttpCookie {
        @Override
        public String getName() {
            return cookie.getName();
        }

        @Override
        public String getValue() {
            return cookie.getValue();
        }

        @Override
        public int getVersion() {
            return cookie.getVersion();
        }

        @Override
        public Map<String, String> getAttributes() {
            return Map.of();
        }

        @Override
        public Instant getExpires() {
            return cookie.getExpiryDate().toInstant();
        }

        @Override
        public long getMaxAge() {
            return -1;
        }

        @Override
        public String getComment() {
            return cookie.getComment();
        }

        @Override
        public String getDomain() {
            return cookie.getDomain();
        }

        @Override
        public String getPath() {
            return cookie.getPath();
        }

        @Override
        public boolean isSecure() {
            return cookie.isSecure();
        }
    }

    private record CookieStoreAdaptor(AbstractCookieStore cookieStore) implements HttpCookieStore {
        @Override
        public boolean add(URI uri, HttpCookie cookie) {
            var basicCookie = new BasicClientCookie(cookie.getName(), cookie.getValue());
            basicCookie.setDomain(cookie.getDomain());
            basicCookie.setPath(cookie.getPath());
            basicCookie.setVersion(cookie.getVersion());
            basicCookie.setComment(cookie.getComment());
            if (cookie.getExpires() != null) {
                basicCookie.setExpiryDate(Date.from(cookie.getExpires()));
            }
            if (cookie.getMaxAge() >= 0) {
                basicCookie.setExpiryDate(new Date(System.currentTimeMillis() + cookie.getMaxAge() * 1000));
            }
            basicCookie.setSecure(cookie.isSecure());
            cookieStore.addCookie(basicCookie);
            return true;
        }

        @Override
        public List<HttpCookie> all() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HttpCookie> match(URI uri) {
            CookieStore hostCookieStore = cookieStore.cookieStoreFor(uri.getHost());
            if (hostCookieStore == null) return Collections.emptyList();
            return hostCookieStore.getCookies().stream()
                    .map(c -> (HttpCookie) new CookieAdaptor(c)).toList();
        }

        @Override
        public boolean remove(URI uri, HttpCookie cookie) {
            return false;
        }

        @Override
        public boolean clear() {
            return false;
        }
    }
}