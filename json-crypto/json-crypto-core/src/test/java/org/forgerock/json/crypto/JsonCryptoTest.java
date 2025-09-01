/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright 2011-2016 ForgeRock AS.
 * Portions Copyrighted 2025 Wren Security
 */

package org.forgerock.json.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.crypto.simple.SimpleDecryptor;
import org.forgerock.json.crypto.simple.SimpleEncryptor;
import org.forgerock.json.crypto.simple.SimpleKeySelector;
import org.forgerock.util.encode.Base64;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JsonCryptoTest {

    private static final String SYMMETRIC_CIPHER = "AES/CBC/PKCS5Padding";

    private static final String ASYMMETRIC_CIPHER = "RSA/ECB/OAEPWithSHA1AndMGF1Padding";

    private static final String PASSWORD = "P@55W0RD";

    private static final String PLAINTEXT = "Lorem ipsum dolor sit amet, consectetur adipiscing elit.";

    private SecretKey secretKey;

    private PublicKey publicKey;

    private PrivateKey privateKey;

    private SimpleKeySelector selector = new SimpleKeySelector() {
        @Override public Key select(String key) {
            if (key.equals("secretKey")) {
                return secretKey;
            } else if (key.equals("privateKey")) {
                return privateKey;
            } else {
                return null;
            }
        }
    };

    // ----- initialization ----------

    private SecretKey generateSymmetricKey(int keySize) throws GeneralSecurityException {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(keySize);
        return kg.generateKey();
    }

    @BeforeClass
    public void beforeClass() throws GeneralSecurityException {
        // generate AES 128-bit secret key (Sun JRE restricts to 128-bit key length)
        secretKey = generateSymmetricKey(128);

        // generate RSA 1024-bit key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair kp = kpg.genKeyPair();
        publicKey = kp.getPublic();
        privateKey = kp.getPrivate();
    }

    // ----- happy path ----------

    @DataProvider
    public Object[][] symmetricKeys() throws GeneralSecurityException {
        return new Object[][] {
            { secretKey },
            { generateSymmetricKey(256) }
        };
    }

    @Test(dataProvider = "symmetricKeys")
    public void testSymmetricEncryption(Key secretKey) throws JsonCryptoException {
        JsonValue value = new JsonValue(PLAINTEXT);
        value = new SimpleEncryptor(SYMMETRIC_CIPHER, secretKey, "secretKey").encrypt(value);
        assertThat(value.getObject()).isNotEqualTo(PLAINTEXT);
        value = new SimpleDecryptor(Map.of("secretKey", secretKey)::get).decrypt(value);
        assertThat(value.getObject()).isEqualTo(PLAINTEXT);
    }

    @Test
    public void testAsymmetricEncryption() throws JsonCryptoException {
        JsonValue value = new JsonValue(PLAINTEXT);
        value = new SimpleEncryptor(ASYMMETRIC_CIPHER, publicKey, "privateKey").encrypt(value);
        assertThat(value.getObject()).isNotEqualTo(PLAINTEXT);
        value = new SimpleDecryptor(selector).decrypt(value);
        assertThat(value.getObject()).isEqualTo(PLAINTEXT);
    }

    @Test
    public void testJsonCryptoTransformer() throws JsonCryptoException {
        JsonValue value = new JsonValue(PLAINTEXT);
        JsonEncryptor encryptor = new SimpleEncryptor(SYMMETRIC_CIPHER, secretKey, "secretKey");
        JsonValue crypto = new JsonCrypto(encryptor.getType(), encryptor.encrypt(value)).toJsonValue();
        assertThat(crypto.as(new JsonDecryptFunction(new SimpleDecryptor(selector))).getObject()).isEqualTo(PLAINTEXT);
    }

    @Test
    public void testDeepObjectEncryption() throws JsonCryptoException {
        SimpleEncryptor encryptor = new SimpleEncryptor(SYMMETRIC_CIPHER, secretKey, "secretKey");

        // encrypt a simple value
        JsonValue value = new JsonValue(PASSWORD);
        value = new JsonCrypto(encryptor.getType(), encryptor.encrypt(value)).toJsonValue();
        assertThat(value.getObject()).isNotEqualTo(PASSWORD);
        assertThat(JsonCrypto.isJsonCrypto(value)).isTrue();

        Map<String, Object> inner = new HashMap<>();
        inner.put("password", value.getObject());
        value = new JsonValue(new HashMap<>());
        value.put("user", inner);
        value.put("description", PLAINTEXT);

        // decrypt the deep object
        value = value.as(new JsonDecryptFunction(new SimpleDecryptor(selector)));
        assertThat(value.get(new JsonPointer("/user/password")).getObject()).isEqualTo(PASSWORD);

        // encrypt a complex object
        value = new JsonValue(value.getObject());
        value = new JsonCrypto(encryptor.getType(), encryptor.encrypt(value)).toJsonValue();
        assertThat(JsonCrypto.isJsonCrypto(value)).isTrue();

        // decrypt the complex object
        value = value.as(new JsonDecryptFunction(new SimpleDecryptor(selector)));
        assertThat(value.get(new JsonPointer("/user/password")).getObject()).isEqualTo(PASSWORD);
        assertThat(value.get("description").getObject()).isEqualTo(PLAINTEXT);
    }

    // ----- exceptions ----------

    @Test(expectedExceptions = JsonCryptoException.class)
    public void testDroppedIV() throws JsonCryptoException {
        JsonValue value = new JsonValue(PLAINTEXT);
        value = new SimpleEncryptor(SYMMETRIC_CIPHER, secretKey, "secretKey").encrypt(value);
        value.remove("iv");
        new SimpleDecryptor(selector).decrypt(value);
    }

    @Test(expectedExceptions = JsonCryptoException.class)
    public void testUnknownKey() throws JsonCryptoException {
        JsonValue value = new JsonValue(PLAINTEXT);
        value = new SimpleEncryptor(SYMMETRIC_CIPHER, secretKey, "secretKey").encrypt(value);
        value.put("key", "somethingCompletelyDifferent");
        new SimpleDecryptor(selector).decrypt(value);
        new SimpleDecryptor(selector).decrypt(value);
    }

    @Test(expectedExceptions = JsonCryptoException.class)
    public void testTamperedIV() throws Exception {
        JsonValue value = new JsonValue(PLAINTEXT);
        value = new SimpleEncryptor(SYMMETRIC_CIPHER, secretKey, "secretKey").encrypt(value);

        byte[] iv = Base64.decode(value.get("iv").asString());
        iv[0] ^= 0x01;
        value.put("iv", Base64.encode(iv));
        new SimpleDecryptor(selector).decrypt(value);
    }
}
