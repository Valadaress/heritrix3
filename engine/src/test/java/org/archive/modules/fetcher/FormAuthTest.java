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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.archive.url.URIException;
import org.apache.commons.io.IOUtils;
import org.archive.crawler.prefetch.PreconditionEnforcer;
import org.archive.modules.CrawlMetadata;
import org.archive.modules.CrawlURI;
import org.archive.modules.CrawlURI.FetchType;
import org.archive.modules.credential.HtmlFormCredential;
import org.archive.net.UURI;
import org.archive.net.UURIFactory;
import org.archive.util.Recorder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.*;
import org.eclipse.jetty.security.authentication.FormAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.security.Password;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/* Somewhat redundant to org.archive.crawler.selftest.FormAuthSelfTest, but 
 * the code is written, it's easier to run in eclipse, and no doubt tests 
 * somewhat different stuff. */
public class FormAuthTest {

    private static Logger logger = Logger.getLogger(FormAuthTest.class.getName());
    @TempDir
    Path tempDir;

    protected static final String DEFAULT_PAYLOAD_STRING = "abcdefghijklmnopqrstuvwxyz0123456789\n";

    protected static final String FORM_AUTH_REALM    = "form-auth-realm";
    protected static final String FORM_AUTH_ROLE     = "form-auth-role";
    protected static final String FORM_AUTH_LOGIN    = "form-auth-login";
    protected static final String FORM_AUTH_PASSWORD = "form-auth-password";

    protected FetchHTTP fetchHttp;

    protected FetchHTTP getFetcher() throws IOException {
        if (fetchHttp == null) { 
            fetchHttp = new FetchHTTP();
            fetchHttp.setCookieStore(new SimpleCookieStore());
            fetchHttp.setServerCache(new DefaultServerCache());
            CrawlMetadata uap = new CrawlMetadata();
            uap.setUserAgentTemplate(getClass().getName());
            fetchHttp.setUserAgentProvider(uap);
            
            fetchHttp.start();
        }
        
        return fetchHttp;
    }

    protected Recorder getRecorder() throws IOException {
        if (Recorder.getHttpRecorder() == null) {
            Recorder httpRecorder = new Recorder(tempDir.toFile(),
                    getClass().getName(), 16 * 1024, 512 * 1024);
            Recorder.setHttpRecorder(httpRecorder);
        }

        return Recorder.getHttpRecorder();
    }

    protected CrawlURI makeCrawlURI(String uri) throws URIException,
            IOException {
        UURI uuri = UURIFactory.getInstance(uri);
        CrawlURI curi = new CrawlURI(uuri);
        curi.setSeed(true);
        curi.setRecorder(getRecorder());
        return curi;
    }
    
    // convenience methods to get strings from raw recorded i/o
    /**
     * Raw response including headers.
     */
    protected String rawResponseString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    /**
     * Raw message body, before any unchunking or content-decoding.
     */ 
    protected String messageBodyString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getMessageBodyReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    /**
     * Message body after unchunking but before content-decoding.
     */ 
    protected String entityString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getEntityReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    /**
     * Unchunked, content-decoded message body.
     */ 
    protected String contentString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getContentReplayInputStream());
        return new String(buf, "US-ASCII");
    }
    protected String httpRequestString(CrawlURI curi) throws IOException, UnsupportedEncodingException {
        byte[] buf = IOUtils.toByteArray(curi.getRecorder().getRecordedOutput().getReplayInputStream());
        return new String(buf, "US-ASCII");
    }

    @Test
    public void testFormAuth() throws Exception {
        startHttpServers();

        HtmlFormCredential cred = new HtmlFormCredential();
        cred.setDomain("localhost:7779");
        cred.setLoginUri("/j_security_check");
        HashMap<String, String> formItems = new HashMap<String,String>();
        formItems.put("j_username", FORM_AUTH_LOGIN);
        formItems.put("j_password", FORM_AUTH_PASSWORD);
        cred.setFormItems(formItems);

        getFetcher().getCredentialStore().getCredentials().put("form-auth-credential",
                cred);

        CrawlURI curi = makeCrawlURI("http://localhost:7779/");
        getFetcher().process(curi);
        logger.info('\n' + httpRequestString(curi) + contentString(curi));
        runDefaultChecks(curi, "hostHeader");

        // jetty needs us to hit a restricted url so it can redirect to the
        // login page and remember where to redirect back to after successful
        // login (if not we get a NPE within jetty)
        curi = makeCrawlURI("http://localhost:7779/auth/1");
        getFetcher().process(curi);
        logger.info('\n' + httpRequestString(curi) + "\n\n" + rawResponseString(curi));
        assertEquals(302, curi.getFetchStatus());
        assertTrue(curi.getHttpResponseHeader("Location").startsWith("/login.html"));

        PreconditionEnforcer preconditionEnforcer = new PreconditionEnforcer();
        preconditionEnforcer.setServerCache(getFetcher().getServerCache());
        preconditionEnforcer.setCredentialStore(getFetcher().getCredentialStore());
        boolean result = preconditionEnforcer.credentialPrecondition(curi);
        assertTrue(result);

        CrawlURI loginUri = curi.getPrerequisiteUri();
        assertEquals("http://localhost:7779/j_security_check", loginUri.toString());

        // there's some special logic with side effects in here for the login uri itself
        result = preconditionEnforcer.credentialPrecondition(loginUri);
        assertFalse(result);

        loginUri.setRecorder(getRecorder());
        getFetcher().process(loginUri);
        logger.info('\n' + httpRequestString(loginUri) + "\n\n" + rawResponseString(loginUri));
        assertEquals(302, loginUri.getFetchStatus()); // 302 on successful login
        assertEquals("/auth/1", loginUri.getHttpResponseHeader("location"));

        curi = makeCrawlURI("http://localhost:7779/auth/1");
        getFetcher().process(curi);
        logger.info('\n' + httpRequestString(curi) + contentString(curi));
        runDefaultChecks(curi, "hostHeader", "requestLine");
    }

    protected static final String LOGIN_HTML = 
            "<html>"
            + "<head><title>Log In</title></head>"
            + "<body>"
            + "<form action='/j_security_check' method='post'>"
            + "<div> username: <input name='j_username' type='text'/> </div>"
            + "<div> password: <input name='j_password' type='password'/> </div>"
            + "<div> <input type='submit' /> </div>" + "</form>" + "</body>"
            + "</html>";

    protected static class FormAuthTestServlet extends HttpServlet {

        public FormAuthTestServlet() {
            super();
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String target = request.getRequestURI();
            if (target.endsWith("/set-cookie")) {
                response.addCookie(new Cookie("test-cookie-name", "test-cookie-value"));
            }
            
            if (target.equals("/login.html")) {
                response.setContentType("text/html;charset=US-ASCII");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getOutputStream().write(LOGIN_HTML.getBytes("US-ASCII"));
            } else {
                response.setContentType("text/plain;charset=US-ASCII");
                response.setDateHeader("Last-Modified", 0);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getOutputStream().write(DEFAULT_PAYLOAD_STRING.getBytes("US-ASCII"));
            }
        }
    }
    
    protected static SecurityHandler makeAuthWrapper(Authenticator authenticator,
            final String role, String realm, final String login,
            final String password) {
        Constraint constraint = Constraint.from(role);

        ConstraintMapping constraintMapping = new ConstraintMapping();
        constraintMapping.setConstraint(constraint);
        constraintMapping.setPathSpec("/auth/*");

        UserStore userStore = new UserStore();
        userStore.addUser(login, new Password(password), new String[]{role});

        HashLoginService loginService = new HashLoginService(realm);
        loginService.setUserStore(userStore);

        ConstraintSecurityHandler authWrapper = new ConstraintSecurityHandler();
        authWrapper.setAuthenticator(authenticator);
        authWrapper.setConstraintMappings(new ConstraintMapping[] {constraintMapping});
        authWrapper.setLoginService(loginService);

        return authWrapper;
    }

    protected void startHttpServers() throws Exception {
        // server for form auth
        Server server = new Server();
        
        ServerConnector sc = new ServerConnector(server);
        sc.setHost("127.0.0.1");
        sc.setPort(7779);
        server.addConnector(sc);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(FormAuthTestServlet.class, "/");

        FormAuthenticator formAuthenticatrix = new FormAuthenticator("/login.html", null, false);

        SecurityHandler authWrapper = makeAuthWrapper(formAuthenticatrix,
                FORM_AUTH_ROLE, FORM_AUTH_REALM, FORM_AUTH_LOGIN,
                FORM_AUTH_PASSWORD);

        context.setSecurityHandler(authWrapper);
        server.setHandler(context);
        
        server.start();
    }

    protected void runDefaultChecks(CrawlURI curi, String... exclusionsArray)
            throws IOException, UnsupportedEncodingException {

        Set<String> exclusions = new HashSet<String>(Arrays.asList(exclusionsArray));

        String requestString = httpRequestString(curi);
        if (!exclusions.contains("requestLine")) {
            assertTrue(requestString.startsWith("GET / HTTP/1.0\r\n"));
        }
        assertTrue(requestString.contains("User-Agent: " + getClass().getName() + "\r\n"));
        assertTrue(requestString.matches("(?s).*Connection: [Cc]lose\r\n.*"));
        if (!exclusions.contains("acceptHeaders")) {
            assertTrue(requestString.contains("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n"));
        }
        if (!exclusions.contains("hostHeader")) {
            assertTrue(requestString.contains("Host: localhost:7777\r\n"));
        }
        assertTrue(requestString.endsWith("\r\n\r\n"));

        // check sizes
        assertEquals(DEFAULT_PAYLOAD_STRING.length(), curi.getContentLength());
        assertEquals(curi.getContentSize(), curi.getRecordedSize());

        // check various 
        assertEquals("sha1:TQ5R6YVOZLTQENRIIENVGXHOPX3YCRNJ", curi.getContentDigestSchemeString());
        assertEquals("text/plain;charset=US-ASCII", curi.getContentType());
        assertEquals(Charset.forName("US-ASCII"), curi.getRecorder().getCharset());
        assertTrue(curi.getCredentials().isEmpty());
        assertTrue(curi.getFetchDuration() >= 0);
        assertTrue(curi.getFetchStatus() == 200);
        assertTrue(curi.getFetchType() == FetchType.HTTP_GET);

        // check message body, i.e. "raw, possibly chunked-transfer-encoded message contents not including the leading headers"
        assertEquals(DEFAULT_PAYLOAD_STRING, messageBodyString(curi));

        // check entity, i.e. "message-body after any (usually-unnecessary) transfer-decoding but before any content-encoding (eg gzip) decoding"
        assertEquals(DEFAULT_PAYLOAD_STRING, entityString(curi));

        // check content, i.e. message-body after possibly tranfer-decoding and after content-encoding (eg gzip) decoding
        assertEquals(DEFAULT_PAYLOAD_STRING, contentString(curi));
        assertEquals(DEFAULT_PAYLOAD_STRING.substring(0, 10), curi.getRecorder().getContentReplayPrefixString(10));
        assertEquals(DEFAULT_PAYLOAD_STRING, curi.getRecorder().getContentReplayCharSequence().toString());

        assertTrue(curi.getNonFatalFailures().isEmpty());
    }

}
