package com.yaytsa.server.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, MockExternalServicesConfig.class})
@ActiveProfiles("tc")
public abstract class BaseIntegrationTest {

  protected static final Path MEDIA_ROOT = Path.of("/tmp/yaytsa-test-media");

  @Autowired protected TestRestTemplate restTemplate;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected TestDataFactory data;
  @Autowired protected DatabaseCleaner cleaner;

  protected String authToken;
  protected String userId;
  protected String testArtistId;
  protected String testAlbumId;
  protected String testTrackId1;
  protected String testTrackId2;
  protected String testTrackId3;

  @BeforeEach
  void setupBaseData() {
    cleaner.clean();

    var admin = data.createAdmin();
    authToken = admin.rawToken();
    userId = admin.userId();

    var library = data.createLibrary(MEDIA_ROOT);
    testArtistId = library.artistId();
    testAlbumId = library.albumId();
    testTrackId1 = library.trackId1();
    testTrackId2 = library.trackId2();
    testTrackId3 = library.trackId3();
  }

  protected HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + authToken);
    return headers;
  }

  protected HttpHeaders headersWithToken(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + token);
    return headers;
  }

  protected TestDataFactory.AuthResult freshLogin() {
    return data.createAdmin("fresh_" + System.nanoTime(), "password123");
  }
}
