package com.yaytsa.server.integration;

import com.yaytsa.server.infrastructure.persistence.entity.*;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TestDataFactory {

  @Autowired private EntityManager em;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private PasswordEncoder passwordEncoder;

  private final SecureRandom random = new SecureRandom();

  public record AuthResult(String userId, String rawToken) {}

  public record TrackResult(String id, String name) {}

  public record LibraryResult(
      String artistId, String albumId, String trackId1, String trackId2, String trackId3) {}

  public AuthResult createAdmin(String username, String password) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    return tx.execute(
        status -> {
          UserEntity user = new UserEntity();
          user.setUsername(username);
          user.setPasswordHash(passwordEncoder.encode(password));
          user.setActive(true);
          user.setAdmin(true);
          em.persist(user);

          String rawToken = generateToken();
          ApiTokenEntity token = new ApiTokenEntity();
          token.setUser(user);
          token.setToken(DigestUtils.sha256Hex(rawToken));
          token.setDeviceId("test-device-" + UUID.randomUUID().toString().substring(0, 8));
          token.setDeviceName("TestDevice");
          token.setRevoked(false);
          em.persist(token);

          em.flush();
          return new AuthResult(user.getId().toString(), rawToken);
        });
  }

  public AuthResult createAdmin() {
    return createAdmin("admin", "admin123");
  }

  public LibraryResult createLibrary(Path mediaRoot) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    return tx.execute(
        status -> {
          ItemEntity artist = new ItemEntity();
          artist.setType(ItemType.MusicArtist);
          artist.setName("Test Artist");
          artist.setPath("artist:test:" + UUID.randomUUID());
          em.persist(artist);

          ItemEntity album = new ItemEntity();
          album.setType(ItemType.MusicAlbum);
          album.setName("Test Album");
          album.setPath("album:test:" + UUID.randomUUID());
          album.setParent(artist);
          album.setLibraryRoot(mediaRoot.toString());
          em.persist(album);

          createAlbumImage(album, mediaRoot);

          String t1 = createTrack(album, artist, "Test Track 1", 300_000L, mediaRoot);
          String t2 = createTrack(album, artist, "Test Track 2", 240_000L, mediaRoot);
          String t3 = createTrack(album, artist, "Test Track 3", 180_000L, mediaRoot);

          em.flush();
          return new LibraryResult(artist.getId().toString(), album.getId().toString(), t1, t2, t3);
        });
  }

  private String createTrack(
      ItemEntity album, ItemEntity artist, String name, long durationMs, Path mediaRoot) {
    Path trackFile = mediaRoot.resolve(name.replace(" ", "_") + ".wav");
    if (!Files.exists(trackFile)) {
      generateWavFile(trackFile, durationMs);
    }

    ItemEntity trackItem = new ItemEntity();
    trackItem.setType(ItemType.AudioTrack);
    trackItem.setName(name);
    trackItem.setPath(trackFile.toAbsolutePath().toString());
    trackItem.setParent(album);
    trackItem.setContainer("wav");
    trackItem.setSizeBytes(getFileSize(trackFile));
    trackItem.setLibraryRoot(mediaRoot.toString());
    trackItem.setSearchText(("Test Artist Test Album " + name).toLowerCase());
    em.persist(trackItem);

    AudioTrackEntity audioTrack = new AudioTrackEntity();
    audioTrack.setItem(trackItem);
    audioTrack.setAlbum(album);
    audioTrack.setAlbumArtist(artist);
    audioTrack.setDurationMs(durationMs);
    audioTrack.setBitrate(1411);
    audioTrack.setSampleRate(44100);
    audioTrack.setChannels(2);
    audioTrack.setCodec("pcm_s16le");
    em.persist(audioTrack);

    return trackItem.getId().toString();
  }

  private void createAlbumImage(ItemEntity album, Path mediaRoot) {
    Path imagePath = mediaRoot.resolve("cover.jpg");
    if (!Files.exists(imagePath)) {
      generateMinimalJpeg(imagePath);
    }

    ImageEntity image = new ImageEntity();
    image.setItem(album);
    image.setType(ImageType.Primary);
    image.setPath(imagePath.toAbsolutePath().toString());
    image.setWidth(1);
    image.setHeight(1);
    image.setSizeBytes(getFileSize(imagePath));
    image.setTag("test");
    image.setIsPrimary(true);
    em.persist(image);
  }

  private static void generateMinimalJpeg(Path path) {
    try {
      Files.createDirectories(path.getParent());
      byte[] minimalJpeg = {
        (byte) 0xFF,
        (byte) 0xD8,
        (byte) 0xFF,
        (byte) 0xE0,
        0x00,
        0x10,
        0x4A,
        0x46,
        0x49,
        0x46,
        0x00,
        0x01,
        0x01,
        0x00,
        0x00,
        0x01,
        0x00,
        0x01,
        0x00,
        0x00,
        (byte) 0xFF,
        (byte) 0xDB,
        0x00,
        0x43,
        0x00,
        0x08,
        0x06,
        0x06,
        0x07,
        0x06,
        0x05,
        0x08,
        0x07,
        0x07,
        0x07,
        0x09,
        0x09,
        0x08,
        0x0A,
        0x0C,
        0x14,
        0x0D,
        0x0C,
        0x0B,
        0x0B,
        0x0C,
        0x19,
        0x12,
        0x13,
        0x0F,
        0x14,
        0x1D,
        0x1A,
        0x1F,
        0x1E,
        0x1D,
        0x1A,
        0x1C,
        0x1C,
        0x20,
        0x24,
        0x2E,
        0x27,
        0x20,
        0x22,
        0x2C,
        0x23,
        0x1C,
        0x1C,
        0x28,
        0x37,
        0x29,
        0x2C,
        0x30,
        0x31,
        0x34,
        0x34,
        0x34,
        0x1F,
        0x27,
        0x39,
        0x3D,
        0x38,
        0x32,
        0x3C,
        0x2E,
        0x33,
        0x34,
        0x32,
        (byte) 0xFF,
        (byte) 0xC0,
        0x00,
        0x0B,
        0x08,
        0x00,
        0x01,
        0x00,
        0x01,
        0x01,
        0x01,
        0x11,
        0x00,
        (byte) 0xFF,
        (byte) 0xC4,
        0x00,
        0x1F,
        0x00,
        0x00,
        0x01,
        0x05,
        0x01,
        0x01,
        0x01,
        0x01,
        0x01,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x01,
        0x02,
        0x03,
        0x04,
        0x05,
        0x06,
        0x07,
        0x08,
        0x09,
        0x0A,
        0x0B,
        (byte) 0xFF,
        (byte) 0xC4,
        0x00,
        (byte) 0xB5,
        0x10,
        0x00,
        0x02,
        0x01,
        0x03,
        0x03,
        0x02,
        0x04,
        0x03,
        0x05,
        0x05,
        0x04,
        0x04,
        0x00,
        0x00,
        0x01,
        0x7D,
        0x01,
        0x02,
        0x03,
        0x00,
        0x04,
        0x11,
        0x05,
        0x12,
        0x21,
        0x31,
        0x41,
        0x06,
        0x13,
        0x51,
        0x61,
        0x07,
        0x22,
        0x71,
        0x14,
        0x32,
        (byte) 0x81,
        (byte) 0x91,
        (byte) 0xA1,
        0x08,
        0x23,
        0x42,
        (byte) 0xB1,
        (byte) 0xC1,
        0x15,
        0x52,
        (byte) 0xD1,
        (byte) 0xF0,
        0x24,
        0x33,
        0x62,
        0x72,
        (byte) 0x82,
        0x09,
        0x0A,
        0x16,
        0x17,
        0x18,
        0x19,
        0x1A,
        0x25,
        0x26,
        0x27,
        0x28,
        0x29,
        0x2A,
        0x34,
        0x35,
        0x36,
        0x37,
        0x38,
        0x39,
        0x3A,
        0x43,
        0x44,
        0x45,
        0x46,
        0x47,
        0x48,
        0x49,
        0x4A,
        0x53,
        0x54,
        0x55,
        0x56,
        0x57,
        0x58,
        0x59,
        0x5A,
        0x63,
        0x64,
        0x65,
        0x66,
        0x67,
        0x68,
        0x69,
        0x6A,
        0x73,
        0x74,
        0x75,
        0x76,
        0x77,
        0x78,
        0x79,
        0x7A,
        (byte) 0x83,
        (byte) 0x84,
        (byte) 0x85,
        (byte) 0x86,
        (byte) 0x87,
        (byte) 0x88,
        (byte) 0x89,
        (byte) 0x8A,
        (byte) 0x92,
        (byte) 0x93,
        (byte) 0x94,
        (byte) 0x95,
        (byte) 0x96,
        (byte) 0x97,
        (byte) 0x98,
        (byte) 0x99,
        (byte) 0x9A,
        (byte) 0xA2,
        (byte) 0xA3,
        (byte) 0xA4,
        (byte) 0xA5,
        (byte) 0xA6,
        (byte) 0xA7,
        (byte) 0xA8,
        (byte) 0xA9,
        (byte) 0xAA,
        (byte) 0xB2,
        (byte) 0xB3,
        (byte) 0xB4,
        (byte) 0xB5,
        (byte) 0xB6,
        (byte) 0xB7,
        (byte) 0xB8,
        (byte) 0xB9,
        (byte) 0xBA,
        (byte) 0xC2,
        (byte) 0xC3,
        (byte) 0xC4,
        (byte) 0xC5,
        (byte) 0xC6,
        (byte) 0xC7,
        (byte) 0xC8,
        (byte) 0xC9,
        (byte) 0xCA,
        (byte) 0xD2,
        (byte) 0xD3,
        (byte) 0xD4,
        (byte) 0xD5,
        (byte) 0xD6,
        (byte) 0xD7,
        (byte) 0xD8,
        (byte) 0xD9,
        (byte) 0xDA,
        (byte) 0xE1,
        (byte) 0xE2,
        (byte) 0xE3,
        (byte) 0xE4,
        (byte) 0xE5,
        (byte) 0xE6,
        (byte) 0xE7,
        (byte) 0xE8,
        (byte) 0xE9,
        (byte) 0xEA,
        (byte) 0xF1,
        (byte) 0xF2,
        (byte) 0xF3,
        (byte) 0xF4,
        (byte) 0xF5,
        (byte) 0xF6,
        (byte) 0xF7,
        (byte) 0xF8,
        (byte) 0xF9,
        (byte) 0xFA,
        (byte) 0xFF,
        (byte) 0xDA,
        0x00,
        0x08,
        0x01,
        0x01,
        0x00,
        0x00,
        0x3F,
        0x00,
        0x7B,
        0x40,
        0x1B,
        (byte) 0xFF,
        (byte) 0xD9
      };
      Files.write(path, minimalJpeg);
    } catch (IOException e) {
      throw new RuntimeException("Failed to generate test JPEG: " + path, e);
    }
  }

  private String generateToken() {
    byte[] tokenBytes = new byte[32];
    random.nextBytes(tokenBytes);
    return HexFormat.of().formatHex(tokenBytes);
  }

  private static void generateWavFile(Path path, long durationMs) {
    try {
      Files.createDirectories(path.getParent());
      int sampleRate = 44100;
      int channels = 2;
      int bitsPerSample = 16;
      int numSamples = (int) (sampleRate * durationMs / 1000);
      int dataSize = numSamples * channels * (bitsPerSample / 8);

      ByteBuffer buf = ByteBuffer.allocate(44 + Math.min(dataSize, 44100 * 4));
      buf.order(ByteOrder.LITTLE_ENDIAN);
      int actualDataSize = buf.capacity() - 44;

      buf.put("RIFF".getBytes());
      buf.putInt(36 + actualDataSize);
      buf.put("WAVE".getBytes());
      buf.put("fmt ".getBytes());
      buf.putInt(16);
      buf.putShort((short) 1);
      buf.putShort((short) channels);
      buf.putInt(sampleRate);
      buf.putInt(sampleRate * channels * bitsPerSample / 8);
      buf.putShort((short) (channels * bitsPerSample / 8));
      buf.putShort((short) bitsPerSample);
      buf.put("data".getBytes());
      buf.putInt(actualDataSize);

      Files.write(path, buf.array());
    } catch (IOException e) {
      throw new RuntimeException("Failed to generate test WAV: " + path, e);
    }
  }

  private static long getFileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return 0L;
    }
  }
}
