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
 * Copyright 2011-2015 ForgeRock AS.
 * Portions Copyrighted 2025 Wren Security
 */

package org.forgerock.json.crypto.simple;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.json.crypto.JsonCryptoException;
import org.forgerock.json.crypto.JsonDecryptor;
import org.forgerock.util.encode.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Decrypts a {@code $crypto} JSON object value encrypted with the
 * {@code x-simple-encryption} type.
 */
public class SimpleDecryptor implements JsonDecryptor {
    private static final Logger logger = Logger.getLogger(SimpleDecryptor.class.getName());

    /** The type of cryptographic representation that this decryptor supports. */
    public static final String TYPE = "x-simple-encryption";

    /** Converts between JSON constructs and Java objects. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** TODO: Description. */
    private final SimpleKeySelector selector;

    /**
     * TODO: Description.
     *
     * @param selector TODO.
     */
    public SimpleDecryptor(SimpleKeySelector selector) {
        this.selector = selector;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private Key select(String alias) throws JsonCryptoException {
        Key result = selector.select(alias);
        if (result == null) {
            throw new JsonCryptoException("key not found: " + alias);
        }
        return result;
    }

    @Override
    public JsonValue decrypt(JsonValue value) throws JsonCryptoException {
        try {
            JsonValue key = value.get("key").required();
            String cipher = value.get("cipher").required().asString();
            Key symmetricKey;
            if (key.isString()) {
                symmetricKey = select(key.asString());
            } else {
                Key privateKey = select(key.get("key").required().asString());
                Cipher asymmetric = Cipher.getInstance(key.get("cipher").required().asString());
                asymmetric.init(Cipher.DECRYPT_MODE, privateKey);
                byte[] ciphertext = Base64.decode(key.get("data").required().asString());
                symmetricKey = new SecretKeySpec(asymmetric.doFinal(ciphertext), cipher.split("/", 2)[0]);
            }

            if (value.isDefined("salt")) {
                final byte[] macTag = Base64.decode(value.get("mac").required().asString());
                HKDFKeyGenerator.HKDFMasterKey masterKey = HKDFKeyGenerator.extractMasterKey(symmetricKey.getEncoded(),
                        Base64.decode(value.get("salt").required().asString()));

                Key macKey = HKDFKeyGenerator.expandKey(masterKey, SimpleEncryptor.MAC_ALGORITHM,
                        SimpleEncryptor.MAC_KEY_SIZE);
                final Map<String, Object> map = new TreeMap<>(value.asMap());
                map.remove("mac");
                final byte[] computedMacTag = SimpleEncryptor.mac(map, macKey);
                if (!MessageDigest.isEqual(macTag, computedMacTag)) {
                    throw new GeneralSecurityException("Invalid tag");
                }

                symmetricKey = HKDFKeyGenerator.expandKey(masterKey, symmetricKey.getAlgorithm(),
                        key.isString() ? symmetricKey.getEncoded().length : SimpleEncryptor.ASYMMETRIC_AES_KEY_SIZE);
            }

            Cipher symmetric = Cipher.getInstance(cipher);
            String iv = value.get("iv").asString();

            IvParameterSpec ivps = (iv == null ? null : new IvParameterSpec(Base64.decode(iv)));
            symmetric.init(Cipher.DECRYPT_MODE, symmetricKey, ivps);
            byte[] plaintext = symmetric.doFinal(Base64.decode(value.get("data").required().asString()));
            return new JsonValue(mapper.readValue(plaintext, Object.class));
        } catch (GeneralSecurityException | IOException | JsonValueException e) {
            logger.log(Level.FINE, "SimpleDecryptor: decryption failure", e);
            throw new JsonCryptoException("Decryption failed");
        }
    }

}
