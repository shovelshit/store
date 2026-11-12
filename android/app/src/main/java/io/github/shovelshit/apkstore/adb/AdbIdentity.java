package io.github.shovelshit.apkstore.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;

public final class AdbIdentity {
    private static final byte[] SHA1_PREFIX = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private AdbIdentity() {}

    public static KeyPair loadOrCreate(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create ADB identity directory");
        File privateFile = new File(directory, "adbkey.pk8");
        File publicFile = new File(directory, "adbkey.pub.der");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (privateFile.exists() && publicFile.exists()) {
                return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(readFile(publicFile))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(readFile(privateFile))));
            }
            if (privateFile.exists() || publicFile.exists()) throw new IOException("Incomplete ADB identity");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair identity = generator.generateKeyPair();
            writeNew(privateFile, identity.getPrivate().getEncoded());
            writeNew(publicFile, identity.getPublic().getEncoded());
            return identity;
        } catch (GeneralSecurityException e) {
            throw new IOException("Invalid ADB RSA identity", e);
        }
    }

    private static byte[] readFile(File file) throws IOException {
        if (file.length() > 4096) throw new IOException("Invalid ADB identity file size");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            for (int pos = 0; pos < bytes.length;) {
                int n = input.read(bytes, pos, bytes.length - pos);
                if (n < 0) throw new IOException("Truncated ADB identity file");
                pos += n;
            }
        }
        return bytes;
    }

    private static void writeNew(File destination, byte[] contents) throws IOException {
        File temp = File.createTempFile(".adbkey-", ".tmp", destination.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(contents);
                output.getFD().sync();
            }
            if (!temp.renameTo(destination)) throw new IOException("Cannot persist ADB identity");
        } finally {
            if (temp.exists() && !temp.delete()) temp.deleteOnExit();
        }
    }

    public static byte[] signToken(KeyPair identity, byte[] token) throws IOException {
        if (token.length != 20) throw new IOException("ADB authentication token must be 20 bytes");
        try {
            byte[] digestInfo = new byte[SHA1_PREFIX.length + token.length];
            System.arraycopy(SHA1_PREFIX, 0, digestInfo, 0, SHA1_PREFIX.length);
            System.arraycopy(token, 0, digestInfo, SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, identity.getPrivate());
            return cipher.doFinal(digestInfo);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot sign ADB authentication token", e);
        }
    }

    public static String publicKeyPayload(KeyPair identity) throws IOException {
        if (!(identity.getPublic() instanceof RSAPublicKey)) throw new IOException("ADB identity requires RSA");
        RSAPublicKey key = (RSAPublicKey) identity.getPublic();
        if (key.getModulus().bitLength() != 2048 || !key.getPublicExponent().equals(BigInteger.valueOf(65537)))
            throw new IOException("ADB identity requires RSA-2048 exponent 65537");
        BigInteger modulus = key.getModulus();
        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64);
        buffer.putInt(modulus.intValue() == 0 ? 0 : BigInteger.ZERO.subtract(modulus.modInverse(BigInteger.ONE.shiftLeft(32))).intValue());
        writeLittle(buffer, modulus);
        writeLittle(buffer, BigInteger.ONE.shiftLeft(4096).mod(modulus));
        buffer.putInt(65537);
        return base64(buffer.array()) + " adb@apkstore";
    }

    private static String base64(byte[] source) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder encoded = new StringBuilder((source.length + 2) / 3 * 4);
        for (int i = 0; i < source.length; i += 3) {
            int a = source[i] & 255;
            int b = i + 1 < source.length ? source[i + 1] & 255 : 0;
            int c = i + 2 < source.length ? source[i + 2] & 255 : 0;
            encoded.append(alphabet.charAt(a >>> 2));
            encoded.append(alphabet.charAt(((a & 3) << 4) | (b >>> 4)));
            encoded.append(i + 1 < source.length ? alphabet.charAt(((b & 15) << 2) | (c >>> 6)) : '=');
            encoded.append(i + 2 < source.length ? alphabet.charAt(c & 63) : '=');
        }
        return encoded.toString();
    }

    private static void writeLittle(ByteBuffer buffer, BigInteger value) {
        byte[] big = value.toByteArray();
        for (int i = 0; i < 256; i++) buffer.put(i < big.length ? big[big.length - 1 - i] : 0);
    }
}
