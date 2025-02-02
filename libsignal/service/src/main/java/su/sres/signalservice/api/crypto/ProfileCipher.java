package su.sres.signalservice.api.crypto;

import org.signal.zkgroup.profiles.ProfileKey;
import org.whispersystems.libsignal.util.ByteUtil;
import su.sres.signalservice.internal.util.Util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class ProfileCipher {

  private static final int NAME_PADDED_LENGTH_1 = 53;
  private static final int NAME_PADDED_LENGTH_2 = 257;
  private static final int ABOUT_PADDED_LENGTH_1 = 128;
  private static final int ABOUT_PADDED_LENGTH_2 = 254;
  private static final int ABOUT_PADDED_LENGTH_3 = 512;

  public static final int MAX_POSSIBLE_NAME_LENGTH  = NAME_PADDED_LENGTH_2;
  public static final int MAX_POSSIBLE_ABOUT_LENGTH = ABOUT_PADDED_LENGTH_3;
  public static final int EMOJI_PADDED_LENGTH       = 32;

  private final ProfileKey key;

  public ProfileCipher(ProfileKey key) {
    this.key = key;
  }

  public byte[] encryptName(byte[] input, int paddedLength) {
    try {
      byte[] inputPadded = new byte[paddedLength];

      if (input.length > inputPadded.length) {
        throw new IllegalArgumentException("Input is too long: " + new String(input));
      }

      System.arraycopy(input, 0, inputPadded, 0, input.length);

      byte[] nonce = Util.getSecretBytes(12);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.serialize(), "AES"), new GCMParameterSpec(128, nonce));

      return ByteUtil.combine(nonce, cipher.doFinal(inputPadded));
    } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | BadPaddingException | NoSuchPaddingException | IllegalBlockSizeException | InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  public byte[] decryptName(byte[] input) throws InvalidCiphertextException {
    try {
      if (input.length < 12 + 16 + 1) {
        throw new InvalidCiphertextException("Too short: " + input.length);
      }

      byte[] nonce = new byte[12];
      System.arraycopy(input, 0, nonce, 0, nonce.length);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.serialize(), "AES"), new GCMParameterSpec(128, nonce));

      byte[] paddedPlaintext = cipher.doFinal(input, nonce.length, input.length - nonce.length);
      int    plaintextLength = 0;

      for (int i=paddedPlaintext.length-1;i>=0;i--) {
        if (paddedPlaintext[i] != (byte)0x00) {
          plaintextLength = i + 1;
          break;
        }
      }

      byte[] plaintext = new byte[plaintextLength];
      System.arraycopy(paddedPlaintext, 0, plaintext, 0, plaintextLength);

      return plaintext;
    } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchPaddingException | IllegalBlockSizeException e) {
      throw new AssertionError(e);
    } catch (InvalidKeyException | BadPaddingException e) {
      throw new InvalidCiphertextException(e);
    }
  }

  public boolean verifyUnidentifiedAccess(byte[] theirUnidentifiedAccessVerifier) {
    try {
      if (theirUnidentifiedAccessVerifier == null || theirUnidentifiedAccessVerifier.length == 0) return false;

      byte[] unidentifiedAccessKey = UnidentifiedAccess.deriveAccessKeyFrom(key);

      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(unidentifiedAccessKey, "HmacSHA256"));

      byte[] ourUnidentifiedAccessVerifier = mac.doFinal(new byte[32]);

      return MessageDigest.isEqual(theirUnidentifiedAccessVerifier, ourUnidentifiedAccessVerifier);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  public static int getTargetNameLength(String name) {
    int nameLength = name.getBytes(StandardCharsets.UTF_8).length;

    if (nameLength <= NAME_PADDED_LENGTH_1) {
      return NAME_PADDED_LENGTH_1;
    } else {
      return NAME_PADDED_LENGTH_2;
    }
  }

  public static int getTargetAboutLength(String about) {
    int aboutLength = about.getBytes(StandardCharsets.UTF_8).length;

    if (aboutLength <= ABOUT_PADDED_LENGTH_1) {
      return ABOUT_PADDED_LENGTH_1;
    } else if (aboutLength < ABOUT_PADDED_LENGTH_2){
      return ABOUT_PADDED_LENGTH_2;
    } else {
      return ABOUT_PADDED_LENGTH_3;
    }
  }
}
